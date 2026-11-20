# Notes

## Assumptions made for M0/M1

The specification I was given ends in the middle of the `imports:` rules, so these were my calls:

- **CLI**: `java -jar why.jar [site] [--port N] [--db FILE]`; default port 8080, default database
  `<site>/data.db`.
- **`schema.sql` bootstrap**: if the database file does not exist at startup, `<site>/schema.sql`
  is executed once. This keeps the demo site self-contained without a separate setup step.
- **Imported controllers** contribute their top-level `def`s only; their top-level statements are
  not executed. Imports are transitive. The importing page's own defs win over imported ones, and
  earlier imports win over later ones.
- **Extension-free URLs**: `/about` resolves to `about.why`, in addition to `/about.why`.
  Directories resolve to `index.why`.
- **Undefined variables** read as `nil` instead of raising, which keeps views forgiving.
- **String concatenation**: `+` with a string on either side concatenates rather than raising.
- **Numeric equality**: `1 == 1.0` is true.
- **`not` precedence**: `not a == b` is parsed as `not (a == b)`. Ruby's `!` binds much tighter;
  this PoC prefers the readable form.

## Deliberately left out (candidates for later)

### Page format

- An optional `model:` section for shared queries. A possibility, not a promise; today data access is
  `db.query`/`db.get`/`db.exec` plus imported `def`s.

### Language

- Modifier `if`/`unless` (`x = 1 if ok`), `case/when`, ranges, symbols, string formatting.
- `puts` and any other debugging output from a page.
- Keyword arguments, default parameters, splats, functions as first-class values.
- Classes and modules (explicit non-goal), exceptions (`begin`/`rescue`), `yield`.
- Assignment to `hash.key` or `list[0]`.
- Arity checking: missing arguments become `nil`, extra arguments are ignored.
- `return` inside a block returns from the block, not from the enclosing function.

### Templates

- Whitespace control (`{{~ … ~}}`), `{{#with}}`, helpers, `{{else if}}`, partial arguments,
  inline partials.
- Recursive partials are not detected; a self-referencing partial recurses until the stack ends.
- Dynamic partial names (`{{> (expr)}}`).

### Server and runtime

- Hot reload / parse cache: every request re-reads and reparses the page; there is no cache. Fast
  enough for a PoC, and it makes "edit and refresh" trivially correct.
- Urlencoded POST bodies are parsed into `form`, `redirect(path)` answers 303, and a one-shot
  `flash` global carries messages across a redirect using an in-memory `why_session` cookie. The
  cookie is `HttpOnly` and `SameSite=Lax`. Multipart bodies, uploads, general cookies,
  longer-lived sessions, general custom headers and other statuses are still out; a page can set a Content-Security-Policy with `response.csp(...)`. The awkward parts are recorded in [FRICTION.md](FRICTION.md).
- Custom 404 pages, directory listings, configurable index files.
- A JSON helper (`render json:`). The spec allows Jackson or Gson, but nothing needed JSON yet,
  so the dependency was left out.
- Multiple sites per server, TLS and access control.
- Sandboxing and security hardening (explicit non-goal): pages can run arbitrary SQL and read the
  whole database.

### Database

- Transactions, connection pooling (one SQLite connection per request), migrations beyond the
  first-run `schema.sql`.
- H2 support. The `db` global is hard-wired to SQLite for now, although the driver is the only
  thing that would need to change.

## Open questions

- **Language drift.** Resolved: the Ruby-shaped language is the decision, recorded in
  [docs/decisions/0001-language-direction.md](docs/decisions/0001-language-direction.md). The
  `{ }`-block simplification is rejected rather than deferred.
- **Custom block helpers.** Not supported today. Template blocks are limited to `if`, `unless` and
  `each`; an unknown `{{#name}}` is an error. A user `def` can receive a language block
  (`do |x| ... end`), but template blocks and language blocks are separate concepts. Whether to
  unify them is open.
- **Block-call syntax.** No syntax exists today for calling a block from a user-defined function;
  user functions receive ordinary arguments, and a `do` block can only be attached to a call.
  If custom helpers are added, choose explicit `block.call` or Ruby-style `yield`; the current
  parser and interpreter have neither a block-call expression nor a `yield` keyword.
- **Parse cache wording.** The documentation brief asked for "parses it on request and caches the
  result", but the server currently re-reads and reparses every request; `PageCompiler` has no
  cache. Adding one is runtime behavior and was not done, so the docs say "parses it on request".
