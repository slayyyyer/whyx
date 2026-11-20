# Security

## Trusted pages only

Why pages are **trusted executable code**, not passive templates. A page's controller runs inside the
server process with access to request data and the configured SQLite database, and can execute
arbitrary SQL supported by that database. Why has no sandbox or permission model for page code.

**Do not deploy pages uploaded or edited by untrusted users.** An untrusted page is equivalent to
running untrusted code on the server. Review and control every `.why` file that reaches a deployed
site.

## Deploying Why

Treat an exposed Why deployment as a prototype service and put the following controls in place:

- **Terminate TLS in a reverse proxy.** Why's built-in listener is plain HTTP. Use a TLS proxy
  (or an `HttpsServer` integration) and start Why with `--secure-cookies` so the session cookie is
  marked `Secure`. Do not expose the plain HTTP listener directly to the internet.
- **Run as a dedicated, non-root service account.** Use least-privilege filesystem permissions.
  The site directory should normally be read-only to the service; only the database directory and
  any explicitly required runtime paths should be writable.
- **Keep the database outside the web root.** Pass an explicit `--db` path such as
  `/var/lib/why/data.db`, rather than leaving `data.db` inside the served site folder. The static
  file resolver blocks the configured database, but keeping it outside the site is defense in depth.
- **Keep the default response headers.** Every response sets `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer`, `Content-Security-Policy: frame-ancestors 'none'` and
  `X-Frame-Options: DENY`. A page may opt into a fuller CSP with `response.csp("...")`; keep
  `frame-ancestors` restrictive in any policy it sets.
- **Maintain regular backups.** Back up the SQLite database and the site content, protect backup
  confidentiality and access, and test restores. Backups belong outside the web root as well.

Development diagnostics are off by default; leave `--dev` off so error responses do not expose
source paths, source excerpts, SQL errors or stack traces. Why binds to loopback by default; only
use `--host` when a reverse proxy on another host must reach it, and review the service's logs and
network access. The built-in request and allocation limits are not a substitute for a reverse proxy,
authentication, authorization, rate limiting or a hardened multi-tenant runtime.

See the README's [Security section](README.md#security) for the short operational checklist.
