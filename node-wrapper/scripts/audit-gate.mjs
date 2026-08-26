#!/usr/bin/env node
// Production dependency audit gate.
//
// Replaces a bare `npm audit --audit-level=high`, which cannot pass on this
// tree for a reason we don't control: @earendil-works/pi-coding-agent publishes
// an npm-shrinkwrap.json, and npm treats a dependency's shrinkwrap as
// authoritative for that subtree. The pinned transitive versions there are not
// reachable by consumer `overrides` or by editing our own lockfile — both were
// tried, and npm applies them inconsistently across install paths (it records
// the override edge while still installing the pinned version).
//
// So the choice is not "fix or ignore", it's "ignore everything at high" or
// "ignore exactly the advisories upstream pins, and still fail on anything
// new". This script does the latter: an allowlist keyed by package name, where
// every entry carries why it is accepted and what would retire it.
//
// The gate fails when:
//   - a high or critical advisory appears for a package not on the allowlist
//   - any critical advisory appears, allowlisted or not
// It warns (without failing) when an allowlisted package no longer reports an
// advisory, so the list gets pruned instead of quietly growing stale.

import { spawnSync } from "node:child_process";

// Every entry here is pinned by pi-coding-agent's npm-shrinkwrap.json. The
// retirement condition for all of them is the same: upgrading the agent stack
// past 0.78.x. That upgrade is real work, not a version bump — 0.84.x moves
// `getModel` to `getBuiltinModel` in @earendil-works/pi-ai/providers/all and
// requires an explicit `streamFn` when constructing an Agent. Tracked as its
// own task; see SECURITY.md for the reachability analysis.
const ALLOWLIST = {
  undici: {
    summary: "shrinkwrap-pinned 8.3.0; cookie/cache/proxy paths the wrapper never drives",
    reason:
      "Pinned to 8.3.0 by pi-coding-agent's shrinkwrap. HTTP client used by the provider SDKs. " +
      "The advisories are cookie/cache/proxy handling in code paths the wrapper does not drive: " +
      "it makes plain Anthropic API calls and never uses undici's SOCKS5 ProxyAgent or WebSocket client.",
  },
  ws: {
    summary: "shrinkwrap-pinned 8.20.1; only reached via non-Anthropic provider SDKs",
    reason:
      "Pinned to 8.20.1 by pi-coding-agent's shrinkwrap, pulled in by the @google/genai, " +
      "@mistralai and openai provider SDKs. This wrapper only ever selects the anthropic " +
      "provider, so no WebSocket is opened and the memory-exhaustion DoS is unreachable.",
  },
  protobufjs: {
    summary: "shrinkwrap-pinned; only reached via the unused Bedrock provider path",
    reason:
      "Pinned by pi-coding-agent's shrinkwrap, reached only through the AWS/Bedrock provider " +
      "SDK path. The wrapper does not use Bedrock, so no .proto is ever parsed.",
  },
  "brace-expansion": {
    summary: "shrinkwrap-pinned; glob DoS inside an operator-chosen repo",
    reason:
      "Pinned by pi-coding-agent's shrinkwrap. DoS via adversarial glob patterns. Patterns come " +
      "from the agent's own tool calls against a repo the operator chose to point it at, which " +
      "is already a trust boundary SECURITY.md documents as operator-owned.",
  },
  "@earendil-works/pi-coding-agent": {
    summary: "moderate; loads project-local extensions without prompting",
    reason:
      "Moderate advisory (loads project-local extensions without prompting) against the pinned " +
      "0.78.x line. Listed so the gate reports it rather than hiding it below the high threshold; " +
      "it does not by itself fail the gate at moderate.",
  },
};

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
