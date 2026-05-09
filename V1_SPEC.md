# v1 Spec — phone-first AI coding agent

**Status:** locked. Last revision: 2026-05-09 (post-CodeCarto reads of Kai, pi-mono, Hermes).
**Working branch:** `claude/spike-pi-agent-android-NugAe` (across all participating repos).
**Project name:** none yet. Branding emerges from building.

This document is the contract. Anything not in scope here is **not v1**, regardless of how appealing it sounds. When scope creep arrives, the answer is "see V1_SPEC.md, then re-open the question after v1 ships."

---

## What v1 is

An Android-first AI coding agent that runs **on the phone**, not as a thin client to a server. The agent edits files, runs git operations, and uses Anthropic's API to do real software work. v1 is intentionally phone-only; multi-device "agent fabric" is v2+.

**Two architectural bets:**

1. **Kai already solved Android's hardest sandbox problems** (Alpine + proot, foreground service, app-private storage, Compose entry point). v1 inherits Kai's bootstrap so we don't reinvent it.
2. **`memory.md` as a tracked file in a git-synced repo replaces vector stores, CRDTs, and sync servers in v1.** Git is the sync protocol; conflicts are an LLM problem, not a distributed-systems problem.

---

## Scope — In

- **Android only.** No iOS in v1 (iOS has no proot path).
- **Alpine Linux 3.21.3 via termux/proot**, bootstrapped using Kai's `build-proot.sh` and `RootfsDownloader`/`LinuxSandboxManager` patterns.
- **Node ≥ 20.6** installed inside Alpine via `apk add nodejs npm`.
- **Agent runtime:** `@mariozechner/pi-agent-core` and `@mariozechner/pi-ai` as npm dependencies. Possibly `@mariozechner/pi-coding-agent` for its bundled tools (read/bash/edit/write). **Do not fork.**
- **File operations restricted to `/root` of the Alpine sandbox.**
- **Git clone/pull/push to GitHub** — single provider. Shell out to git inside Alpine; no GitSync dependency for v1.
- **Single LLM provider:** Anthropic. API key supplied by user; stored in app-private storage.
- **Memory:** sectioned markdown file (`memory.md`) checked into the same git repo as the code. Manually round-tripped via commit/pull.
- **UI surface:** decided after the spike. Default expectation is `pi-coding-agent`'s TUI accessed via a terminal view, OR `pi-web-ui` rendered in an Android WebView — whichever the spike makes obvious.
- **Foreground service** based on Kai's `DaemonService` pattern (FGS type `dataSync`, `START_STICKY`, `MainActivity.onStart()` re-assertion).

## Scope — Out (named explicitly so we can say no)

- Server-side companion agent (v2)
- Tailscale or any cross-device transport (v2)
- GitHub-issue or chat-based async transport (v2)
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

---

## What we inherit from Kai (corrected after CodeCarto read)

The handoff doc overstated the inheritance. Reality is narrower but cleaner:

| Component | Inherited? | Notes |
|---|---|---|
| Alpine 3.21.3 minirootfs + termux/proot @ pinned commit | ✅ | `RootfsDownloader.kt:17-27` (mirrors hardcoded), `build-proot.sh` (cross-builds for arm64-v8a, armeabi-v7a, x86_64) |
| `SandboxController` interface (setup/execute/cancel/reset) | ✅ | Result envelope `{success, stdout, stderr, exit_code, timed_out}` |
| `DaemonService` foreground service + `MainActivity.onStart()` re-assertion | ✅ | FGS type `dataSync`, `START_STICKY`. Survives Doze; may be silently killed by aggressive OEM ROMs |
| `build-proot.sh` cross-compile pipeline | ✅ | Reuse as-is. NDK r29, Python 3, talloc 2.4.3 |
| **SAF persistent directory binds** | ❌ | Kai delegates to FileKit and does *not* call `takePersistableUriPermission`. We accept this for v1 |
| **Battery optimization whitelist prompt** | ❌ | Kai never requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. We accept this for v1 |
| Notification listener / heartbeat scheduler | ❌ | Kai-internal, deeply tied to its ViewModel layer |

**Tight couplings to lift when porting:** Alpine version + 6 mirror URLs hardcoded in `RootfsDownloader.kt:17-27`; `jniLibs/<abi>/` path baked into Gradle; FileProvider authority `${applicationId}.fileprovider`. Plan to parameterize all three.

**Known operational risks accepted:** OEM-kill of the foreground service is silent (no UI warning); Android 14+ `dataSync` FGS has a 6-hour time limit, after which the user must reopen the app.

---

## The spike (week 1 of real work)

**Use case:** "agent reads README.md inside an Alpine-mounted git checkout, makes a one-line edit, commits, pushes to GitHub."

**Default path: Track A (library import).** `pi-agent-core` exports an `Agent` class with `.prompt()` / `.subscribe()` event streams that is fully turn-by-turn drivable from a Node wrapper. `pi-coding-agent` also documents an embedded SDK via `createAgentSession()` that ships the file/bash/edit tools for free. Track B (subprocess) is hackable but its scriptable modes (`--print`, `--mode json`, `--mode rpc`) aren't designed for one-shot driving and would require us to implement a bidirectional RPC client.

**The spike's job is to answer three empirical questions that the spec couldn't:**

1. **Alpine/musl headless compat.** Does `pi-agent-core` + `pi-ai` install and run cleanly on Alpine inside proot, with optional native deps (`@silvia-odwyer/photon-node` wasm, `@mariozechner/clipboard`)?
2. **Custom auth injection.** Can we pass an Anthropic API key per-session without using global env-var/auth-storage? If not, we need to design around it or contribute upstream.
3. **AbortSignal propagation.** Does closing a socket cleanly cancel an in-flight agent turn? Important for any long-running tool execution.

**Spike output (one page):** answers to the three questions, decision on `pi-agent-core` direct vs `createAgentSession()` higher-level SDK, list of any patches needed upstream.

**Time budget:** 5 working days. If after 5 days the three questions aren't answered, escalate before extending.

---

## Open decisions (deferred, with resolution triggers)

| Decision | Resolve when |
|---|---|
| `pi-agent-core` vs `createAgentSession()` | End of spike |
| UI surface (TUI in terminal view / WebView with pi-web-ui / native Compose) | After agent loop runs end-to-end |
| Memory format final pick (sectioned markdown / JSONL append-only / sqlite-derived) | When v2 phone+server pair work begins |
| Project name | When the wedge becomes obvious |
| GitSync vs shell-out git | After the spike's git step works one way |

## Anti-scope reminders (from handoff brainstorm)

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

---

## References

- Kai sandbox spec: `theamericanmaker/kai` branch `claude/setup-codecarto-pipeline-NamtZ`, `.codecarto/`
- pi-mono skill docs + API: `theamericanmaker/pi-mono` branch `claude/create-skill-docs-sTAlC`, `docs/` and `packages/`
- Hermes design context (v2-only): `theamericanmaker/hermes-agent` branch `claude/codecarto-hermes-analysis-abvQm`, `.codecarto/`
- Original handoff: `./HANDOFF.md`
