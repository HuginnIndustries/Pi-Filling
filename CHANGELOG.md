# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is pre-1.0
and developed on the `claude/spike-pi-agent-android-NugAe` working branch; until
a `main` opens, "Unreleased" is the live state.

## [Unreleased]

### Production-readiness pass

A multi-dimension audit (correctness, security, protocol, testing, CI/build,
dependencies, docs) drove the following.

#### Changed — dependencies (blocker)

- **Migrated the agent stack off the deprecated `@mariozechner/*` npm namespace
  to its maintained successor `@earendil-works/*`** (`pi-agent-core`, `pi-ai`,
  `pi-coding-agent`), pinned to exact `0.78.0`. The old namespace was frozen at
  `0.73.1` and formally deprecated. Verified: hermetic wrapper suite green and
  the host-Alpine spike's Q1/Q2/Q3 pass on the new packages; `npm audit` reports
  0 vulnerabilities. Applies to both `node-wrapper/` and `spike-host-alpine/`.
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

### Earlier (pre-audit)

- Stage 1.1: node-wrapper with stdio JSONL RPC + memory.md round-trip.
- Stage 1.2a: vendored Kai's `build-proot.sh`.
- Stage 1.2b: Alpine wrapper Dockerfile + hermetic smoke coverage.
- Stage 0: host-Alpine spike proving the agent stack on musl.
