# Why

[![CI](https://github.com/slayyyyer/why-lang/actions/workflows/ci.yml/badge.svg)](https://github.com/slayyyyer/why-lang/actions/workflows/ci.yml)

A small, Ruby-flavored page language for the JVM. MVC at the scale of a single page: imports, a
controller, and a view in one file, with the URL as the filename and SQLite data access built in.
Controller and view are separate sections. Drop it in a folder and it's live. No router, no build
step, no framework boilerplate.

> Named in tribute to _why the lucky stiff_, whose tiny, whimsical tools (Camping, Shoes, *Why's (Poignant) Guide to Ruby*) made programming feel like play.

Requires JDK 21+.

## Quick start

The `io.github.slayyyyer.why` Gradle plugin is on Maven Central. Point both repository blocks at it,
then put pages in `src/why`:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("io.github.slayyyyer.why") version "0.2.0"
}

repositories {
    mavenCentral()
}
```

```bash
mkdir -p src/why
printf '<h1>Hello from a file</h1>\n' > src/why/index.why
./gradlew whyRun
# Why is serving /…/src/why at http://localhost:8080
```

Two repositories, two jobs: `pluginManagement` resolves the plugin marker, which is on Maven
Central rather than the Plugin Portal, and the project `repositories` block resolves
`io.github.slayyyyer:why-lang`, which the plugin adds as an `api` dependency.

`./gradlew whyCheck` parses and checks every page without serving it and is wired into `check`. It
reports unresolved imports and partials, duplicate `def`s, undefined function calls and URL
collisions, then prints a page map. It exits 1 when it finds anything and does not touch the
database.

## Without a project

A folder of pages needs no Gradle project. `why new` writes a starter site and `bin/why` runs the
CLI jar:

```bash
./gradlew shadowJar
./bin/why new mysite
./bin/why mysite
```

`bin/why` uses `build/libs/why.jar` unless `WHY_JAR` points at another copy; `bin/why.cmd` is the
Windows launcher. `why new` writes pages, a stylesheet and a `schema.sql`, and refuses a folder that
is not empty.

## Gradle plugin

The plugin targets Java projects. Put pages and assets in `src/why`:

```
src/why/index.why
src/why/style.css
```

The plugin applies `java-library`, adds the Why runtime (`io.github.slayyyyer:why-lang:0.2.0`) as an `api`
dependency, copies `src/why` into the jar at `META-INF/why-site`, and writes a sorted
`META-INF/why-site.index`. It adds:

- `./gradlew whyRun` — serve `src/why`.
- `./gradlew whyCheck` — compile every page and print the page map; wired into `check`.
- `./gradlew jar` — package pages and assets as classpath resources.

The `why` extension overrides the defaults:

```kotlin
why {
    siteDir.set(layout.projectDirectory.dir("src/why"))
    host.set("127.0.0.1")
    port.set(8080)
    diagnostics.set(true)       // --dev for whyRun
    secureCookies.set(false)
    databaseFile.set(layout.projectDirectory.file("data.db"))
}
```

`whyRun` uses the CLI default database, `src/why/data.db`, unless `databaseFile` is set. Database
files and their journals (`*.db`, `*.db-journal`, `*.db-wal`, `*.db-shm`) are excluded from the jar
and index; set `databaseFile` outside `src/why` to keep development data out of the source tree.

Serve a packaged site from Java, including from a library jar on the classpath:

```java
Site site = Site.fromClasspath();                 // or Site.fromClasspath(loader, dbFile)
WhyServer server = new WhyServer(site, 8080);
server.start();
```

`Site.fromClasspath()` extracts the indexed resources to a temporary folder and bootstraps
`schema.sql` when the database does not exist. Pass an explicit `dbFile` to keep data across
restarts. Resources from multiple jars merge when their page names differ; a duplicate path is an
error.

## Testing a site

`why.testing.TestServer` boots a site in process on a free port, does not follow redirects, and
submits the CSRF token, so a write page is an ordinary JUnit test:

```java
try (TestServer server = TestServer.start(Path.of("src/why"))) {
    assertEquals(200, server.get("/").status());
    assertEquals(303, server.post("/guestbook", Map.of("name", "why")).status());
}
```

## From source

One Gradle build produces the library, the plugin and the shaded CLI jar. To work on Why itself:

```bash
./gradlew shadowJar
java -jar build/libs/why.jar ./site
```

`java -jar build/libs/why.jar check ./site` parses and checks the site without serving it. The CLI
accepts `--port`, `--host`, `--db`, `--dev`, `--secure-cookies` and `--trust-proxy`; the plugin
exposes the same settings through the `why` extension. `PORT`, `HOST`, `WHY_DB` and
`WHY_SECURE_COOKIES` supply the defaults for the first four, so a container needs no shell wrapper.
`--trust-proxy` marks cookies `Secure` when a loopback proxy forwards `X-Forwarded-Proto: https`,
and derives `request.remote` from the last `X-Forwarded-For` hop that is not loopback; both headers
are ignored from any other peer.

To try a local build of the plugin against a consumer project, run
`./gradlew publishToMavenLocal :why-gradle-plugin:publishToMavenLocal` and put `mavenLocal()` ahead
of `mavenCentral()` in both repository blocks.

## Releasing to Maven Central

The runtime, the Gradle plugin and its marker publish under `io.github.slayyyyer` with Nmcp.
Releases are uploaded from a local machine with `./publish-central.sh`; because the build uses
`USER_MANAGED`, the upload then waits for `./gradlew nmcpPublishDeployment -PnmcpDeploymentId=<id>`.
See [RELEASING.md](RELEASING.md) for the namespace, GPG key, Central token and release steps.

## Website

The project site lives in `docs/` as plain HTML/CSS with no build step. Pushes to `main` that touch
`docs/` or `.github/workflows/pages.yml` publish it to GitHub Pages. In the repository settings,
set **Pages → Build and deployment → Source** to **GitHub Actions**.

Preview it locally with the JDK's built-in static server. On this Windows machine (Git Bash), `-d`
needs an absolute path:

```bash
.tools/jdk/bin/jwebserver -p 4000 -d "$(cygpath -m "$PWD/docs")"
```

`jwebserver` rejects a relative `-d` path on every OS. To avoid paths entirely, change into the
folder and let it use its default directory — with the JDK on your `PATH`:

```bash
cd docs
jwebserver -p 4000
```

This working copy has no system-wide Java, so it carries a portable JDK in the gitignored `.tools/`
folder; the Gradle wrapper supplies the build tool. The same build from this folder:

```bash
export JAVA_HOME="$PWD/.tools/jdk"; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew shadowJar
.tools/jdk/bin/java -jar build/libs/why.jar ./site --port 3000
```

Status: **a proof of concept**. The page format, controller language, views, SQLite access, forms,
redirects, one-shot flash messages, diagnostics and `why check` are implemented. This is not a
production runtime.

## Security

Why pages are **trusted executable code**, not passive templates. They run inside the server process
with access to request data and the configured database, and there is no sandbox. **Never deploy or
accept page uploads from untrusted users**: an untrusted page is unsafe and is equivalent to running
untrusted code on the server.

For an exposed deployment:

- terminate TLS in a reverse proxy and use `--secure-cookies` (or `--trust-proxy` when the proxy is
  loopback and forwards `X-Forwarded-Proto: https` and `X-Forwarded-For`); do not expose the plain
  HTTP listener
- run Why as a dedicated, non-root service account with least-privilege filesystem permissions
- keep the SQLite database outside the web root, using an explicit `--db` path
- keep regular, protected backups outside the web root and test restoring them
- keep `--dev` off so error responses stay generic, and review the service's logs and network access
- keep the default security headers: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  `Content-Security-Policy: frame-ancestors 'none'` and `X-Frame-Options: DENY`

See [SECURITY.md](SECURITY.md) for the full security policy and deployment checklist, and
[deploy/](deploy/README.md) for the container and systemd recipes.

## M0 — the shell

- Java 21, the Gradle wrapper, one runnable `build/libs/why.jar` (SQLite JDBC shaded in).
- `com.sun.net.httpserver.HttpServer` with a virtual-thread executor.
- Every `.why` file under the site folder is served at its path: `/` is `index.why`,
  `/posts/archive` is `posts/archive.why`, and `/about` also finds `about.why`. Files whose name
  starts with `_` are import-only and have no URL.
- Allowlisted public assets (HTML, CSS, JS, JSON, SVG, images, fonts and text) are served with
  content types. Database, schema, backup and other files are not public.
- 404 page; with `--dev`, a 500 page with `file:line`, the offending source line and a caret, the
  partial or import chain that led there, did-you-mean hints, and the message; without it, errors
  are generic while the details are logged server-side; one request log line per request.
- Security headers on every response (`nosniff`, `no-referrer`, `frame-ancestors 'none'` and
  `X-Frame-Options: DENY`); a page may opt into a fuller CSP with `response.csp("...")`.
- Hard per-request limits: 64 KiB bodies, 16 KiB/100 headers, 32 concurrent requests, a 10-second
  timeout, 100k interpreter steps, 1M rendered characters, 10k/4 MiB database results, 4 MiB static
  assets, 4 KiB flash values and 256 KiB pages/imports.
- `java -jar why.jar [site] [--host HOST] [--port N] [--db FILE] [--dev] [--secure-cookies]
  [--trust-proxy]`; `java -jar why.jar check [site]` parses and checks the whole site without
  serving it.

## M1 — the language and views

- The three page sections with ordering validation and exact file/line errors.
- `imports:` give you partials (`{{> name}}`) and controller functions (`def`s), recursively,
  with cycle detection.
- A Ruby-flavored core language (below).
- Handlebars-flavored views (below).
- A `db` global over SQLite, plus `request` and `params` globals.

## M2 — the first write path

`/guestbook` in the demo site is a writable page: a POST form, one bound `db.exec` insert, and a
303 redirect back to the same page. It adds:

- `form` — fields from an `application/x-www-form-urlencoded` POST body (empty on GET); also
  `request.form`
- `redirect("/path")` — stop the page and answer `303 See Other`; the target must be a
  path-absolute same-origin path
- `flash` — one-shot messages carried across the redirect: `flash.set("notice", "…")`, then
  `{{flash.notice}}` on the next page request

The page-model friction found while building it is logged in [FRICTION.md](FRICTION.md).

## File format

A page has up to three sections. Each label is a line containing exactly the label at column 0:

```
imports:
header
footer

controller:
posts = db.query("select * from posts order by id desc")
title = "Latest posts"

view:
{{> header}}
<h1>{{title}}</h1>
{{#each posts}}
  <h2>{{it.title}}</h2>
{{/each}}
{{> footer}}
```

- Sections are optional but must appear in the order `imports`, `controller`, `view`.
- A file with no labels at all is a view (plain HTML works).
- Any other `word:` line at column 0 before `view:` is an error with file and line.
- Once `view:` starts, everything is template text to the end of the file.
- Each import is one bare name. It resolves to `<name>.why` next to the importing file first,
  then in the site root.
- A file whose name starts with `_` is import-only: `_header.why` is listed as `_header` in
  `imports:` (and used as `{{> _header}}`), but `/_header` and `/_header.why` both answer 404.
  Static files are covered too, so `_notes.css` is not served.

Imports are transitive. An imported page's `controller:` contributes only its `def`s; its
top-level statements do not run when you import it. `def`s are hoisted, so a page may call one
before its definition line.

## Language

```ruby
# values
n = 42
pi = 3.14
name = "why #{n}"          # double quotes interpolate
raw = 'no #{interpolation}'  # single quotes are literal
list = [1, 2, 3]
post = {title: "Why", views: 3}

# operators: + - * / % == != < <= > >= && || ! and or not
# integer / integer is integer division, like Ruby

# control flow
if n > 10
  label = "big"
elsif n == 0
  label = "nothing"
else
  label = "small"
end

unless done
  title = "still going"
end

while n > 0
  n -= 1
  if n == 2
    next
  end
  if n == 0
    break
  end
end

# functions
def shout(text)
  text.upcase + "!"
end

# blocks close over the surrounding scope
total = 0
[1, 2, 3].each do |number|
  total += number
end
doubled = [1, 2, 3].map do |number|
  number * 2
end
```

Only `nil` and `false` are falsy (Ruby rules: `0` and `""` are truthy). Undefined names read as
`nil`. `+` with a string on either side concatenates. Strings are indexed by character:
`"why"[0]` is `"w"`, `"why"[-1]` is `"y"`, and an index past either end is `nil`.

Built-in methods:

- Strings: `length`/`size`, `upcase`, `downcase`, `strip`, `split`, `include?`, `start_with?`,
  `ends_with?`, `to_i`, `to_f`, `reverse`, `replace`, `capitalize`
- Lists: `length`/`size`, `first`, `last`, `push`/`append`, `join`, `include?`, `reverse`, `sum`,
  `each`/`map`/`select` with a block
- Hashes: `keys`, `values`, `length`/`size`, `has_key?`, `each` with a block
- Numbers: `to_i`, `to_f`, `abs`, `round`
- Functions: `str(x)`, `int(x)`, `float(x)`, `redirect(path)`, `redirect_back(fallback)`,
  `status(code)`, `halt(code)`, `validate(form, rules)`, `pbkdf2(password, salt, iterations)`,
  `password_hash(password, iterations)`, `password_matches(password, stored)`,
  `password_needs_rehash(stored, iterations)`

## Views

```
{{title}}              escaped with the same expression grammar as the controller
{{{title}}}            raw
{{& title}}            raw
{{! a comment }}

{{#if posts.length > 0}} … {{else}} … {{/if}}
{{#unless done}} … {{/unless}}

{{#each posts}}
  <h2>{{it.title}}</h2>
  <p>#{{@index}} · first={{@first}} · last={{@last}}</p>
{{else}}
  <p>No posts.</p>
{{/each}}

{{> header}}
```

Inside `{{#each}}` the current item is `it`; `@index`, `@first` and `@last` are available, and
`@key` when iterating a hash. A mustache holds a real expression, so `{{shout(post.title)}}` and
`{{posts.length}}` work. Only `nil` and `false` are falsy in views too, so an empty list's `length`
is `0` and still truthy; use `posts.length > 0` for an empty state.

## The `db` global

SQLite, at `<site>/data.db` by default (`--db` overrides it); point `--db` outside the site folder
for an exposed deployment. One connection per request.

Connections enable `PRAGMA foreign_keys`, so a declared `references` clause is enforced and
`on delete cascade` works. The migration runner turns enforcement off while a migration rebuilds a
table and runs `foreign_key_check` before recording it.

A migration file is recorded with a SHA-256 checksum when it runs; editing it afterwards raises
instead of being ignored, so add a new file. Statements split at top-level semicolons, so a
string or a `CREATE TRIGGER ... BEGIN ... END` body keeps its own.

```ruby
posts = db.query("select * from posts order by id desc")   # list of hashes
post  = db.get("select * from posts where id = ?", id)     # first row or nil
n     = db.exec("insert into posts (title) values (?)", title)
```

Rows are hashes keyed by column label, so `{{it.title}}` and `post.title` work. Arguments are
bound as prepared-statement parameters.

A parameter can be named, which is useful when one value appears more than once:

```ruby
db.query("select * from posts where user_id = :who or id in (select post_id from likes where user_id = :who)",
         who: uid)
db.query("select * from posts where id = ? and user_id = :who", id, who: uid)  # mixed with ?
```

Names are checked against the SQL: a missing value and an unused name both raise. Numbered
placeholders such as `?1` are rejected; use `?` or a name. Use `#{}` only to build SQL structure,
never to insert values. An identifier cannot be a parameter, so a page
that must choose a table or column passes it through an explicit allowlist:

```ruby
table = sql.ident(params.table, ["likes", "reposts"])
db.exec("delete from " + table + " where user_id = ?", uid)
```

If the database file does not exist, `<site>/schema.sql` is applied once so a site folder is
self-contained. A `migrations/` folder takes over the schema: every `.sql` file in it applies once,
in filename order, each in its own transaction, recorded in a `schema_migrations` table. A fresh
database runs them in order; an existing one applies only what is pending. The first migration
added to a database created by `schema.sql` should therefore be written against that schema with
`create table if not exists`, or the baseline row inserted into `schema_migrations`.

`seed.sql` is demo data, not schema. With `--dev` it runs once after the migrations, and only when
the database file did not exist before that boot. A deployed site with a persistent disk never
re-inserts rows; an ephemeral host that starts empty still gets its demo users. Write the seed with
`insert or ignore` so applying it by hand is safe too.

## Request globals

- `params` — query parameters as strings, e.g. `/post?id=3` gives `params.id == "3"`.
- `form` — POSTed form fields as strings, empty on GET. Only `application/x-www-form-urlencoded`
  bodies are read (64 KiB cap).
- `request` — a hash with `method`, `path`, `query`, `params`, `form` and `remote` (the caller's IP
  address, for throttling or auditing; behind `--trust-proxy`, a loopback proxy's last
  `X-Forwarded-For` hop replaces the socket peer).
- `flash` — one-shot messages. `flash.set("notice", "…")` stores a value for the next page
  request, where `{{flash.notice}}` can read it; the request after that no longer has it. Messages
  live in a bounded, expiring in-memory store keyed by a server-issued `why_session` cookie;
  restarting the server drops them.
- `csrf` — `csrf.token`, a per-browser secret for form posts. It rides an HttpOnly `why_csrf`
  cookie, so it survives a restart and needs no server-side state. A POST without the matching
  `_token` field is rejected with 403.
- `session` — long-lived values for this browser; see [Sessions](#sessions).

## Sessions

`session` keeps small values for one browser across requests and server restarts. They live in the
site database (`why_sessions`) and are named by the HttpOnly `why_sid` cookie.

```ruby
session.login(user.id)            # clear values, set user_id, rotate the session ID
me = session.get("user_id")
session.set("notice", nil)        # nil removes the name
session.clear()                   # sign out
session.rotate()                  # rotate manually when a page manages its own sign-in
```

The cookie lasts 30 idle days; every page that loads a live session slides that window. Expired
rows are pruned when a session is written. Use `session.login(user.id)` after verifying
credentials: it clears values from before sign-in, stores `user_id`, and rotates the session ID,
so a pre-login cookie cannot be replayed. Set any welcome flash after `session.login`.
`session.rotate()` remains available when a page manages its own sign-in state. A session holds at
most 32 names, 64-character names and 4096-character values.

## Passwords

`pbkdf2(password, salt, iterations)` derives a 32-byte PBKDF2-HMAC-SHA256 key and returns it as
lowercase hex. The API accepts 10,000 to 1,000,000 iterations; use at least 600,000 for new
PBKDF2-HMAC-SHA256 hashes. Store the parameters with the key so the cost can be raised later
without invalidating rows:

```
pbkdf2-sha256$600000$<salt>$<key>
```

`password_matches(password, stored)` parses that row and compares the keys in constant time; a
malformed row is a failed match. `password_hash(password, iterations)` generates a fresh 16-byte
salt and returns the row to store. `password_needs_rehash(stored, iterations)` is true when the
row is malformed or costs less than `iterations`, so a sign-in can upgrade it while the password
is in hand:

```
if user != nil and password_matches(password, user.password_hash)
  if password_needs_rehash(user.password_hash, rounds())
    db.exec("update users set password_hash = ? where id = ?",
            password_hash(password, rounds()), user.id)
  end
end
```

Never store the password itself.

## Responses

`redirect("/path")` answers `303 See Other`; it accepts only a single-slash local path, never an
absolute URL, `//host` or a backslash variant. `redirect_back("/")` uses the posted `back` field
when it is such a path and the fallback otherwise.

A page controls the rest through `response`, or the `status`/`halt` builtins:

```ruby
response.status(201)                      # 200-599; defaults to 200
status(201)                               # the same, as a builtin
halt(404)                                 # set the status and stop before the view renders
response.header("X-Why", "created")
response.json({ok: true, count: 2})       # answers JSON and ignores the rendered view
response.csp("default-src 'self'")        # opt into a policy
```

`status(code)` sets the status and keeps rendering, which is what a page that wants a 404 body
uses; `halt(code)` sets it and stops the controller or view immediately.

`response.header` refuses the headers the server owns (`Content-Length`, `Set-Cookie` and friends)
and any value that is not one line of printable ASCII. The default `frame-ancestors 'none'` is
appended unless the policy sets its own.

Every response also carries `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
`Content-Security-Policy: frame-ancestors 'none'`, `X-Frame-Options: DENY` and an `X-Request-Id`
that also starts the request log line. `/health` answers `200 ok` without parsing a page, and
`SIGTERM` drains in-flight requests for up to five seconds.

## Forms

Two things make a write page safe without a framework: a CSRF token and a validator.

```ruby
controller:
errors = validate(form, {name: "required", message: "required,min:3"})
if request.method == "POST" and errors.length == 0
  db.exec("insert into guestbook (name, message) values (?, ?)", form.name, form.message)
  redirect("/guestbook")
end

view:
<form method="post">
  <input type="hidden" name="_token" value="{{csrf.token}}">
  <input name="name" value="{{form.name}}">
  {{#if errors.name}}<p class="error">{{errors.name}}</p>{{/if}}
  <button>Sign</button>
</form>
```

`validate` returns a hash of field to message, empty when everything passes; rules are
comma-separated `required`, `min:N` and `max:N`. `form` still holds the submitted values on the
error path, so `value="{{form.name}}"` re-populates the form. A form POST without the token is
rejected with 403 before the page runs.

## Project layout

```
build.gradle.kts, settings.gradle.kts, gradlew
site/                      demo site: pages, partials, CSS, schema.sql
docs/                      project site, published to GitHub Pages
why-gradle-plugin/         the `io.github.slayyyyer.why` plugin and its TestKit tests
deploy/                    Dockerfile, systemd unit and reverse-proxy notes
editors/vscode/            TextMate grammar and VS Code extension
tools/check-docs.mjs       fails when the README and site copies drift
roadmap.md                 ranked follow-ups after 0.1.0
.github/workflows/ci.yml     tests and demo site check
.github/workflows/pages.yml  GitHub Pages publishing
src/main/java/why/
  Main.java                CLI
  WhyServer.java           HTTP handler, errors, static files
  Site.java                the .why extension and URL → file resolution
  ClasspathSite.java       extraction for sites packaged by the Gradle plugin
  PageLoader.java          imports/controller/view section splitting
  PageCompiler.java        import resolution and parsing
  Program.java             a parsed page
  Checker.java             parse-and-check site map
  lang/                    Lexer, Parser, Ast, Interpreter, Scope, Value, Db
  tpl/                     Template, TemplateParser
  util/Html.java
src/test/java/why/         JUnit 5 tests (language, templates, pages, HTTP)
```

## Scope

Milestones M0–M2, kept deliberately small. Classes, packages, bytecode, Truffle, sandboxing and
performance work are out of scope. Decisions, assumptions and deferred features live in
[NOTES.md](NOTES.md); M2's awkward edges are in [FRICTION.md](FRICTION.md).

## License

Why is available under the MIT License. See [LICENSE](LICENSE).
