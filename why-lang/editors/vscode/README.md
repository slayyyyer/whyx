# Why for VS Code

Syntax highlighting for `.why` pages: section labels, Ruby-shaped keywords, double-quoted
interpolation, `?`/`!` method names, mustaches and mustache comments.

## Install

Copy this folder into your extensions directory and restart the editor:

- macOS / Linux: `~/.vscode/extensions/why-lang`
- Windows: `%USERPROFILE%\.vscode\extensions\why-lang`

Or package and install it:

```bash
npx @vscode/vsce package
code --install-extension why-lang-0.1.0.vsix
```

The grammar is a TextMate grammar (`syntaxes/why.tmLanguage.json`), so other editors that read
TextMate bundles, including Sublime Text and TextMate itself, can use the same file.
