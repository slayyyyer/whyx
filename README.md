# whyx

An X/Twitter clone built from [Why](https://github.com/slayyyyer/why-lang) pages. Feed, threads,
profiles, replies, likes, reposts, search, sign-up and sign-in — with no router, no page build step
and no framework: one `.why` file per URL.

Demo accounts are `alice`, `bob`, `carol`, `dave`, `erin` and `frank`, all with the password
`whylang`.

## What is here

- `app/` — the site: one `.why` file per URL, `style.css`, and `migrations/` holding the schema and
  the demo seed. A database is created from the migrations on first boot, so deleting `data.db` gets
  the demo data back.
- `src/main/java/whyx/Main.java` — the launcher. The Why plugin packages `app/` into the jar under
  `META-INF/why-site`, so the launcher serves it through `Site.fromClasspath()` instead of a folder.
- `src/test/java/whyx/WhyxAppTest.java` — the app's tests, driving the real pages through
  `why.testing.TestServer`.
- `build.gradle.kts` — applies the published `io.github.slayyyyer.why` plugin (version `0.2.0`) and
  the Shadow plugin, which produce one self-contained `build/libs/whyx.jar`.
- `Dockerfile` — two stages: the Gradle wrapper builds that jar, then a JRE image runs it.
- `.github/workflows/image.yml` — builds and pushes `ghcr.io/slayyyyer/whyx:latest` on every push to
  `main`.

The language itself is a dependency, not a copy: the plugin resolves
`io.github.slayyyyer:why-lang:0.2.0` from Maven Central. Upgrading is one version bump in
`build.gradle.kts`.

## Run it locally

Needs JDK 21; the Gradle wrapper fetches the build tool.

```bash
./gradlew whyRun --args="--db data.db --dev"
```

Or build the jar and run it:

```bash
./gradlew shadowJar
java -jar build/libs/whyx.jar --port 3000 --dev --db data.db
```

Then open http://localhost:3000. `--dev` shows source and SQL in 500 pages; drop it to see the plain
error page. `--db` keeps the database across runs — without it the site uses a temporary file.

## Checks

```bash
./gradlew whyCheck   # compiles every page, prints the page map (also runs as part of build)
./gradlew test       # WhyxAppTest, plus the runtime's own coverage of the pages
./gradlew build      # both, then jars the site
```

`WhyxAppTest` drives the real pages through `why.testing.TestServer`: sign-in rotation, unauthenticated
posts, deleting someone else's post, the home feed's follow graph, the sign-in and sign-up limits.

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
the address from the `SITE_URL` repository variable, defaulting to `https://whyx-sxzp.onrender.com/` — set the variable
(Settings → Secrets and variables → Actions → Variables) to point it at another address.

Two things to know: GitHub's scheduler is best effort and can fire late by several minutes, so an
external monitor at a fixed 5-minute interval is more punctual if the demo keeps falling asleep; and
keeping one free service warm for a month uses about 720 of the 750 monthly instance hours. GitHub
also disables scheduled workflows after 60 days without repository activity.

## TODO

- [ ] Re-test the Render health check on `/health` once the service is up, and put `/health` back in
  the dashboard if it passes.
