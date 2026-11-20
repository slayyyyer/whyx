# 1. Keep the Ruby-shaped language

Date: 2026-11-19
Status: accepted

## Context

`NOTES.md` records a real ambiguity: the shipped language is Ruby-shaped — `do |x| ... end` blocks,
`unless`, `elsif`, `while`/`break`, `&&`/`||` — because the parser, interpreter, tests and examples
were all built that way. An earlier simplification (`{ }` blocks with an implicit `it`, no
`unless`/`elsif`/`while`) was never carried through, and no rationale for rejecting it was written
down. The docs and the demo lean on the Ruby forms, including `unless done` and `each do |number|`.

## Decision

Keep the Ruby-shaped language. The simplification is rejected rather than deferred.

Reasons:

- The forms are already what the tests, the demo site, the docs and the editor grammar describe.
  Changing them is a language change, not a cleanup.
- The page format, not the grammar, is the idea Why is making. Ruby's shape is familiar enough that
  a reader can guess what `unless` and `do |x|` mean without a tutorial.
- The two shapes have different template stories. `{{#each posts}}` needs an `it` binding, while
  language blocks name their parameter; keeping one shape avoids two sets of rules for what a block
  is.

## Consequences

- `NOTES.md` no longer lists language drift as an open question; it points here.
- Custom template helpers stay deferred. Unifying a template block with a language block requires a
  first-class block value and a way to call it, most likely `yield` inside a `def`. That is a
  deliberate language addition for a later version, not a bug fix.
- A `{ }`-block dialect is out of scope. Anyone who wants it can treat this document as the reason
  it is not on the roadmap.
