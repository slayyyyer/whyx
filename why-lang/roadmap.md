# Roadmap

Why 0.1.0 is on Maven Central and the docs describe the Gradle path. This is what comes next,
ranked by value rather than effort. Each item says why it is worth doing and what "done" looks
like.

Status: `[ ]` not started, `[~]` in progress, `[x]` done, `[!]` blocked on something outside the
repository.

## Before another release ships

### [x] 1. One source of truth for the version

The version was hardcoded in four places, not the three `RELEASING.md` named: `build.gradle.kts`,
`why-gradle-plugin/build.gradle.kts`, the Maven build, and
`WhyPlugin.RUNTIME = "io.github.slayyyyer:why-lang:0.1.0"`. A 0.2.0 release that follows the
runbook verbatim ships a plugin that silently pulls the 0.1.0 runtime.

Why: cheap to fix, and the failure mode is invisible until a consumer reports a missing fix.

Done when the Gradle builds read one version and the plugin's runtime coordinate is derived rather
than typed. With the Maven build gone, `gradle.properties` is the only copy left.

### [!] 2. Publish the plugin to the Gradle Plugin Portal

Today every install needs the two-repository explanation, including the note that the marker is not
on the Plugin Portal. That is the least idiomatic paragraph in the docs and the first thing a Gradle
user questions.

Why: the default `plugins { id("io.github.slayyyyer.why") version "…" }` resolves with no
`pluginManagement` block, and the plugin gets a Portal listing with version history.

Done when `./gradlew publishPlugins` is configured and documented, and the install page no longer
requires a repository block for the plugin marker.

### [x] 3. Finish the `response` object

`Response` has one method, `csp`, while `FRICTION.md` #1 still says a page cannot set a status or a
header.

Why: `response.status`, `response.header` and `response.json` close the custom-404 gap, make
201/204 possible and give the specification's `render json:` a home. It is the second half of an
abstraction that already exists.

Done when a page can set a status, set a header, and answer JSON without hand-building a string.

### [x] 4. CSRF tokens and a form contract

`FRICTION.md` #10 and #4 are the two things between "demo" and "anyone may deploy a form".

Why: a `csrf.token` rendered into a hidden field and checked on POST makes the write path safe; a
conventional `errors`/`old` pair makes re-rendering a failed form the default instead of something
every page invents.

Done when a POST without a valid token is rejected, the token survives a request without a
database, and the demo guestbook and recipes use it.

### [x] 5. Migrations

`schema.sql` runs only when the database file is missing, so adding a table means deleting
everyone's data (`FRICTION.md` #8).

Why: it is the first thing any real deployment needs on day two.

Done when ordered SQL files in `migrations/` apply once each, tracked in a `schema_migrations`
table, and existing databases upgrade in place.

### [x] 6. SQLite WAL and `busy_timeout`

No pragma is set anywhere. One connection per request with the default rollback journal means two
simultaneous writers fail with `database is locked`, which surfaces as a 500 (`FRICTION.md` #6).

Why: three lines turn a hard failure into a short wait, and let readers proceed during a write.

Done when every connection enables WAL and a busy timeout, with a test that holds a write
transaction open and expects the second writer to wait rather than fail.

## New audiences

### [x] 7. A non-Gradle install path

The hero says "Drop a file. Hit its URL." The documented path says "first create a Gradle project
with two repository blocks."

Why: the largest untapped adoption lever. A scaffolder and a launcher let someone try Why without
meeting Gradle, which is the price of admission for a page language.

Done when `why new mysite` writes a working project and a launcher runs a site from a folder.

### [x] 8. `why test`: a page harness

Write paths are only testable over HTTP (`FRICTION.md` #12), so they sit outside the fast loop.

Why: an in-process client with a temporary database turns a POST page into a JUnit assertion, and
gives the page model a story that Sinatra-plus-ERB tutorials do not have.

Done when a consumer test can boot a site, issue GET/POST and assert status, headers and body in a
few lines.

### [x] 9. Editor support

Every `.why` file is currently grey text in every editor.

Why: for a new language, syntax highlighting is table stakes; it makes screenshots, docs and the
tutorial credible, and it is a weekend of grammar work.

Done when a VS Code extension and a TextMate grammar highlight sections, strings, interpolation,
mustaches and Ruby-shaped keywords.

### [x] 10. Static asset caching and compression

No `ETag`, no `Last-Modified`, no gzip; static files are re-read and re-sent in full.

Why: on a server that re-parses pages per request, `ETag`/`304` and gzip are the cheapest
perceived-speed win available, and they make the 4 MiB asset cap reasonable.

Done when a repeat request for an asset answers 304, and text responses are compressed when the
client asks.

## Paying down what 0.1.0 exposed

### [x] 11. One source for the language reference

The reference is restated in `README.md`, `docs/usage.html`, `docs/why.html`, `docs/recipes.html`
and `docs/install.html`. Changing paths meant editing four of them by hand.

Why: drift is now a certainty. The next language change means five coordinated edits.

Done when the site's reference is generated from one source, or a check fails when the copies
disagree.

### [x] 12. A deploy recipe

`SECURITY.md` tells people to run Why behind a reverse proxy as a service account, but the
repository ships nothing for that, and `server.stop()` on SIGTERM calls `HttpServer.stop(0)`, which
drops in-flight requests.

Why: the runtime's trust model is the caveat, not its operability.

Done when the repository carries a Dockerfile and a systemd unit, SIGTERM drains in-flight requests,
`/health` answers without touching a page, and the request log carries an id.

### [ ] 13. Classpath sites without temp extraction

`Site.fromClasspath()` extracts the packaged site to a temporary directory.

Why: it forces a writable `/tmp` and repeats work on every start. Reading resources directly
removes both and matters for read-only container filesystems.

Done when a packaged site serves with no temporary directory.

### [x] 14. Published Javadoc

The Javadoc jar exists, but the embeddable API is documented only in prose.

Why: `Site.fromClasspath` and `WhyServer` are what library consumers call; they deserve a
reference.

Done when the Pages build publishes Javadoc under `docs/api`.

## The language's next headline

### [~] 15. Custom template helpers, block-call syntax, and the drift decision

`NOTES.md` records that `{{#if}}`, `{{#unless}}` and `{{#each}}` are the only blocks, that a `def`
can receive a language block but a template cannot call it, and that there is no `yield` or
`block.call`. The "language drift" question is still open.

Why: unifying template blocks with language blocks is what makes "views with teeth" more than a
slogan. The drift decision deserves a one-page record because it is the only shipped choice logged
as accidental.

Done when a page-defined helper can render a template block, and the drift decision is written
down.

## Where this pass left off

- Item 2 is configured (`com.gradle.plugin-publish` 1.3.0, website and VCS URLs) but cannot be
  published from here: `publishPlugins` needs `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.
- Item 7 ships `why new` and `bin/why`/`bin/why.cmd`; a published standalone CLI jar is still the
  missing piece, so the launcher needs `./gradlew shadowJar` or a `WHY_JAR` path.
- Item 11 took the second half of its "done when": `tools/check-docs.mjs` fails when the README and
  the site disagree. Generation from one source is still open.
- Item 13 is untouched. `Site` is path-based throughout (`PageLoader`, `PageCompiler`, `WhyServer`),
  so serving from a jar without extraction is an architectural change, not a patch.
- Item 15 records the language decision in `docs/decisions/0001-language-direction.md`. Custom
  template blocks are still deferred; they need a first-class block value and a `yield`-style call.

## Deliberately not yet

TLS, multiple sites per server, H2, a REPL and a formatter all look plausible and all cost more
than they return at 0.1.0. TLS and multi-site belong in a reverse proxy; H2 is a driver swap nobody
has asked for; a REPL and a formatter only pay off once the grammar is settled, which is what item
15 decides.
