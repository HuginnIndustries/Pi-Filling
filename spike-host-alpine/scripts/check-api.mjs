#!/usr/bin/env node
// API-contract check for the spike drivers.
//
// Two of the three drivers (driver-extras.mjs, driver-e2e.mjs) make real
// Anthropic calls, so CI cannot run them without a key. That left this
// directory with no CI coverage at all — and a dependency bump that removes an
// export the drivers rely on would report green while breaking every driver at
// import time. That is exactly what a 0.84.x bump does: `getModel` moved out of
// @earendil-works/pi-ai's main entry point.
//
// So instead of executing those drivers, assert their import contract: read the
// named imports each driver takes from an @earendil-works package, and check
// the package actually exports them. Cheap, needs no API key, and fails on the
// break that matters.

import { readdirSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const driverDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const drivers = readdirSync(driverDir).filter((f) => f.endsWith(".mjs"));

// Named imports only: `import { a, b as c } from "@earendil-works/pkg"`.
// Default and namespace imports carry no per-symbol contract to check.
const NAMED_IMPORT = /import\s*\{([^}]*)\}\s*from\s*["'](@earendil-works\/[^"']+)["']/gs;

let checked = 0;
const failures = [];

for (const driver of drivers.sort()) {
  const source = await readFile(join(driverDir, driver), "utf8");

  for (const match of source.matchAll(NAMED_IMPORT)) {
    const [, clause, specifier] = match;
    const names = clause
      .split(",")
      .map((part) => part.trim())
      .filter(Boolean)
      // `a as b` — the contract is on the exported name, not the local alias.
      .map((part) => part.split(/\s+as\s+/)[0].trim());

    let mod;
    try {
      mod = await import(specifier);
    } catch (err) {
      failures.push(`${driver}: cannot import ${specifier} — ${err.message}`);
      continue;
    }

    for (const name of names) {
      checked += 1;
      if (!(name in mod)) {
        failures.push(`${driver}: ${specifier} does not export "${name}"`);
      }
    }
  }
}

if (failures.length > 0) {
  console.error(`check-api: FAIL — ${failures.length} broken import(s):`);
  for (const failure of failures) console.error(`  ${failure}`);
  console.error("");
  console.error("A dependency bump likely moved or removed these exports.");
  console.error("Update the drivers to the new API before taking the bump.");
  process.exit(1);
}

console.log(`check-api: PASS — ${checked} named imports across ${drivers.length} drivers all resolve.`);
