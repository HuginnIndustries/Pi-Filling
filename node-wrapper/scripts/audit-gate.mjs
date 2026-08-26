#!/usr/bin/env node
// Production dependency audit gate.
//
// A bare `npm audit --audit-level=high` has already proven too blunt for this
// tree once. @earendil-works/pi-coding-agent 0.78.x shipped an
// npm-shrinkwrap.json, and npm treats a dependency's shrinkwrap as
// authoritative for that subtree — so the vulnerable transitives it pinned
// were not reachable by consumer `overrides` or by editing our own lockfile.
// Both were tried. CI could not go green without either ignoring every high
// advisory or upgrading, and the upgrade was a breaking API migration.
//
// The 0.84.x migration resolved that instance, and the allowlist below is now
// empty. The gate stays because the situation is structural rather than
// hypothetical: an upstream can pin a vulnerable transitive out of our reach
// again, and when it does the choice should be a documented, per-package
// exception rather than lowering the threshold for everything.
//
// The gate fails when:
//   - a high or critical advisory appears for a package not on the allowlist
//   - any critical advisory appears, allowlisted or not
// It warns (without failing) when an allowlisted package no longer reports an
// advisory, so entries get pruned instead of going stale.

import { spawnSync } from "node:child_process";

// Intentionally empty: production deps currently report zero advisories.
//
// Add an entry only for an advisory that genuinely cannot be fixed downstream
// (an upstream shrinkwrap pin, a transitive with no patched release). Each one
// needs a `summary` for the gate's output, a `reason` arguing why it is not
// reachable in this codebase, and a matching row in SECURITY.md. If a fix
// exists, take the fix instead.
const ALLOWLIST = {};

const BLOCKING = new Set(["high", "critical"]);

const result = spawnSync("npm", ["audit", "--omit=dev", "--json"], {
  encoding: "utf8",
  // npm audit exits nonzero whenever vulnerabilities exist. That is the normal
  // case here, so the exit code carries no signal — only the JSON does.
  maxBuffer: 32 * 1024 * 1024,
});

if (!result.stdout) {
  console.error("audit-gate: npm audit produced no output");
  if (result.stderr) console.error(result.stderr.trim());
  process.exit(2);
}

let report;
try {
  report = JSON.parse(result.stdout);
} catch (err) {
  console.error(`audit-gate: could not parse npm audit JSON: ${err.message}`);
  process.exit(2);
}

const found = Object.entries(report.vulnerabilities ?? {});
const violations = [];
const accepted = [];

for (const [name, vuln] of found) {
  const severity = vuln.severity;
  const allowed = ALLOWLIST[name];

  if (severity === "critical") {
    // A critical is never waved through, even for an allowlisted package: the
    // reachability arguments below were written against the advisories that
    // existed at the time, and a critical warrants re-deriving them.
    violations.push({
      name,
      severity,
      why: allowed ? "critical overrides the allowlist" : "not allowlisted",
    });
    continue;
  }

  if (!BLOCKING.has(severity)) {
    if (allowed) accepted.push({ name, severity });
    continue;
  }

  if (allowed) accepted.push({ name, severity });
  else violations.push({ name, severity, why: "not allowlisted" });
}

const stale = Object.keys(ALLOWLIST).filter((name) => !found.some(([reported]) => reported === name));

const counts = report.metadata?.vulnerabilities ?? {};
console.log(
  `audit-gate: ${counts.total ?? 0} advisories on production deps ` +
    `(critical ${counts.critical ?? 0}, high ${counts.high ?? 0}, moderate ${counts.moderate ?? 0}, low ${counts.low ?? 0})`,
);

for (const { name, severity } of accepted) {
  console.log(`  accepted  ${severity.padEnd(8)} ${name} — ${ALLOWLIST[name].summary}`);
}

for (const name of stale) {
  console.log(`  stale     allowlist entry for ${name} reports nothing — prune it from audit-gate.mjs`);
}

if (violations.length > 0) {
  console.error("");
  console.error("audit-gate: FAIL — advisories outside the documented allowlist:");
  for (const { name, severity, why } of violations) {
    console.error(`  ${severity.padEnd(8)} ${name} (${why})`);
  }
  console.error("");
  console.error("Fix the dependency, or add an entry to ALLOWLIST in scripts/audit-gate.mjs");
  console.error("with a written reachability argument and update SECURITY.md to match.");
  process.exit(1);
}

console.log("audit-gate: PASS — nothing outside the documented allowlist.");
