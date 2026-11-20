# Deploying Why

Why binds loopback by default and runs pages as trusted code. Terminate TLS and authenticate in
front of it; see [SECURITY.md](../SECURITY.md).

## Container

```bash
docker build -t why .
docker run --rm -p 8080:8080 -v why-data:/data why
```

The image builds the shaded CLI jar, runs as an unprivileged user, and keeps the database in
`/data`. To ship your own site, replace `/app/site` with a mounted folder:

```bash
docker run --rm -p 8080:8080 -v "$PWD/site:/app/site:ro" -v why-data:/data why
```

`/health` answers `200 ok` without parsing a page, so a load balancer can probe it. Every response
carries an `X-Request-Id` that also appears at the start of the request log line.

## systemd

```bash
sudo install -d /opt/why /srv/why /var/lib/why
sudo cp -r bin build/libs/why.jar /opt/why/   # after `./gradlew shadowJar`
sudo cp -r site/. /srv/why/site/
sudo useradd --system --home /srv/why --shell /usr/sbin/nologin why
sudo chown -R why:why /srv/why /var/lib/why
sudo cp deploy/why.service /etc/systemd/system/
sudo systemctl enable --now why
```

Edit `Environment=JAVA_HOME` and `ExecStart` for your JDK path. The unit keeps the database in
`/var/lib/why`, blocks writes everywhere else, and gives Why five seconds to drain requests on
`SIGTERM` before systemd stops it.

## Reverse proxy

Caddy, in front of the loopback listener:

```
example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Pages answering forms need `--secure-cookies` (or `WHY_SECURE_COOKIES=1` in the unit) so the session
and CSRF cookies are marked `Secure` behind the proxy. The CLI reads `PORT`, `HOST`, `WHY_DB` and
`WHY_SECURE_COOKIES`, so environment variables can replace those flags. If the proxy terminates TLS
and forwards `X-Forwarded-Proto: https` over the loopback listener, `--trust-proxy` derives `Secure`
from that header instead and makes `request.remote` the last non-loopback `X-Forwarded-For` hop, so
the per-IP login limit sees each client rather than the proxy. Both headers are ignored from any
non-loopback peer.

`seed.sql` is demo data and is never applied to production just because it exists: it only runs
with `--dev` against a database that did not exist before that boot. Keep `--dev` off here.
