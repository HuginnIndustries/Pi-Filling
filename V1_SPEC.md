# v1 Spec — phone-first AI coding agent

**Status:** locked, post-spike; wrapper Docker verification complete. Last revision: 2026-05-21.
**Working branch:** `main`.
**Project name:** none yet. Branding emerges from building. Repo is `Pi-Filling` as a working title.

This document is the contract. Anything not in scope here is **not v1**, regardless of how appealing it sounds. When scope creep arrives, the answer is "see V1_SPEC.md, then re-open the question after v1 ships."

For *how* the pieces fit together, see [`ARCHITECTURE.md`](./ARCHITECTURE.md). For *what comes next*, see [`ROADMAP.md`](./ROADMAP.md). For the spike's empirical results, see [`SPIKE_NOTES.md`](./SPIKE_NOTES.md).

---

## What v1 is

An Android-first AI coding agent that runs **on the phone**, not as a thin client to a server. The agent edits files, runs git operations, and uses Anthropic's API to do real software work. v1 is intentionally phone-only; multi-device "agent fabric" is v2+.

**Two architectural bets:**

1. **Kai already solved Android's hardest sandbox problems** (Alpine + proot, foreground service, app-private storage, Compose entry point). v1 inherits Kai's bootstrap so we don't reinvent it.
2. **`memory.md` as a tracked file in a git-synced repo replaces vector stores, CRDTs, and sync servers in v1.** Git is the sync protocol; conflicts are an LLM problem, not a distributed-systems problem.

Both bets survived the spike intact and are now baseline.

---

## v1 acceptance test

> User opens the app on their Android phone, points it at a GitHub
> repository, types `"summarize the last three commits in CHANGELOG.md
> and push the result"`, and within a minute the change is in the remote.

The host-Alpine spike and the wrapper Docker suite have proven the agent half of this end-to-end (read → edit → commit, no push, against real Anthropic; plus hermetic wrapper protocol tests on Alpine/musl). The remaining work is the Android shell/proot integration, key storage, GitHub auth, UI/control layer, and the push step.

---

## Scope — In

### Runtime stack

- **Android only.** No iOS in v1 (iOS has no proot path).
- **Alpine Linux 3.21+ via termux/proot**, bootstrapped using Kai's `build-proot.sh` and `LinuxSandboxManager` patterns.
- **Node ≥ 22** + **git** installed inside Alpine via `apk add nodejs npm git`.
- **Foreground service** based on Kai's `DaemonService` pattern (FGS type `dataSync`, `START_STICKY`, `MainActivity.onStart()` re-assertion).
- **File operations restricted to `/root` of the Alpine sandbox**, with the user's chosen project directory mounted there.

### Agent runtime (locked after spike)

- **`@earendil-works/pi-agent-core`** — agent loop, event subscription, `AbortSignal` plumbing.
- **`@earendil-works/pi-ai`** — LLM client. Anthropic provider used; other provider SDKs are bundled but unused.
- **`@earendil-works/pi-coding-agent`** — `createCodingTools(cwd)` returns the `read`, `write`, `edit`, `bash` tools as plain `AgentTool[]`. We do not use `createAgentSession()` (its `SessionManager` / `AuthStorage` / `ModelRegistry` are too opinionated for our wrapper).
- These are the maintained successor to the `@mariozechner/*` packages, which upstream deprecated; pinned to exact `0.84.3`. See the decision log and CHANGELOG.
- **All three are npm dependencies. We do not fork pi-mono.**

### Single-provider posture

- **Single LLM provider:** Anthropic. Initial model: `claude-haiku-4-5`. Upgrade path to Sonnet/Opus via config; not a separate code path.
- **API key supplied by user; stored in app-private storage** (Keystore-backed where possible). Passed to the wrapper at run start via stdin or socket — never via persistent env vars or command args.

### Memory + git

- **Memory:** sectioned markdown file (`memory.md`) checked into the same git repo as the user's code. Read at start of each prompt, written during the run via the `edit` tool the agent already has, committed alongside code changes.
- **Git operations** (clone/pull/push/commit) via the `bash` tool — no separate GitSync dependency.
- **Single git provider for v1:** GitHub. HTTPS auth via personal access token or OAuth-device flow. SSH deferred (no safe place to store private keys yet).

### UI

- **One UI surface ships in v1.** Decision deferred to after the wrapper RPC is up. Candidates ranked by current preference: (1) `pi-coding-agent`'s TUI rendered in an Android terminal view; (2) `pi-web-ui` in a WebView; (3) Compose-native chat. Whichever ships first acceptably.

## Scope — Out (named explicitly so we can say no)

- Server-side companion agent (v2)
- Tailscale / GitHub-issue / chat-based async transport (v2)
- `agentskills.io` / SKILL.md skills loader (post-v1)
- Sandcastle integration (v2)
- OB1 sync layer (v2 if ever; `memory.md` + git replaces it)
- Hermes-style heartbeats / cron-driven autonomy (v2)
- Kai-style generative interactive UI screens (v2 at earliest)
- iOS port or thin client (v2+, may never)
- MarkText editor integration
- Multi-LLM via LMRouter (v2)
- Vector search / sqlite-vec derived index (flagged for v1.5)
- Persistent SAF directory grants — Kai doesn't ship this; we don't add it in v1
- Battery optimization whitelist prompt — Kai doesn't ship this; we don't add it in v1
- `createAgentSession()` and pi-coding-agent's `SessionManager` / `AuthStorage` / `ModelRegistry`. We use `createCodingTools` only.

---

## What we inherit from Kai

| Component | Inherited? | Notes |
|---|---|---|
| Alpine minirootfs + termux/proot @ pinned commit | ✅ | `RootfsDownloader.kt:17-27` (mirrors hardcoded), `build-proot.sh` (cross-builds for arm64-v8a, armeabi-v7a, x86_64) |
| `SandboxController` interface (setup/execute/cancel/reset) | ✅ | Result envelope `{success, stdout, stderr, exit_code, timed_out}` |
| `DaemonService` foreground service + `MainActivity.onStart()` re-assertion | ✅ | FGS type `dataSync`, `START_STICKY`. Survives Doze; may be silently killed by aggressive OEM ROMs |
| `build-proot.sh` cross-compile pipeline | ✅ | Reuse as-is. NDK r29, Python 3, talloc 2.4.3 |
| **SAF persistent directory binds** | ❌ | Kai delegates to FileKit and does *not* call `takePersistableUriPermission`. We accept this for v1 |
| **Battery optimization whitelist prompt** | ❌ | Kai never requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. We accept this for v1 |
| Notification listener / heartbeat scheduler | ❌ | Kai-internal, deeply tied to its ViewModel layer |

**Tight couplings to lift when porting:** Alpine version + 6 mirror URLs hardcoded in `RootfsDownloader.kt:17-27`; `jniLibs/<abi>/` path baked into Gradle; FileProvider authority `${applicationId}.fileprovider`. Plan to parameterize all three.

**Known operational risks accepted:** OEM-kill of the foreground service is silent (no UI warning) — recovery is automatic on next foreground; Android 14+ `dataSync` FGS has a 6-hour time limit, after which the user must reopen the app.

---

## Stage-0 spike — results

The host-Alpine spike answered every question the spec needed answered to lock Track A. Full record in [`SPIKE_NOTES.md`](./SPIKE_NOTES.md); summary:

| Check | Result |
|---|---|
| Q1 — install + import on musl | ✅ Pure JS / WASM, zero native rebuilds. 121 MB of `node_modules` for Anthropic only |
| Q2 — `getApiKey` injects per-call key into streamFn | ✅ Resolved key reaches `options.apiKey` exactly as supplied |
| Q3 — `agent.abort()` cancels mock stream | ✅ `stopReason: "aborted"` in <100 ms |
| Q4 — `createCodingTools` on musl | ✅ 4 tools (`read`, `write`, `edit`, `bash`); native musl clipboard prebuild picked correctly; +65 MB / +105 packages |
| Q3-real — Anthropic SDK honors `AbortSignal` | ✅ 5 deltas streamed (419 chars), then aborted cleanly in 1569 ms |
| E2E — agent reads README, edits, `git commit` | ✅ 4 turns, 3 tool calls (read → edit → bash), 5.2 s, `stopReason: "stop"` |

The "Track A vs Track B vs `createAgentSession()`" decision is **closed in favor of Track A + `createCodingTools`**. See `SPIKE_NOTES.md` "Verdict" section.

## Stage 1.1/1.2b wrapper verification — results

The Layer 3 wrapper now has repeatable tests at two levels:

| Check | Result |
|---|---|
| `npm test` in `node-wrapper/` | ✅ `node --test` reports 8/8 with no key; real-provider integration self-skips when `ANTHROPIC_API_KEY` is absent |
| `node-wrapper/test/smoke.mjs` | ✅ Hermetic protocol coverage: `wrapper_ready`, `hasMemory`, `state`, bad params, unknown methods, idle abort, clean shutdown |
| `node-wrapper/Dockerfile` | ✅ Builds on `node:22-alpine`, installs git, runs `node --test` by default |
| Wrapper Docker run | ✅ 8/8 passing inside Alpine/musl with no key; pass a key to additionally run real Anthropic integration |
| `spike-host-alpine` Docker run | ✅ Q1 install/import, Q2 `getApiKey`/API-key plumbing, Q3 abort signal behavior all pass |

This verifies the wrapper is testable and portable to Alpine/musl. It does **not** yet prove the Android app can bootstrap proot, install Node/git, store keys, or drive the wrapper from a UI.

### Implementation notes from the spike

These are the practical details a v1 implementer needs to know that didn't fit in `ARCHITECTURE.md`:

- **`pi-ai` eagerly loads every provider SDK at module init.** 121 MB just for Anthropic. Worth flagging upstream after v1 ships if phone footprint matters; not a v1 blocker.
- **`pi-coding-agent`'s `bash` tool's cwd ≠ the `cwd` arg passed to `createCodingTools`.** In the e2e run, the agent prepended `cd <repo>` to its bash command defensively. Workaround: either run the wrapper with `process.chdir(repoPath)` before instantiating `Agent`, or wrap the bash tool with one that injects `cd`. Filing upstream optional.
- **`Agent.shouldStopAfterTurn` is on `AgentLoopConfig`, not on the `Agent` class.** No constructor option for it; setting it on the instance is dead code. To enforce a turn cap, use `afterToolCall` with `terminate: true` instead.
- **Cold-start cost:** `createCodingTools` import takes ~800–1000 ms on x86_64 desktop. Realistic phone-in-proot estimate: 3–5 s. Not fatal; profile during port.
- **API key handling pattern:** `getApiKey: () => process.env.ANTHROPIC_API_KEY` is the spike pattern. v1 replaces this with `getApiKey: () => keyFromAndroidStorage`, supplied via stdin or socket at run start.

---

## Open decisions

The spike closed most of the previously-open ones. Remainder:

| Decision | Resolve when |
|---|---|
| UI surface (TUI / WebView / Compose-native) | After Android sandbox port runs the wrapper end-to-end |
| GitHub auth (PAT vs OAuth device flow) | When 1.5 (auth + push) starts |
| Memory format evolution (sectioned markdown / JSONL / sqlite-derived) | When `memory.md` exceeds ~10k entries OR when v2 phone+server work begins, whichever comes first |
| Project name | When the product wedge becomes obvious |

All other prior questions are resolved (see decision log below).

### Decisions log

| Resolved | Question | Choice |
|---|---|---|
| 2026-05-08 | Track A vs Track B for pi-mono integration | A (library import) |
| 2026-05-09 | `pi-agent-core` direct vs `createAgentSession()` | `pi-agent-core` direct |
| 2026-05-09 | Roll our own tools vs use upstream | `createCodingTools` from `pi-coding-agent` |
| 2026-05-09 | Standalone `gitsync` dep vs shell-out `git` | Shell out via the bash tool |
| 2026-05-09 | Inherit Kai's SAF persistence | No (Kai doesn't ship it; v1 doesn't add it) |
| 2026-05-09 | Inherit Kai's battery whitelist prompt | No (Kai doesn't ship it; v1 doesn't add it) |
| 2026-05-09 | License | MIT |
| 2026-06-02 | `@mariozechner/*` agent stack deprecated upstream | Migrate to maintained `@earendil-works/*` (pinned `0.78.0`, since moved to `0.84.3`); re-verified Q1–Q3 + wrapper suite. See CHANGELOG. |
| 2026-08-26 | `0.78.x` pinned vulnerable transitives via an upstream `npm-shrinkwrap.json`, unreachable by `overrides` | Migrate to `0.84.3`: `getModel` → `getBuiltinModel` (`pi-ai/providers/all`), and `Agent` now needs an explicit `streamFn` (`pi-ai/compat.streamSimple`). Production advisories 5 → 0; Q1–Q3 + 23/23 wrapper suite re-verified. |
| 2026-06-02 | API key via env var (spike expedient) | Keep env *delivery* at spawn but scrub from `process.env` immediately after capture so it can't reach the `bash` tool or pi-ai's env fallback; non-env handshake still the longer-term goal. |

---

## Anti-scope reminders

These came up during brainstorming and are explicitly **not v1**, even when they sound appealing:

- Server-side companion agent
- Multi-device shared memory beyond what git gives for free
- Cross-project / global memory
- Vector recall
- Skill marketplace integration
- iOS thin client
- LMRouter integration for multi-provider
- Generative UI à la Kai
- Sandcastle integration
- Hermes heartbeat-style autonomy
- Background autonomy of any kind on the phone

When in doubt: if it's not on the acceptance test path, it's not v1.

---

## References

- Spike record: [`SPIKE_NOTES.md`](./SPIKE_NOTES.md), reproducible via [`spike-host-alpine/RUNBOOK.md`](./spike-host-alpine/RUNBOOK.md).
- Architecture: [`ARCHITECTURE.md`](./ARCHITECTURE.md).
- Roadmap: [`ROADMAP.md`](./ROADMAP.md).
- Kai sandbox spec: `theamericanmaker/kai` branch `claude/setup-codecarto-pipeline-NamtZ`, `.codecarto/`.
- pi-mono skill docs + API: `theamericanmaker/pi-mono` branch `claude/create-skill-docs-sTAlC`, `docs/` and `packages/`.
- Hermes design context (v2-only): `theamericanmaker/hermes-agent` branch `claude/codecarto-hermes-analysis-abvQm`, `.codecarto/`.
- Original handoff: [`HANDOFF.md`](./HANDOFF.md) (preserved historical context; superseded by this document on conflicts).
