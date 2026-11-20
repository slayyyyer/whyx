#!/usr/bin/env node
// The README and the project site restate the install path and the language reference. This keeps
// the copies honest: it fails when an old path or coordinate survives, or a documented feature is
// missing from one of the two places that must describe it.
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const docsDir = path.join(root, "docs");
const pages = (await readdir(docsDir)).filter((name) => name.endsWith(".html")).sort();
const files = ["README.md", ...pages.map((name) => `docs/${name}`)];

const failures = [];
const texts = new Map();
for (const file of files) {
    try {
        texts.set(file, await readFile(path.join(root, file), "utf8"));
    } catch (error) {
        failures.push(`${file}: cannot read (${error.message})`);
    }
}

const forbidden = [
    [/mysite\//, "old site folder 'mysite/'"],
    [/why:why-lang/, "old runtime coordinate 'why:why-lang'"],
    [/id\("why"\)/, 'old plugin id id("why")'],
];

for (const [file, text] of texts) {
    for (const [pattern, reason] of forbidden) {
        if (pattern.test(text)) failures.push(`${file}: ${reason}`);
    }
}

function requireIn(file, needles) {
    const text = texts.get(file);
    if (text === undefined) return;
    for (const needle of needles) {
        if (!text.includes(needle)) failures.push(`${file}: missing '${needle}'`);
    }
}

requireIn("README.md", [
    "io.github.slayyyyer.why",
    "mavenCentral()",
    "why new",
    "csrf.token",
    "response.json",
    "validate(",
]);
requireIn("docs/install.html", [
    "io.github.slayyyyer.why",
    "mavenCentral()",
    "why new",
]);
requireIn("docs/usage.html", ["csrf.token", "response.json", "validate("]);

if (failures.length > 0) {
    console.error("docs are out of sync:");
    for (const failure of failures) console.error(`  ${failure}`);
    process.exit(1);
}
console.log(`docs check: ${files.length} files agree`);
