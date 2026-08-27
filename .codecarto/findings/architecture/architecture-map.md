# Architecture Map

Evidence levels used throughout: `observed fact`, `strong inference`,
`portability hazard`, `open question`.

## System Intent

Pi-Filling runs a real coding agent **on an Android phone**, not as a thin client
to a server. The user points it at a git repository, types a prompt, and the
agent reads and edits files, runs shell and git commands, and commits — all
on-device, with only the LLM API call leaving the handset. `observed fact`
(README.md, V1_SPEC.md §Scope). The product bet is that a phone is a viable
*host* for an agent rather than a remote control for one; V1 is deliberately
Android-only and single-device, with multi-device "agent fabric" pushed to v2+.
`observed fact` (V1_SPEC.md).

The repository is pre-1.0 and is structured around a three-layer stack that the
docs name explicitly and consistently (Layer 1 / 2 / 3). That naming is load
bearing — it is used in code comments, docs, and commit messages. `observed fact`

## Layer Map

### The three product layers

| Layer | Lives in | What it is |
|---|---|---|
| **Layer 1** | `android/app/` | Kotlin/Compose app: UI, key storage, foreground service, process supervision |
| **Layer 2** | `android/proot-bootstrap/` + runtime download | proot + talloc cross-compiled to `.so`, hosting an Alpine rootfs fetched at first run |
| **Layer 3** | `node-wrapper/` | Node process wrapping `@earendil-works/pi-agent-core`, speaking JSONL RPC over stdio |

Layer 3 is shipped *inside* Layer 1 as an APK asset and copied into Layer 2's
filesystem at provisioning time. `observed fact` (`app/build.gradle.kts`
`copyWrapperAssets`; APK contains `assets/wrapper/{src/wrapper.mjs,package.json,
package-lock.json}`, verified on-device).

### Package Inventory

| Package / Module | Role | Public Entrypoints | Key Dependencies | Runtime Surface |
|---|---|---|---|---|
| `android/app` (`industries.huginn.pifilling`) | product shell | `MainActivity`, `DaemonService` | AndroidX Compose, kotlinx-coroutines/serialization | Android app process |
| `…/ui` | UI or rendering | `PiFillingApp` (Composable), `AppViewModel` | `AgentController` | Compose UI |
| `…/runtime` | core semantics | `AgentController` | `LinuxSandboxManager`, `WrapperClient`, `SecureKeyStore` | app process |
| `…/sandbox` | integration adapter | `LinuxSandboxManager`, `ProotExecutor`, `RootfsDownloader`, `AgentProvider`, `SandboxState` | Android `Os`, proot `.so` | app process + guest processes |
| `…/wrapper` | protocol or normalization | `WrapperClient`, `Protocol` | kotlinx-serialization | stdio pipes |
| `…/storage` | persistence or state | `SecureKeyStore` | AndroidKeyStore, SharedPreferences | app-private storage |
| `…/service` | product shell | `DaemonService` | Android FGS API | foreground service |
| `node-wrapper` | core semantics + protocol | `src/wrapper.mjs` (CLI) | `@earendil-works/pi-agent-core`, `pi-ai`, `pi-coding-agent` @ 0.84.3 | Node ≥22 process in guest |
| `android/proot-bootstrap` | integration adapter | `build-proot.sh` | Android NDK r29, termux/proot, talloc 2.4.3 | build-time only |
| `spike-host-alpine` | **not product** | `driver.mjs`, `driver-extras.mjs`, `driver-e2e.mjs` | same pi-mono packages | dev machine only |

`spike-host-alpine/` is historical evidence, not a shipped component — it answered
the Stage-0 feasibility questions and is retained as a regression harness.
`observed fact` (SPIKE_NOTES.md; nothing in `android/` references it).

### Dependency Direction

The graph is acyclic and flows in one direction:

```
ui  →  runtime  →  sandbox  →  (proot .so, Alpine rootfs)
            ↓          ↓
         wrapper    storage
            ↓
       stdio pipes → node-wrapper (Layer 3) → pi-agent-core → provider API
```

`strong inference` from import direction across all 15 Kotlin files.

- **Lowest stable layer inside the repo:** `wrapper/Protocol.kt` and
  `sandbox/SandboxState.kt` — pure data/constants, imported by others, importing
  nothing from the project. `observed fact`
- **No cycles observed.** `sandbox` never imports `ui` or `runtime`; `runtime`
  never imports `ui`. `observed fact`
- `sandbox/AgentProvider.kt` is a deliberate mirror of the `PROVIDERS` table in
  `node-wrapper/src/wrapper.mjs`. It is a **duplicated contract across a language
  boundary with no automated check** — the two can drift silently. `portability
  hazard`

### Wrappers around shared internals

`node-wrapper` is a thin façade over `pi-agent-core`: it owns exactly one `Agent`
for the process lifetime and exposes a deliberately small RPC surface rather than
re-exporting the library. `observed fact` (`node-wrapper/DESIGN.md`).

## Public Surfaces

### Layer 3 CLI

`node src/wrapper.mjs --repo <path> [--provider <name>] [--model <id>]
[--system-prompt <file>]` `observed fact`

Exit codes are a contract Layer 1 branches on: `1` config/usage, `2` `--repo`
missing or not a directory, `3` model not in the registry, `4` uncaught
exception. `observed fact` (wrapper.mjs `fatal()` sites; DESIGN.md).

### Layer 3 ⇄ Layer 1 wire protocol (JSONL over stdio)

Requests carry a numeric `id` and one of four methods: `prompt`, `abort`,
`state`, `shutdown`. `observed fact` (`Protocol.kt` `WrapperMethod`).

Responses are `{id, result}` or `{id, error:{code, message}}` with codes
`bad_params`, `busy`, `unknown_method`, `shutting_down`, `handler_error`.
`observed fact` (`WrapperErrorCode`).

Events are pushed unsolicited: two synthetic ones (`wrapper_ready`,
`wrapper_error`) plus `agent_start` / `agent_end` and the full pi-agent-core
event stream forwarded verbatim (`turn_start`, `message_update`,
`tool_execution_start/end`, `message_end`, `turn_end`). `observed fact`

**stdout is structured JSONL; stderr is free-form logs.** They are deliberately
not merged, because Layer 1 parses one and logs the other. `observed fact`
(`ProotExecutor.start` KDoc).

### User-facing surfaces

Three screens gated by a `when` on state: API-key entry (with provider chooser) →
sandbox provisioning → session (repo path, prompt box, transcript).
`observed fact` (`PiFillingApp.kt`).

### Persistent artifacts

`shared_prefs/pifilling_secure_prefs.xml` (encrypted credentials, one entry per
provider), the sandbox tree, the `.setup-complete` marker, and `memory.md` inside
the *user's* repository. `observed fact`

## Runtime Lifecycle

**Boot.** `PiFillingApplication.onCreate` builds `AppContainer` (manual DI, no
Hilt/Dagger). `SandboxState` is seeded `Ready` or `NotInstalled` purely from the
existence of the marker file. `observed fact`

That seeding has a consequence worth stating plainly: **`setup()` is never called
again once the marker exists**, so any provisioning step added later would never
reach existing installs. This was a real defect; `ensureCurrent()` plus a
versioned marker (`setup=N`) now backfills from `startSession`. `observed fact`
(LinuxSandboxManager; verified on-device, `android/VERIFICATION.md`).

**Provisioning** (once): download Alpine rootfs → extract (guarding path
traversal and applying the tar mode bits) → write `resolv.conf` and
`repositories` → `apk update` → `apk add nodejs npm git bash` → configure a git
identity → copy Layer 3 assets in and `npm install` → write marker. `observed
fact`

**Session.** `startSession` decrypts the provider's key, calls `ensureCurrent()`,
starts `DaemonService` (a `dataSync` foreground service that keeps the process
alive), spawns the wrapper under proot, and drains its event stream until
`wrapper_ready`. `observed fact`

**Shutdown.** `shutdown` RPC, then process teardown; closing stdin also triggers a
clean exit. `observed fact`

## Concurrency Model

- **Layer 1** is Kotlin coroutines with `StateFlow` as the single source of UI
  truth. Key decryption and process I/O are pushed to `Dispatchers.IO`
  deliberately, to keep the AndroidKeyStore and blocking pipe reads off the
  default pool. `observed fact`
- **Layer 3** is a single Node event loop owning exactly one `Agent`. Concurrency
  is refused rather than queued: a second `prompt` while one is running returns
  `busy`. `observed fact` (DESIGN.md)
- **Backpressure** is handled explicitly — the wrapper honours stdout
  backpressure and flushes before exit so a slow reader cannot truncate the final
  `agent_end`. `observed fact`
- `WrapperClient` runs cancellable reader coroutines over stdout and stderr with
  a backpressure-safe fan-out. `observed fact`

**Portability hazards.** The stdio JSONL protocol assumes a POSIX process with
inheritable pipes; proot's `-0` uid emulation makes the guest believe it is root
without real privilege; and Android may kill the process group despite the
foreground service. None of these translate 1:1 off Android. `portability hazard`

## Build and Packaging

Three independent toolchains, only loosely coupled — details in
`findings/build-and-deploy/build-and-deploy.md`.

- **Layer 1:** Gradle 8.10.2 / AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35, and
  **JDK 17 specifically** — Kotlin 2.0.21 refuses to run on Java 25 and reports
  only the version string. `observed fact` / `portability hazard`
- **Layer 2:** `build-proot.sh`, vendored byte-identical from Kai and treated as
  a black box, needs NDK r29 and produces `.so` files for three ABIs. `observed
  fact` (VENDORED.md)
- **Layer 3:** npm with an exact-pinned lockfile; `npm ci` everywhere.
  `observed fact`
- **CI:** six GitHub Actions jobs covering wrapper lint/test, an allowlisted
  dependency audit, an Alpine/musl Docker run, the spike API contract, the
  Android debug build, and shellcheck. `observed fact`

## Porting Priorities

| Component | Priority | Rationale |
|---|---|---|
| `node-wrapper/src/wrapper.mjs` + protocol | core | The agent loop and the only contract between layers. Everything else is delivery. |
| `sandbox/LinuxSandboxManager` + `ProotExecutor` | core | Without a working guest there is no agent; holds the hard-won platform fixes. |
| `sandbox/RootfsDownloader` | core | Tar extraction with traversal guards and mode bits; subtle and security-relevant. |
| `storage/SecureKeyStore` | important | Credential handling; AES-GCM via AndroidKeyStore, verified on-device. |
| `wrapper/WrapperClient` + `Protocol` | important | Any host needs this to drive the wrapper. |
| `service/DaemonService` | important | Android-specific survival; a different host solves it differently. |
| `runtime/AgentController` | important | Orchestration, but thin and re-derivable from the protocol. |
| `ui/*` | optional | Deliberately minimal scaffold; explicitly slated for replacement. |
| `android/proot-bootstrap` | optional | Vendored; re-vendor rather than port. |
| `spike-host-alpine` | incidental | Historical evidence, not product. |

## Durable State

Full detail in `findings/state-and-storage/state-and-storage.md`.

- **Credentials:** `pifilling_secure_prefs.xml`, one entry per provider keyed
  `<provider>_api_key`, stored as `base64(IV):base64(ciphertext)` under an
  AndroidKeyStore AES-256-GCM key. `observed fact`
- **Sandbox:** `files/linux-sandbox/` — `rootfs/`, `tmp/`, `nativelib/`
  (SONAME-corrected `libtalloc.so.2`), and `.setup-complete` carrying
  `alpine=… abi=… setup=N`. `observed fact`
- **Agent memory:** `memory.md` in the user's repo, git-synced and therefore
  treated as untrusted input folded into the system prompt with
  delimiter-breakout neutralisation. `observed fact` (SECURITY.md)
- **Environment:** `<PROVIDER>_API_KEY` and `WRAPPER_LOG_LEVEL` are passed at
  spawn; the wrapper scrubs **every** known provider key from `process.env`
  after capture. `observed fact`

## Coverage and limits

- **Inspected scope:** all 82 tracked files. Every Kotlin source in
  `android/app/src/main/kotlin`, `node-wrapper/src` and `test`, the CI workflow,
  Gradle build files, `build-proot.sh` headers and configuration, and all
  root-level docs. Behaviour cross-checked against a live device session on a
  Galaxy Z Fold 5 (see `android/VERIFICATION.md`).
- **Skipped scope:** `build-proot.sh` internals below its configuration block
  (vendored, treated as a black box by project policy); `node_modules` and the
  pi-mono packages themselves; `spike-host-alpine` drivers read only for role
  classification; Compose theming.
- **Evidence basis:** source inspection, plus tests, plus runtime verification on
  physical hardware for the sandbox, protocol and storage paths.
- **Known blind spots:** no release/minified build has ever been produced, so R8
  behaviour is unknown; the `anthropic` provider path has never made a live call
  from a device; `git push` and GitHub auth are entirely unimplemented, so their
  eventual shape is not visible in the code.
- **Coverage disposition:** COMPLETE

## Open Questions

| ID | Kind | Description | Deferred Reason |
|---|---|---|---|
| q-r8-release-shape | runtime-test | No release build has been produced; R8/ProGuard effects on kotlinx-serialization models and reflection are unverified. `proguard-rules.pro` exists but is untested. | Needs an actual `assembleRelease` plus on-device run; no static reading settles it. |
| q-provider-table-drift | maintainer-decision | `AgentProvider.kt` and the wrapper's `PROVIDERS` table encode the same contract in two languages with no automated check. | Whether to generate one from the other, or add a cross-language test, is a design ruling. |
| q-fgs-survival | runtime-test | Whether the `dataSync` foreground service actually keeps a long agent run alive under real memory pressure and Doze is unmeasured. | Requires a long-running device test under induced pressure. |
| q-anthropic-on-device | runtime-test | The provider v1 ships against has never made a live call from the handset; all device verification used `openai-completions`. | Needs a device run with an Anthropic key. |

## Carry-Forward

None. `architecture-only` has no later phase, so nothing can be routed onward;
every gap above is recorded as an open question or in Coverage and limits.

| ID | Target Phase | Description | Deferred Reason |
|---|---|---|---|
| — | — | — | — |

---

## Validation

| # | Criterion | Result | Evidence |
|---|-----------|--------|----------|
| 1 | The system intent is documented. | PASS | §System Intent. |
| 2 | The layer map and dependency direction are documented. | PASS | §Layer Map, §Package Inventory, §Dependency Direction (acyclic; lowest stable layer named). |
| 3 | Public surfaces are identified. | PASS | §Public Surfaces — CLI with exit codes, four RPC methods, five error codes, event stream, three screens, persistent artifacts. Detail in `findings/public-surfaces/public-surfaces.md`. |
| 4 | Runtime lifecycle, concurrency model, and porting priorities are summarized. | PASS | §Runtime Lifecycle, §Concurrency Model, §Porting Priorities. |
| 5 | Findings are marked with evidence levels. | PASS | `observed fact` / `strong inference` / `portability hazard` / `open question` used throughout. |
| 6 | Coverage and limits name inspected scope, skipped scope, evidence basis, and blind spots. | PASS | §Coverage and limits, all four named; disposition COMPLETE. Four residual unknowns recorded as open questions. |

**Validated by:** 2026-08-26 (architecture phase, architecture-only pipeline)
**Overall:** PASS
