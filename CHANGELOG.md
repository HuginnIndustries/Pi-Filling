# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is pre-1.0
and developed on `main`; until the first tagged release, "Unreleased" is the
live state.

## [Unreleased]

### Production-readiness pass

A multi-dimension audit (correctness, security, protocol, testing, CI/build,
dependencies, docs) drove the following.

#### Changed — dependencies (blocker)

- **Migrated the agent stack off the deprecated `@mariozechner/*` npm namespace
  to its maintained successor `@earendil-works/*`** (`pi-agent-core`, `pi-ai`,
  `pi-coding-agent`), pinned to exact `0.78.0`. The old namespace was frozen at
  `0.73.1` and formally deprecated. Verified: hermetic wrapper suite green and
  the host-Alpine spike's Q1/Q2/Q3 pass on the new packages. Applies to both
  `node-wrapper/` and `spike-host-alpine/`. (The "0 vulnerabilities" claim
  originally recorded here no longer holds — see the public-readiness pass
  below and `SECURITY.md`.)
- Switched dependency ranges from caret (`^0.73.0`) to exact pins; added
  `node-wrapper/.npmrc` (`engine-strict=true`) and a startup Node-version guard.

#### Added — wrapper hardening (`node-wrapper/src/wrapper.mjs`)

- Flush stdout before exit so a backpressured pipe can't truncate the final
  `agent_end`; honor stdout backpressure in the event listener.
- Last-resort `uncaughtException` / `unhandledRejection` handlers that emit a
  `wrapper_error` and exit **4** (the previously-unreachable documented code).
- Robust request parsing: a bare `null`/array no longer crashes dispatch; a valid
  id with a non-string method gets a `bad_request` response instead of hanging.
- Caught `parseArgs` and `--system-prompt` read failures (clean exit 1, not an
  uncaught stack trace); defensive JSON serialization and an EPIPE guard.
- Surface a run that ends in a provider/auth error as a `wrapper_error`.

#### Security

- The API key is captured into a closure and then **scrubbed from
  `process.env`** (`ANTHROPIC_API_KEY`/`ANTHROPIC_OAUTH_TOKEN`) so it can't leak
  to the agent's `bash`-tool children or pi-ai's env auth fallback.
- `memory.md` is now folded into the system prompt as **untrusted reference
  data** with closing-delimiter-breakout neutralization (it is git-synced across
  devices — prompt-injection surface).
- `SECURITY.md` documents the current key-handling posture and that proot is a
  compatibility layer, not a security sandbox.

#### Added — tests & CI

- Shared promise-based test harness (`test/harness.mjs`) replacing busy-poll RPC
  loops; new `test/hardening.mjs` (exit-code contract + malformed-input
  robustness); event-ordering and stdin-close-mid-run coverage in integration.
  Hermetic suite grew from 8 to **23** tests.
- GitHub Actions CI (`.github/workflows/ci.yml`): lint + format + hermetic tests
  on Node 22, `npm audit`, the Alpine/musl Docker build, and `shellcheck`.
- Dependabot (`.github/dependabot.yml`), ESLint + Prettier configs, root
  `.editorconfig`, and `package.json` metadata (license, repository, version).
- Dockerfiles now use `npm ci --omit=dev --ignore-scripts` for reproducible,
  integrity-checked installs.

#### Added — Android app scaffold (Layer 1, Stage 1.2c)

- New `android/app/` Gradle/Compose project porting Kai's patterns: `DaemonService`
  foreground service, `LinuxSandboxManager` + `ProotExecutor` + `RootfsDownloader`
  (Alpine 3.21.3), AndroidKeyStore-backed `SecureKeyStore`, and a `WrapperClient`
  speaking the Layer 3 stdio JSONL protocol. **Scaffold only — not yet built on a
  device.** See `android/README.md` and `android/KAI_PATTERNS.md`.

### Public-readiness pass

Consolidating the outstanding branches and closing the gaps that would have
shipped with a public repo.

#### Changed — branches

- Opened **`main`** carrying all previously unmerged work: the
  production-readiness branch (CI, lint/format tooling, wrapper hardening,
  Android scaffold) is now contained in it, as is the original spike branch.
- Repointed `README.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `V1_SPEC.md` and
  `spike-host-alpine/RUNBOOK.md` from `claude/spike-pi-agent-android-NugAe` to
  `main`, and dropped the `git checkout` steps that only existed because the
  default branch was not the working branch. `SPIKE_NOTES.md` keeps the branch
  name as a record of where the spike ran.

#### Fixed

- `RootfsDownloader.kt` held two **raw NUL bytes** inside Kotlin char literals
  (the tar entry-type check and the header-string trim). Git classified the file
  as binary, so it produced no reviewable diffs. Replaced with `\u0000` escapes;
  no file in the repo is binary-flagged now.

#### Changed — dependencies

- Patch-bumped the three `@earendil-works` packages `0.78.0` -> `0.78.1` in
  lockstep, dropping production advisories from 8 (2 low, 6 high) to 5
  (1 moderate, 4 high). Suite stays green at 23/23.

#### Security

- **Documented the residual dependency advisories** rather than hiding them.
  `pi-coding-agent` publishes an `npm-shrinkwrap.json`, which npm treats as
  authoritative for that subtree, so the vulnerable transitives it pins
  (`undici`, `ws`, `protobufjs`, `brace-expansion`) are unreachable by consumer
  `overrides` or lockfile edits — both were tried. `SECURITY.md` now carries a
  per-package reachability analysis (the wrapper only ever selects the
  `anthropic` provider, so the SDKs carrying most of these are installed but
  never invoked) and the retirement condition.
- Replaced CI's `npm audit --audit-level=high` — which could not pass — with
  `node-wrapper/scripts/audit-gate.mjs`. It allowlists exactly the packages
  above, each with a written argument, and still fails on any high outside that
  list, on any critical even when allowlisted, and warns on stale entries.
- Audited all 21 commits of history before publication: no credentials in any
  of the 150 blobs, no sensitive filenames, nothing added-then-deleted.

#### Fixed — CI reported green on broken code

- `spike-host-alpine/` had **no CI coverage at all**, so a dependency bump that
  removed an export its drivers use passed every check while breaking them at
  import time. A Dependabot PR bumping that directory to `0.84.2` did exactly
  that and showed green — `getModel` moves out of `@earendil-works/pi-ai`'s main
  entry point, and `driver-e2e.mjs` and `driver-extras.mjs` both import it.
- Added a `spike-api-contract` CI job. Two of the three drivers need a real API
  key and cannot run in CI, so `scripts/check-api.mjs` asserts their **import
  contract** instead: it reads the named imports each driver takes from an
  `@earendil-works` package and checks the package still exports them. Verified
  it passes at `0.78.0` and fails on the `0.84.2` bump that CI had waved through.
- The job also runs `driver.mjs`, which is hermetic by design (mock `streamFn`,
  no real LLM call), so the Q1/Q2/Q3 spike assertions now run on every push.
- Corrected a stale `@mariozechner/*` reference in `driver.mjs`'s header, left
  over from the namespace migration.

#### Added

- `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1).
- Issue templates for bug reports, reproduction reports and design/scope
  pushback, matching what `CONTRIBUTING.md` names as useful, plus a config that
  routes security reports to private advisories instead of public issues.
- CI and license badges in `README.md`.

### Earlier (pre-audit)

- Stage 1.1: node-wrapper with stdio JSONL RPC + memory.md round-trip.
- Stage 1.2a: vendored Kai's `build-proot.sh`.
- Stage 1.2b: Alpine wrapper Dockerfile + hermetic smoke coverage.
- Stage 0: host-Alpine spike proving the agent stack on musl.
