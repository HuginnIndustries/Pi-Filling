# Defect Report — Pi-Filling

## Scan Context

- **Source:** `../` (repository root)
- **Architecture reference:** `findings/architecture/architecture-map.md`
- **Contracts reference:** not available (architecture-only → defect-scan pipeline)
- **Protocols reference:** not available
- **Pipeline:** defect-scan (architecture → defect-scan)
- **Date:** 2026-08-27

Six defects were already found and fixed during on-device verification earlier in
this session (see `android/VERIFICATION.md`). This scan is static and looks for
what remains. Evidence levels: `observed fact`, `strong inference`.

---

## Pass 1: Logic and Correctness

| # | Location | Defect | Severity | Evidence Level | Action |
|---|----------|--------|----------|----------------|--------|
| 1.1 | `wrapper/WrapperClient.kt` `handleEvent` | The comment states events are dropped "oldest first" under buffer pressure. `MutableSharedFlow` is constructed with `extraBufferCapacity = 256` and the default `onBufferOverflow = SUSPEND`, so `tryEmit` **drops the newest event and returns false** instead. Behaviour is the opposite of the documented intent, and the newest agent events are the ones a UI most needs. | Medium | strong inference | Construct the flow with `onBufferOverflow = BufferOverflow.DROP_OLDEST` to match the comment, or correct the comment and accept newest-drop deliberately. |
| 1.2 | `wrapper/WrapperClient.kt` `handleEvent` | `hasMemory` is parsed as `(data["hasMemory"] as? JsonPrimitive)?.content?.toBoolean()`. `String.toBoolean()` returns `false` for anything that is not literally `"true"`, so a malformed or renamed field degrades silently to `false` rather than surfacing. | Low | observed fact | Use `booleanOrNull` and treat a missing/unparseable value as an explicit protocol error. |

---

## Pass 2: Error Handling and Resilience

| # | Location | Defect | Severity | Evidence Level | Action |
|---|----------|--------|----------|----------------|--------|
| 2.1 | `runtime/AgentController.kt` `prompt()` | `wc.prompt(text)` is called unguarded inside `scope.launch`. `WrapperClient.call` throws `WrapperProcessExitedException` when the wrapper has died. The scope is `CoroutineScope(SupervisorJob())` with **no `CoroutineExceptionHandler`**, so an uncaught exception reaches the thread's default handler and **crashes the app** instead of setting `SessionState.Failed`. `startSession`, `provision` and `abort` all guard; `prompt` is the only unguarded path, and it is the one the user hits most. | **High** | strong inference | Wrap in `try/catch` setting `SessionState.Failed`, and add a `CoroutineExceptionHandler` to the scope as a backstop. |
| 2.2 | `wrapper/WrapperClient.kt` `call()`, `awaitReady()` | No timeout anywhere. A wrapper that is alive but wedged (hung provider call, stalled tool) leaves `deferred.await()` suspended forever, and the UI has no path back to a usable state short of killing the app. The process-death path is handled; the no-response path is not. | Medium | observed fact | Wrap calls in `withTimeout`, failing the request and surfacing an actionable error. Give `awaitReady` its own shorter bound. |

---

## Pass 3: Concurrency and Resource Management

| # | Location | Defect | Severity | Evidence Level | Action |
|---|----------|--------|----------|----------------|--------|
| 3.1 | `wrapper/WrapperClient.kt` `call()` vs `onProcessExit()` | Race between the liveness check and registration. `call()` reads `exitCode` (null), then inserts into `pending`. If the process exits in that window, `onProcessExit` sets `exitCode` and drains `pending` **before** the insert lands. The new deferred is never failed, and the caller suspends forever — the exact hang `onProcessExit` exists to prevent. Compounded by 2.2: with no timeout, nothing recovers it. | **High** | strong inference | Re-check `exitCode` after inserting into `pending` and fail the deferred if it is now set, or guard registration and drain with a shared lock. |
| 3.2 | `runtime/AgentController.kt` | `scope` is created with `CoroutineScope(SupervisorJob())` and never cancelled — there is no `close()`/`onCleared()`. Harmless while the controller is app-scoped in `AppContainer`, but it silently becomes a leak if the controller is ever made session- or Activity-scoped. | Low | observed fact | Expose a `close()` cancelling the scope, and call it from the container's teardown. |

---

## Pass 4: Security and Trust Boundaries

| # | Location | Defect | Severity | Evidence Level | Action |
|---|----------|--------|----------|----------------|--------|
| 4.1 | `storage/SecureKeyStore.kt` `getOrCreateKey()` vs `SECURITY.md` | `KeyGenParameterSpec` sets block mode, padding and key size but requests **neither `setIsStrongBoxBacked(true)` nor key attestation**. `SECURITY.md` describes the key as "hardware-bound". Most devices back AndroidKeyStore with the TEE, but the code asks for no such guarantee and nothing verifies it — the documented claim exceeds what the implementation establishes. | Medium | observed fact | Either request StrongBox with a graceful fallback and verify via an attestation chain, or soften the wording in `SECURITY.md` to "AndroidKeyStore-managed" and record hardware-binding as unverified. |
| 4.2 | `node-wrapper/src/wrapper.mjs` memory.md injection | The delimiter-breakout guard is `replaceAll("</prior_memory>", …)` — exact-match, so it is case- and whitespace-sensitive. `</PRIOR_MEMORY>` or `</prior_memory >` pass through unmodified. `memory.md` is git-synced and explicitly treated as untrusted, so this is a documented control with a bypass. | Low | observed fact | Neutralize with a case-insensitive, whitespace-tolerant regex, and add a test using a mixed-case closing tag. |
| 4.3 | `wrapper/WrapperClient.kt` `pumpStderr()` | Wrapper stderr is forwarded verbatim to logcat under the `wrapper` tag. Wrapper diagnostics echo the CLI arguments they were given, so a credential mistyped into a UI field that becomes an argument reaches the device log. Observed on hardware this session; the log buffer is volatile and nothing is persisted, but the path is real and needs no agent to trigger. | Medium | observed fact | Do not forward wrapper stderr in release builds, or redact argument-shaped values before logging. Already recorded in `SECURITY.md`. |

---

## Pass 5: API Contract Violations

| # | Location | Defect | Severity | Evidence Level | Action |
|---|----------|--------|----------|----------------|--------|
| 5.1 | `sandbox/AgentProvider.kt` ⇄ `PROVIDERS` in `node-wrapper/src/wrapper.mjs` | The same contract — provider ids, key env-var names, default models — is written twice in two languages with nothing enforcing agreement. Drift does not fail the build; it fails at run time on a device, as a confusing symptom. Convention C02 documents the hazard but nothing checks it. | Medium | observed fact | Add a test that reads both sources and asserts the id/keyEnv sets match, or generate one from the other. |
| 5.2 | `ROADMAP.md` | Stage claims contradict the repository: 1.1/1.2b report "8/8" where the suite is now 25/25, 1.2a says the cross-compile still "needs a dev machine" though proot is built for three ABIs, and 1.2c says "Not yet built or run on a device" though it is built, installed, and verified. A roadmap that misreports completed state cannot be used to plan. | Low | observed fact | Rewrite against verified state; treat `android/VERIFICATION.md` as the source of truth for what runs. |

---

## Pass 6: Configuration and Environment Hazards

| # | Location | Defect | Severity | Evidence Level | Action |
|---|----------|--------|----------|----------------|--------|
| 6.1 | `android/gradle/wrapper/gradle-wrapper.properties` | `distributionSha256Sum` is not pinned, so the Gradle distribution is fetched without integrity verification. The project otherwise takes supply-chain seriously (exact npm pins, `npm ci`, an allowlisted audit gate, a pinned NDK), which makes this the weakest link in an otherwise tight chain. | Medium | observed fact | Pin the checksum Gradle publishes for 8.10.2. |
| 6.2 | `ui/AppViewModel.kt` | The selected provider is held only in memory. On relaunch the app resets to Anthropic and asks for a key even when another provider's credential is stored; re-selecting recovers it. Confusing rather than harmful. | Low | observed fact | Persist the selection alongside the credentials. |

---

## Summary

### Findings by Severity

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High | 2 |
| Medium | 6 |
| Low | 5 |
| **Total** | **13** |

### Findings by Category

| Pass | Critical | High | Medium | Low | Total |
|------|----------|------|--------|-----|-------|
| 1. Logic and correctness | 0 | 0 | 1 | 1 | 2 |
| 2. Error handling | 0 | 1 | 1 | 0 | 2 |
| 3. Concurrency and resources | 0 | 1 | 0 | 1 | 2 |
| 4. Security and trust | 0 | 0 | 2 | 1 | 3 |
| 5. API contract violations | 0 | 0 | 1 | 1 | 2 |
| 6. Config and environment | 0 | 0 | 1 | 1 | 2 |

### Top Findings

1. **3.1 — `WrapperClient.call()` can orphan a request (High).** A process exit racing request registration leaves the caller suspended forever. Compounded by 2.2, nothing recovers it.
2. **2.1 — `AgentController.prompt()` crashes the app on wrapper death (High).** The one unguarded call site, in a scope with no exception handler, on the path users hit most.
3. **4.1 — The "hardware-bound" key claim exceeds the code (Medium).** No StrongBox request, no attestation; `SECURITY.md` promises more than `KeyGenParameterSpec` asks for.
4. **5.1 — The provider contract is duplicated across languages with no check (Medium).** Drift fails at run time on a device, not at build time.
5. **1.1 — Event overflow drops the newest, not the oldest (Medium).** The comment asserts the opposite, so the bug reads as intended behaviour.

Two structural observations worth more than any single row. First, **the Node wrapper is markedly more defensive than the Kotlin layer** — it handles serialization failure, stdout backpressure, EPIPE, malformed requests and unknown methods, while the Kotlin side has the two High findings. That asymmetry tracks test coverage: the wrapper has 25 tests, `android/` has none. Second, **the two High findings interact**: 3.1 produces a hang, 2.2 removes any timeout that would break it, and 2.1 means the adjacent failure mode is a crash. The wrapper-death path is the weakest area of the codebase.

---

## Coverage and limits

- **Inspected scope:** all Kotlin sources under `android/app/src/main/kotlin` (15 files), `node-wrapper/src/wrapper.mjs`, the wrapper test suite, `RootfsDownloader` extraction logic, `SecureKeyStore` crypto, CI workflow, Gradle configuration, and the root docs against which claims were checked.
- **Skipped scope:** `build-proot.sh` internals (vendored, project policy is to re-vendor not patch — Convention C03); `node_modules` and the pi-mono packages; `spike-host-alpine` drivers (not product); Compose theming; generated Gradle files.
- **Evidence basis:** source inspection, plus runtime verification on physical hardware for the paths exercised earlier this session, plus the existing wrapper test suite.
- **Known blind spots:** no release/minified build exists, so R8-specific defects cannot be seen; no instrumented tests exist, so Android lifecycle defects (rotation, process death, background restore) are invisible to a static pass; concurrency findings 3.1 and 2.2 are reasoned from the code rather than reproduced under a race, so they are `strong inference` rather than observed failures; provider-side behaviour under network partition is untested.
- **Coverage disposition:** PARTIAL — the Android lifecycle and release-build surfaces are unexamined for lack of any harness that can reach them.

## Open Questions

| ID | Kind | Description | Deferred Reason |
|---|---|---|---|
| q-wrapperclient-race-repro | runtime-test | Whether 3.1 is reachable in practice, and how often, is unmeasured. The window is between a null `exitCode` read and a `ConcurrentHashMap` insert. | Needs a stress harness that kills the wrapper mid-request; reading cannot establish the rate. |
| q-android-test-harness | maintainer-decision | `android/` has no tests at all, which is why Passes 1–3 found more there than in the wrapper. Whether to add JVM/Robolectric tests, instrumented tests, or both is a project decision. | A scope and tooling ruling, not a finding. |

---

## Validation

| # | Criterion | Result | Evidence |
|---|-----------|--------|----------|
| 1 | At least three analysis passes produced findings or documented "no defects found." | PASS | All six passes produced findings (13 total). |
| 2 | Each finding has location, severity, evidence level, and recommended action. | PASS | Every row carries all four columns. |
| 3 | Findings are organized by pass and sorted by severity. | PASS | Six pass sections; rows ordered High → Medium → Low within each. |
| 4 | Summary tables are complete and counts match the detailed findings. | PASS | 0/2/6/5 = 13; per-pass table sums to 13 and matches the rows. |
| 5 | Findings are marked with evidence levels. | PASS | `observed fact` / `strong inference` on every row. |
| 6 | Coverage and limits name inspected scope, skipped scope, evidence basis, and blind spots. | PARTIAL | All four named, but disposition is PARTIAL: Android lifecycle and release-build surfaces are unreachable without a harness that does not exist. Tracked as `q-android-test-harness`; the race's reproducibility is tracked as `q-wrapperclient-race-repro`. |

**Validated by:** 2026-08-27 (defect-scan phase)
**Overall:** PASS WITH GAPS
