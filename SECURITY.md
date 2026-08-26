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

## Known dependency advisories

`npm audit --omit=dev` reports advisories against this tree that we cannot
patch downstream, and we would rather state that plainly than hide it behind a
loosened CI threshold.

**Why they cannot be patched here.** `@earendil-works/pi-coding-agent` publishes
an `npm-shrinkwrap.json`. npm treats a dependency's shrinkwrap as authoritative
for that subtree, so the versions pinned inside it are not reachable by consumer
`overrides` or by editing our own lockfile. Both were attempted; npm records the
override edge while still installing the pinned version, and the result varies
by install path. The advisories retire when the agent stack moves past 0.78.x,
which is a migration rather than a version bump: 0.84.x moves `getModel` to
`getBuiltinModel` in `@earendil-works/pi-ai/providers/all` and requires an
explicit `streamFn` when constructing an `Agent`.

**What is accepted, and why each is not reachable in this wrapper.** The wrapper
only ever selects the `anthropic` provider (`getModel("anthropic", ...)`), so the
provider SDKs that carry most of these advisories are installed but never
invoked.

| Package | Severity | Why it is not reachable here |
|---|---|---|
| `undici` | high | HTTP client for the provider SDKs. The advisories are in cookie, cache, SOCKS5-proxy and WebSocket-client paths; the wrapper makes plain Anthropic API calls and drives none of them. |
| `ws` | high | Pulled in by the `@google/genai`, `@mistralai` and `openai` SDKs. No WebSocket is ever opened, because those providers are never selected. |
| `protobufjs` | high | Reached only through the AWS/Bedrock provider path. The wrapper does not use Bedrock, so no `.proto` is parsed. |
| `brace-expansion` | high | Glob-pattern DoS. Patterns originate in the agent's own tool calls against a repo the operator deliberately pointed it at — a trust boundary this document already describes as operator-owned. |
| `@earendil-works/pi-coding-agent` | moderate | Loads project-local extensions without prompting. Same operator-owned trust boundary as the `bash` tool above: treat any repo you point the agent at as code you are choosing to run. |

**How this is enforced.** CI does not run a bare `npm audit --audit-level=high`.
It runs [`node-wrapper/scripts/audit-gate.mjs`](./node-wrapper/scripts/audit-gate.mjs),
which allowlists exactly the packages above and still fails the build on any
high advisory outside that list, on any critical advisory even for an
allowlisted package, and warns when an allowlist entry has gone stale so the
list gets pruned rather than accumulating. Adding an entry requires writing the
reachability argument next to it and updating this table.

## Cryptographic material in the repo

There is none and there should be none. The repo's gitignore excludes
`spike-host-alpine/certs/*.crt` and any `.anthropic-key` style files. If you
spot a key, secret, or private cert in a commit, treat it as a security
report per the section above.
