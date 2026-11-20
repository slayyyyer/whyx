package why;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import why.lang.Csrf;
import why.lang.Db;
import why.lang.ExecutionBudget;
import why.lang.Flash;
import why.lang.Redirect;
import why.lang.Response;
import why.lang.Session;
import why.util.Html;

/** The HTTP server: one handler, virtual threads, every request compiled and rendered from disk. */
public final class WhyServer {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_HEADER_COUNT = 100;
    private static final int MAX_CONCURRENT_REQUESTS = 32;
    private static final long REQUEST_TIMEOUT_MILLIS = 10_000;
    private static final long MAX_INTERPRETER_STEPS = 100_000;
    private static final int MAX_OUTPUT_CHARS = 1_000_000;
    private static final int MAX_DIAGNOSTIC_CHARS = 500;
    /** Below this, compression costs more than it saves. */
    private static final int MIN_GZIP_BYTES = 512;

    private final Site site;
    private final PageCompiler compiler;
    private final HttpServer server;
    private final boolean diagnostics;
    private final boolean secureCookies;
    private final long requestTimeoutMillis;
    private final Semaphore requestSlots = new Semaphore(MAX_CONCURRENT_REQUESTS);
    private final ExecutorService executor;
    private final ScheduledExecutorService timeouts;
    /** Bounded, expiring one-shot flashes keyed by server-issued session IDs. */
    private final FlashStore flashes = new FlashStore();
    /** Long-lived values a page stores with {@code session.set}, keyed by the why_sid cookie. */
    private final SessionStore sessions = new SessionStore();

    /** Binds loopback with no development diagnostics. */
    public WhyServer(Site site, int port) throws IOException {
        this(site, "127.0.0.1", port, false, false);
    }

    public WhyServer(Site site, String host, int port, boolean diagnostics, boolean secureCookies)
            throws IOException {
        this(site, host, port, diagnostics, secureCookies, REQUEST_TIMEOUT_MILLIS);
    }

    /** Package-private so tests can exercise the timeout quickly. */
    WhyServer(Site site, String host, int port, boolean diagnostics, boolean secureCookies,
            long requestTimeoutMillis) throws IOException {
        this.site = site;
        this.compiler = new PageCompiler(site);
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.diagnostics = diagnostics;
        this.secureCookies = secureCookies;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.timeouts = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "why-request-timeout");
            thread.setDaemon(true);
            return thread;
        });
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** The bound address, for the startup message and tests. */
    InetSocketAddress address() {
        return server.getAddress();
    }

    /** Visible for tests: request slots not currently held by a handler. */
    int freeRequestSlots() {
        return requestSlots.availablePermits();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        stop(Duration.ZERO);
    }

    /** Stops accepting new requests, then waits up to {@code drain} for in-flight ones. */
    public void stop(Duration drain) {
        int seconds = (int) Math.min(60, Math.max(0, drain.toSeconds()));
        server.stop(seconds);
        executor.shutdownNow();
        timeouts.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        String requestId = Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        exchange.getResponseHeaders().set("X-Request-Id", requestId);
        setSecurityHeaders(exchange);
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        int status = 500;
        boolean admitted = false;
        ScheduledFuture<?> timeoutTask = null;
        AtomicBoolean finished = new AtomicBoolean();
        try {
            if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("POST")) {
                status = 405;
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            if (path.equals("/health")) {
                status = 200;
                sendText(exchange, 200, "ok");
                return;
            }
            if (headersTooLarge(exchange)) {
                status = 431;
                sendText(exchange, 431, "Request Header Fields Too Large");
                return;
            }
            int contentLengthStatus = contentLengthStatus(exchange);
            if (contentLengthStatus != 0) {
                status = contentLengthStatus;
                sendText(exchange, status, status == 413 ? "Payload Too Large" : "Bad Request");
                return;
            }
            if (!requestSlots.tryAcquire()) {
                status = 503;
                sendText(exchange, 503, "Service Unavailable");
                return;
            }
            admitted = true;
            Thread requestThread = Thread.currentThread();
            timeoutTask = timeouts.schedule(() -> {
                if (finished.compareAndSet(false, true)) {
                    System.err.println("why: request timed out: " + method + " " + path);
                    requestThread.interrupt();
                    exchange.close();
                }
            }, requestTimeoutMillis, TimeUnit.MILLISECONDS);

            Path file = site.resolve(path);
            if (file == null) {
                status = 404;
                sendHtml(exchange, 404, notFound(path));
                return;
            }
            if (site.isPage(file)) {
                status = servePage(exchange, file);
            } else if (method.equals("POST")) {
                status = 405;
                sendText(exchange, 405, "Method Not Allowed");
            } else {
                status = serveStatic(exchange, file);
            }
        } catch (BodyTooLarge e) {
            status = 413;
            try {
                sendText(exchange, 413, "Payload Too Large");
            } catch (IOException ignored) {
                // The response may already be committed; nothing else to send.
            }
        } catch (RequestTimeout e) {
            status = 504;
            System.err.println("why: request timed out: " + method + " " + path);
            try {
                sendHtml(exchange, 504, timeoutPage());
            } catch (IOException ignored) {
                // The watchdog may have closed the exchange already.
            }
        } catch (RuntimeException e) {
            status = 500;
            e.printStackTrace();
            try {
                sendHtml(exchange, 500, diagnostics
                        ? errorPage("Internal error", String.valueOf(e), "")
                        : internalErrorPage());
            } catch (IOException ignored) {
                // The response may already be committed; nothing else to send.
            }
        } finally {
            finished.set(true);
            if (timeoutTask != null) timeoutTask.cancel(false);
            if (admitted) requestSlots.release();
            long millis = (System.nanoTime() - started) / 1_000_000;
            System.out.println(requestId + " " + method + " " + path + " " + status + " " + millis + "ms");
            exchange.close();
        }
    }

    /** Rejects requests before parsing bodies or doing any page work. */
    private static boolean headersTooLarge(HttpExchange exchange) {
        int count = 0;
        long bytes = 0;
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            count += entry.getValue().size();
            for (String value : entry.getValue()) {
                bytes += utf8Length(entry.getKey()) + utf8Length(value) + 4;
                if (count > MAX_HEADER_COUNT || bytes > MAX_HEADER_BYTES) return true;
            }
        }
        return count > MAX_HEADER_COUNT || bytes > MAX_HEADER_BYTES;
    }

    /** 0 when acceptable, otherwise the response status for Content-Length. */
    private static int contentLengthStatus(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders().getFirst("Content-Length");
        if (raw == null) return 0;
        try {
            long length = Long.parseLong(raw.strip());
            if (length < 0) return 400;
            return length > MAX_BODY_BYTES ? 413 : 0;
        } catch (NumberFormatException e) {
            return 400;
        }
    }

    private static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private int servePage(HttpExchange exchange, Path file) throws IOException {
        String display = site.relative(file);
        String sessionId = sessionId(exchange);
        Map<String, Object> carried = Map.of();
        if (sessionId != null) {
            carried = flashes.take(sessionId);
            if (carried == null) {
                sessionId = null;
                carried = Map.of();
            }
        }
        Flash flash = new Flash(carried);
        Response page = new Response();
        Map<Object, Object> fields = form(exchange);
        String csrfValue = cookie(exchange, "why_csrf");
        boolean hasToken = Csrf.valid(csrfValue);
        Csrf csrf = new Csrf(hasToken ? csrfValue : Csrf.newToken(), !hasToken);
        if (exchange.getRequestMethod().equals("POST") && !csrf.matches(fields.get("_token"))) {
            sendHtml(exchange, 403, csrfPage());
            return 403;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis);
        String html;
        String requestedSession = browserSessionCookie(exchange);
        String activeSession = requestedSession;
        Session session = new Session();
        try {
            Program program = compiler.compile(file);
            Map<String, Object> globals = globals(exchange, flash, csrf, fields, session);
            globals.put("response", page);
            try (Db db = new Db(site.dbFile())) {
                globals.put("db", db);
                if (activeSession != null) session.load(sessions.load(db, activeSession));
                try {
                    html = program.render(globals,
                            new ExecutionBudget(MAX_INTERPRETER_STEPS, MAX_OUTPUT_CHARS, deadline));
                } finally {
                    // A page that wrote to the session has it stored before the response leaves,
                    // including when the write ends in a redirect or a form error.
                    if (session.changed()) {
                        activeSession = sessions.save(db, activeSession, session);
                    }
                    writeSessionCookie(exchange, requestedSession, activeSession, session);
                }
            }
            if (html.length() > MAX_OUTPUT_CHARS) {
                throw new WhyError("output budget exceeded", display, 0);
            }
        } catch (Redirect redirect) {
            persistFlash(exchange, sessionId, flash);
            setCsrfCookie(exchange, csrf);
            sendRedirect(exchange, redirect.location());
            return 303;
        } catch (RequestTimeout e) {
            persistFlash(exchange, sessionId, flash);
            System.err.println("why: request timed out: " + display);
            try {
                sendHtml(exchange, 504, timeoutPage());
            } catch (IOException ignored) {
                // The watchdog may have closed the exchange already.
            }
            return 504;
        } catch (WhyError e) {
            persistFlash(exchange, sessionId, flash);
            WhyError located = e.locate(display, e.line());
            System.err.println("why: " + located);
            sendHtml(exchange, 500, diagnostics ? whyErrorPage(located) : internalErrorPage());
            return 500;
        }
        persistFlash(exchange, sessionId, flash);
        setCsrfCookie(exchange, csrf);
        applyPageHeaders(exchange, page);
        applyPageCsp(exchange, page);
        int status = page.status();
        if (page.json() != null) {
            send(exchange, status, "application/json; charset=utf-8",
                    page.json().getBytes(StandardCharsets.UTF_8));
        } else {
            sendHtml(exchange, status, html);
        }
        return status;
    }

    /** The why_session cookie value; it is accepted only if the server issued it. */
    private static String sessionId(HttpExchange exchange) {
        String id = cookie(exchange, "why_session");
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}") ? id : null;
    }

    /** One cookie value by name, or null. */
    private static String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                int equals = part.indexOf('=');
                if (equals < 0) continue;
                if (!part.substring(0, equals).strip().equals(name)) continue;
                String value = part.substring(equals + 1).strip();
                if (!value.isEmpty() && value.length() <= 256) return value;
            }
        }
        return null;
    }

    /** Issues the CSRF cookie only when the page actually rendered its token. */
    private void setCsrfCookie(HttpExchange exchange, Csrf csrf) {
        if (!csrf.shouldSetCookie()) return;
        StringBuilder cookie = new StringBuilder("why_csrf=").append(csrf.token())
                .append("; Path=/; HttpOnly; SameSite=Lax");
        if (secureCookies || exchange instanceof HttpsExchange) cookie.append("; Secure");
        exchange.getResponseHeaders().add("Set-Cookie", cookie.toString());
    }

    /** Stores messages set this request; a visitor without an issued session gets a cookie. */
    private void persistFlash(HttpExchange exchange, String sessionId, Flash flash) {
        Map<String, Object> pending = flash.pending();
        if (pending.isEmpty()) return;
        String id = sessionId;
        if (id == null) {
            id = flashes.put(pending);
            setSessionCookie(exchange, id);
        } else {
            flashes.put(id, pending);
        }
    }

    private void setSessionCookie(HttpExchange exchange, String id) {
        StringBuilder cookie = new StringBuilder("why_session=").append(id)
                .append("; Path=/; HttpOnly; SameSite=Lax");
        if (secureCookies || exchange instanceof HttpsExchange) cookie.append("; Secure");
        exchange.getResponseHeaders().add("Set-Cookie", cookie.toString());
    }

    /** The why_sid cookie value; it is accepted only in the shape this server mints. */
    private static String browserSessionCookie(HttpExchange exchange) {
        String id = cookie(exchange, "why_sid");
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}") ? id : null;
    }

    /**
     * Issues the session cookie the first time a page stores something, re-issues it with a fresh
     * lifetime while the session lives, and expires it once the page clears the session (sign out).
     */
    private void writeSessionCookie(HttpExchange exchange, String requested, String active,
            Session session) {
        String cookie;
        if (session.isEmpty()) {
            // A page that never touched the session keeps it, so a 500 cannot sign a visitor out.
            if (requested == null || !session.changed()) return;
            cookie = "why_sid=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";
        } else {
            if (active == null) return;
            // The store keeps a session for 30 idle days and restarts that clock on every page,
            // so the cookie is re-issued on every page too: one left at its first expiry would
            // sign out a visitor who never stopped browsing.
            cookie = "why_sid=" + active + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
                    + SessionStore.TTL_SECONDS;
        }
        if (secureCookies || exchange instanceof HttpsExchange) cookie += "; Secure";
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private int serveStatic(HttpExchange exchange, Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        long size = Files.size(file);
        if (size > AllocationLimits.STATIC_ASSET_BYTES) throw assetTooLarge(file, size);
        String etag = "\"" + Long.toHexString(size) + "-"
                + Long.toHexString(Files.getLastModifiedTime(file).toMillis()) + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=60");
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            exchange.sendResponseHeaders(304, -1);
            return 304;
        }
        byte[] body = Files.readAllBytes(file);
        if (body.length > AllocationLimits.STATIC_ASSET_BYTES) throw assetTooLarge(file, body.length);
        send(exchange, 200, contentType(name), body);
        return 200;
    }

    private WhyError assetTooLarge(Path file, long bytes) {
        String display = site.relative(file);
        return new WhyError("static asset '" + display + "' is " + bytes + " bytes; the limit is "
                + AllocationLimits.STATIC_ASSET_BYTES, display, 0);
    }

    private Map<String, Object> globals(HttpExchange exchange, Flash flash, Csrf csrf,
            Map<Object, Object> form, Session session) {
        Map<Object, Object> params = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) parseUrlEncoded(rawQuery, params);

        Map<Object, Object> request = new LinkedHashMap<>();
        request.put("method", exchange.getRequestMethod());
        request.put("path", exchange.getRequestURI().getPath());
        request.put("query", rawQuery == null ? "" : rawQuery);
        request.put("params", params);
        request.put("form", form);
        request.put("remote", remoteAddress(exchange));

        Map<String, Object> globals = new LinkedHashMap<>();
        globals.put("request", request);
        globals.put("params", params);
        globals.put("form", form);
        globals.put("flash", flash);
        globals.put("csrf", csrf);
        globals.put("session", session);
        return globals;
    }

    /** The caller's IP address as text, for pages that throttle or audit by client. */
    private static String remoteAddress(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        InetAddress address = remote == null ? null : remote.getAddress();
        return address == null ? "" : address.getHostAddress();
    }

    /** POSTed urlencoded fields; empty on GET or other content types. */
    private Map<Object, Object> form(HttpExchange exchange) {
        Map<Object, Object> fields = new LinkedHashMap<>();
        if (!exchange.getRequestMethod().equals("POST")) return fields;
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null
                && !contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return fields;
        }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) throw new BodyTooLarge();
            parseUrlEncoded(new String(body, StandardCharsets.UTF_8), fields);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw new RequestTimeout();
            throw new WhyError("cannot read the request body: " + e.getMessage());
        }
        return fields;
    }

    /** Internal signal for a body that exceeded the hard request limit. */
    private static final class BodyTooLarge extends RuntimeException {
        BodyTooLarge() {
            super(null, null, false, false);
        }
    }

    private static void parseUrlEncoded(String encoded, Map<Object, Object> into) {
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            into.put(decode(key), decode(value));
        }
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    /** Headers every response gets; a page may replace the CSP through {@code response.csp}. */
    private static void setSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "frame-ancestors 'none'");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    }

    /** Page-set headers, applied before the body so the server fills only what is still unset. */
    private static void applyPageHeaders(HttpExchange exchange, Response page) {
        page.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    }

    /** Applies a page's opt-in CSP while keeping frame protection in the policy. */
    private static void applyPageCsp(HttpExchange exchange, Response page) {
        String policy = page.csp();
        if (policy == null) return;
        if (!policy.toLowerCase(Locale.ROOT).contains("frame-ancestors")) {
            if (policy.endsWith(";")) policy = policy.substring(0, policy.length() - 1).stripTrailing();
            policy += "; frame-ancestors 'none'";
        }
        exchange.getResponseHeaders().set("Content-Security-Policy", policy);
    }

    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        send(exchange, status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        send(exchange, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendRedirect(HttpExchange exchange, String location) throws IOException {
        if (!Redirect.isSameOriginPath(location)) {
            throw new WhyError("redirect must be a same-origin path", null, 0);
        }
        exchange.getResponseHeaders().set("Location", location);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(303, -1);
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        if (!exchange.getResponseHeaders().containsKey("Cache-Control")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
        }
        if (exchange.getRequestMethod().equals("HEAD") || status == 204 || status == 304) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        if (body.length >= MIN_GZIP_BYTES && compressible(contentType) && acceptsGzip(exchange)) {
            body = gzip(body);
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.getResponseHeaders().set("Vary", "Accept-Encoding");
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static boolean compressible(String contentType) {
        return contentType.startsWith("text/")
                || contentType.startsWith("application/json")
                || contentType.startsWith("application/javascript")
                || contentType.startsWith("image/svg+xml");
    }

    private static boolean acceptsGzip(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("Accept-Encoding");
        if (values == null) return false;
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).contains("gzip")) return true;
        }
        return false;
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, body.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(body);
        }
        return out.toByteArray();
    }

    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        return switch (extension) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    private static String notFound(String path) {
        return layout("Not found", "<h1>404</h1><p>No page at <code>"
                + Html.escape(clip(path)) + "</code>.</p>");
    }

    /** The 500 page: file:line, the source excerpt with a caret, the message, then the path here. */
    private String whyErrorPage(WhyError error) {
        StringBuilder body = new StringBuilder("<h1>Why error</h1>");
        if (!error.location().isEmpty()) {
            body.append("<p class=\"loc\">").append(Html.escape(error.location())).append("</p>");
        }

        String source = site.sourceLine(error.file(), error.line());
        if (source != null) {
            source = clip(source).replace("\t", "    ");
            int column = caretColumn(source, error.getMessage());
            String number = String.valueOf(error.line());
            body.append("<pre class=\"snippet\">")
                    .append(number).append(" | ").append(Html.escape(source)).append('\n')
                    .append(" ".repeat(number.length())).append(" | ")
                    .append(" ".repeat(column)).append("^</pre>");
        }

        body.append("<pre>").append(Html.escape(clip(error.getMessage()))).append("</pre>");

        if (!error.chain().isEmpty()) {
            body.append("<p class=\"via\">Reached via</p><ul class=\"chain\">");
            for (WhyError.Frame frame : error.chain()) {
                body.append("<li><code>")
                        .append(Html.escape(frame.file() + ":" + frame.line()))
                        .append("</code>");
                String callSite = site.sourceLine(frame.file(), frame.line());
                if (callSite != null) body.append(" ").append(Html.escape(clip(callSite).strip()));
                body.append("</li>");
            }
            body.append("</ul>");
        }

        return layout("Why error", body.toString());
    }

    /**
     * Where to put the caret: under the quoted name in the message when it appears on the line,
     * otherwise under the first non-space character.
     */
    private static int caretColumn(String line, String message) {
        int quote = message.indexOf('\'');
        if (quote >= 0) {
            int end = message.indexOf('\'', quote + 1);
            if (end > quote) {
                int at = line.indexOf(message.substring(quote + 1, end));
                if (at >= 0) return at;
            }
        }
        return line.length() - line.stripLeading().length();
    }

    /** A form POST without a token: the page never rendered {@code csrf.token}. */
    private static String csrfPage() {
        return layout("Form expired", "<h1>403 — Form expired</h1>"
                + "<p>Reload the page and submit the form again.</p>");
    }

    /** A 504 response with no paths, source, SQL or stack details. */
    private static String timeoutPage() {
        return layout("Request timeout",
                "<h1>504 — Request timeout</h1><p>The request took too long to complete.</p>");
    }

    /** A 500 response with no paths, source, SQL or stack details. */
    private static String internalErrorPage() {
        return layout("Internal server error",
                "<h1>500 — Internal server error</h1><p>The server could not complete this request.</p>");
    }

    private static String errorPage(String title, String message, String location) {
        StringBuilder body = new StringBuilder("<h1>").append(Html.escape(title)).append("</h1>");
        if (!location.isEmpty()) {
            body.append("<p class=\"loc\">").append(Html.escape(clip(location))).append("</p>");
        }
        body.append("<pre>").append(Html.escape(clip(message))).append("</pre>");
        return layout(title, body.toString());
    }

    private static String clip(String text) {
        if (text == null || text.length() <= MAX_DIAGNOSTIC_CHARS) return text;
        return text.substring(0, MAX_DIAGNOSTIC_CHARS) + "…";
    }

    private static String layout(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + Html.escape(title) + "</title>"
                + "<style>body{font:16px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;margin:6vh auto;max-width:44rem;"
                + "padding:0 1.5rem;color:#222}h1{font-size:1.4rem}.loc{color:#a00}"
                + "pre{background:#f4f4f4;padding:1rem;overflow:auto;border-left:3px solid #a00}"
                + ".snippet{background:#fff;padding:.5rem .75rem}"
                + ".via{margin:1.5rem 0 .25rem;color:#777}"
                + ".chain{margin:.25rem 0;padding-left:1.2rem;color:#555}.chain li{margin:.1rem 0}"
                + "code{background:#f4f4f4;padding:.1rem .3rem}</style></head><body>" + body + "</body></html>";
    }
}
