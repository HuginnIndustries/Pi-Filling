# Security policy

Pi-Filling is in pre-1.0 development. No releases yet. The notes below are
provisional and will firm up as the project matures.

## Reporting a vulnerability

If you find a security issue, please **do not** open a public GitHub issue.

Until we publish a contact address, please use GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository.

We aim to acknowledge reports within 7 days. Coordinated-disclosure timelines
will be agreed case-by-case until a formal policy is published.

## Scope

Reports relevant to this repository:

- Code we ship from this repo (the eventual Android app, any Node wrappers,
  the spike drivers).
- Configuration we recommend in our docs (e.g., the Dockerfile, the API-key
  handling patterns in `RUNBOOK.md`).
- Build pipelines and release artifacts (once they exist).

Out of scope (please report upstream):

- `@earendil-works/pi-agent-core`, `@earendil-works/pi-ai`,
  `@earendil-works/pi-coding-agent` (the maintained successor to the deprecated
  `@mariozechner/*` packages) — report to the pi-mono upstream.
- Anthropic SDK / API — report at https://www.anthropic.com/security.
- Kai Android sandbox — report at https://github.com/TheAmericanMaker/Kai.
- The Anthropic models themselves — report via Anthropic's responsible
  disclosure channels.

## Current key handling and known limitations

These are the security-relevant realities of the code as it ships today (pre-1.0),
stated plainly so operators aren't surprised:

- **API key delivery.** Layer 1 passes the Anthropic key to the wrapper via the
  `ANTHROPIC_API_KEY` environment variable at process spawn. The wrapper captures
  it into a closure on startup and then **deletes it from `process.env`** (along
  with `ANTHROPIC_OAUTH_TOKEN`), so it does not propagate to the agent's `bash`
  tool children or to pi-ai's env-var auth fallback. The key is never written to
  disk by the wrapper. On Android the key is stored encrypted at rest via a
  hardware-bound AndroidKeyStore AES-256-GCM key (`SecureKeyStore`). The
  spec's longer-term goal is a non-env key handshake (stdin/socket); the env path
  with immediate scrub is the current state.
- **The `bash` tool runs arbitrary shell.** The agent's `bash` tool executes
  model-chosen commands with no allowlist at the wrapper layer. **proot is an
  isolation/compat layer, not a security sandbox** — it does not contain a
  determined attacker. Containment for v1 relies on Android app-private storage
  and the OS process boundary, not on confining the agent. Treat any repo you
  point the agent at, and any `memory.md` it loads, as code you are choosing to
  run.
- **`memory.md` is untrusted input.** It is git-synced across devices, so the
  wrapper folds it into the system prompt framed as untrusted reference data
  (not instructions) with delimiter-breakout neutralization. This reduces, but
  does not eliminate, prompt-injection risk from a compromised synced file.

## Dependency advisories

`npm audit --omit=dev` currently reports **zero advisories** on production
dependencies, and CI enforces that.

This was not always true, and the history is worth recording because the failure
mode is structural. The `0.78.x` line of `@earendil-works/pi-coding-agent`
shipped an `npm-shrinkwrap.json`, and npm treats a dependency's shrinkwrap as
authoritative for that subtree — so the vulnerable transitives it pinned
(`undici`, `ws`, `protobufjs`, `brace-expansion`) could not be reached by
consumer `overrides` or by editing our own lockfile. Both were attempted; npm
records the override edge and installs the pinned version anyway, inconsistently
across install paths. The only real fix was upgrading the agent stack to
`0.84.x`, which was a breaking API migration rather than a version bump. That
migration has since been done.

**How this is enforced.** CI runs
[`node-wrapper/scripts/audit-gate.mjs`](./node-wrapper/scripts/audit-gate.mjs)
rather than a bare `npm audit --audit-level=high`. Today the two behave
identically, because the gate's allowlist is empty. The gate stays because an
upstream can pin a vulnerable transitive out of our reach again, and when that
happens the response should be a documented, per-package exception — with a
written argument for why it is not reachable in this codebase, and a row in this
document — rather than lowering the threshold for every dependency at once. The
gate also fails on a critical advisory even if that package is allowlisted, and
warns when an allowlist entry has gone stale so entries get pruned.

## Cryptographic material in the repo

There is none and there should be none. The repo's gitignore excludes
`spike-host-alpine/certs/*.crt` and any `.anthropic-key` style files. If you
spot a key, secret, or private cert in a commit, treat it as a security
report per the section above.
