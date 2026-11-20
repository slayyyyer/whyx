# whyx

An X/Twitter clone built from [Why](https://github.com/slayyyyer/why-lang) pages, on a vendored copy
of the runtime. Feed, threads, profiles, replies, likes, reposts, search, sign-up and sign-in — with
no router, no page build step and no framework: one `.why` file per URL.

Demo accounts are `alice`, `bob`, `carol`, `dave`, `erin` and `frank`, all with the password
`whylang`.

## What is here

- `app/` — the site: one `.why` file per URL, `style.css`, and `migrations/` holding the schema and
  the demo seed. A database is created from the migrations on first boot, so deleting `data.db` gets
  the demo data back.
- `why-lang/` — the vendored runtime. Its changes are server-side sessions with sign-in rotation,
  PBKDF2 password hashing and string indexing; the rest is upstream.
- `Dockerfile` — two stages: the Gradle wrapper builds the shaded CLI jar, then a JRE image runs
  the site.
- `.github/workflows/image.yml` — builds and pushes `ghcr.io/slayyyyer/whyx:latest` on every push to
  `main`.

## Run it locally

Needs JDK 21; the Gradle wrapper fetches the build tool.

```bash
(cd why-lang && ./gradlew shadowJar)
java -jar why-lang/build/libs/why.jar app --port 3000 --dev
```

Then open http://localhost:3000. `--dev` shows source and SQL in 500 pages; drop it to see the plain
error page.

## Checks

```bash
(cd why-lang && ./gradlew test)
java -jar why-lang/build/libs/why.jar check app
```

`test` runs the runtime's suite, including `WhyxAppTest`, which drives the real pages through
`why.testing.TestServer` (sign-in rotation, unauthenticated posts, deleting someone else's post, the
home feed's follow graph). `check` compiles every page and prints the page map without serving
anything.

## Run the image

```bash
docker build --platform linux/amd64 -t whyx .
docker run --rm -p 8080:10000 -e PORT=10000 whyx
```

The container binds `0.0.0.0` on `$PORT`, keeps its database in `/data`, answers `/health` with `ok`
and marks cookies `Secure`, so browse it at `http://localhost` rather than a LAN address.

## Deploy on Render

Every push to `main` publishes `ghcr.io/slayyyyer/whyx:latest`. Then in the Render dashboard:

1. **New → Web Service → Existing Image**, image `ghcr.io/slayyyyer/whyx:latest`.
2. Instance Type **Free**; leave **Docker Command** empty.
3. **Health Check Path** `/` — the path the live service is configured with. The runtime also
   answers `/health` with `ok`, so it is worth retrying once a deploy is healthy, but `/` is the
   one that has been exercised.
4. Create the service.

Free instances have an ephemeral filesystem: `/data/data.db` is rebuilt from the migrations on every
deploy, restart and wake, so the demo accounts reappear and posts do not survive. Image-backed
services do not auto-deploy — after a push, wait for the `image` workflow, then **Manual Deploy →
Deploy latest image**.

Render can also build this repository directly: **New → Web Service → Git repository**, Language
**Docker**, Dockerfile path `Dockerfile`.

### Keepalive

A free instance sleeps after 15 idle minutes, and the first visit after that waits about a minute.
`.github/workflows/keepalive.yml` curls the site every 10 minutes so the demo stays awake. It reads
the address from the `SITE_URL` repository variable (Settings → Secrets and variables → Actions →
Variables, e.g. `https://whyx.onrender.com/`); with the variable unset the job skips and prints a
reminder instead of failing.

Two things to know: GitHub's scheduler is best effort and can fire late by several minutes, so an
external monitor at a fixed 5-minute interval is more punctual if the demo keeps falling asleep; and
keeping one free service warm for a month uses about 720 of the 750 monthly instance hours. GitHub
also disables scheduled workflows after 60 days without repository activity.

## TODO

- [ ] Re-test the Render health check on `/health` once the service is up, and put `/health` back in
  the dashboard if it passes.
