# On-device verification

What has actually been run on real hardware, and what has not. CI proves the app
compiles and packages; it cannot prove anything below. Each entry records the
date, the device, and the observed values — so a later reader can tell evidence
from assumption.

Device serials and any device content are deliberately excluded from this file.

## 2026-08-27 — the host-capability channel speaks

First end-to-end exercise of the reverse RPC: a tool inside the sandbox (Layer 3)
asking the Android app (Layer 1) to do something the sandbox cannot do itself.
Speech is the first capability because it is the one whose success or failure is
unmistakable from outside the process.

### A seventh bug: a wrapper change never reaches a provisioned sandbox

The first run looked like the tools were missing entirely. The wrapper logged

```
tools: read, bash, edit, write
```

with no `host capabilities:` line, so `voice_speak` was not registered — on a
build whose assets plainly contained it. `deployWrapper` ran only inside
`setup()`, which short-circuits on the completion marker, so **every wrapper
change after first provisioning was invisible on device**. Every earlier run in
this file exercised whatever wrapper happened to be deployed first, which is
worth knowing when re-reading them.

Fixed with a content digest rather than a hand-bumped version, because a version
constant only works when someone remembers to bump it: the marker now carries
`wrapper=<sha256 of the asset tree, first 16 hex>` and `ensureCurrent()`
redeploys when it differs. Observed on the next launch:

```
LinuxSandboxManager: bundled wrapper differs from the deployed one; redeploying
.setup-complete: alpine=3.21.3 abi=arm64-v8a setup=4 wrapper=fb92968243ac1b41
wrapper: host capabilities: voice_speak, voice_config
```

### A harness bug that read as two model failures

Twice the agent answered by running `bash` instead of calling the tool, which
looked like poor tool selection. It was not. `adb shell input text` splits on
spaces, so only the first word of each prompt was ever typed — the transcript
showed `you: Call`. Escaping spaces as `%s` fixed it, and the model chose the
tool correctly on the first properly-delivered prompt.

Compounding it, `memory.md` in the sandbox repo — residue from an earlier run in
this file — told the agent it had written a `hello.sh` "that prints a greeting",
so a one-word prompt plus that memory made running the script a reasonable read.
Moved aside for the test.

### Passed

Voice off and voice on, same prompt, same session shape — a pair that
discriminates, because a capability that was never registered would refuse in
both.

| | transcript | Layer 1 | system engine |
|---|---|---|---|
| speech **off** | `▸ voice_speak` → agent reports speech is disabled and continues in text | no `TtsCapability` log — refused before reaching the engine | nothing |
| speech **on** | `▸ voice_speak` → "The requested phrase has been spoken" | `TtsCapability: speaking 16 chars in 1 piece(s)` | `SamsungTTS [Synthesize] eng-USA … 24000hz … 1.66s`, `Caller[…industries.huginn.pif…]` |

The spoken phrase was 16 characters, matching the chunker's count, and the
synthesis request names this package as caller — so the audio came from this
request rather than from anything else on the device.

Also passed:

- The refusal path is the *host's*, not a guest-side shortcut: the tool result
  distinguishes `not_permitted` from `unsupported_capability`, and only the
  former appeared.
- `enabled` persists to `shared_prefs/pifilling_voice.xml`, defaulting to
  `false`.

### A defect the test surfaced, and the fix

The switch was written into the idle branch of the session screen, so it existed
only *before* a session — speech could not be turned off while the agent was
speaking, which is precisely when a person reaches for it. Moved to a row that
renders in every state; switching it off already calls `stopSpeaking()`.
Verified after the fix: the switch is present with the composer on screen.

### Not proved

- **That audio was audible.** The synthesis request and its duration are
  observed; the speaker output is not. A muted device would produce the same log.
- **Chunking of long text.** One 16-character utterance is one piece. The
  multi-piece path and `QUEUE_ADD` ordering are covered by unit tests only.
- **Interruption.** `stop()` mid-utterance was not exercised on device.
- **A device with no TTS engine.** The `unsupported_capability` path is unit-
  tested; this phone has a working engine, so it cannot be produced here.

### Cleanup performed

Speech was switched back off, the state it was in before the run
(`enabled=false` confirmed in prefs). The confounding `memory.md` — itself
residue from an earlier run — was moved aside to `memory.md.bak` inside
app-private storage rather than deleted. No device content was read or copied.

## 2026-08-27 — GitHub auth plumbing

Plan step C, first half. V1_SPEC deferred "PAT vs OAuth device flow" to the start
of 1.5; resolved to a fine-grained PAT (see its decision log).

### Passed

| # | Claim | Evidence |
|---|---|---|
| 26 | git can push from inside the sandbox at all | Pushed to a local bare remote in the guest; `show-ref` on the bare repo reports `refs/heads/main` at a real SHA |
| 27 | HTTPS transport reaches GitHub from the guest | git 2.47.3 with `git-remote-https`; an unauthenticated request fails at the *credential* stage, not on DNS or TLS |
| 28 | **The credential helper delivers the token** | With the helper disabled: `fatal: could not read Username` — nothing offered a credential. With it enabled and a deliberately wrong token in the environment: `remote: Invalid username or token` — GitHub received and rejected ours. Two different errors, so the helper demonstrably fires |
| 29 | The token is not written to the guest filesystem | `git config --get credential.https://github.com.helper` reads back `!f() { echo username=x-access-token; echo password=$GITHUB_TOKEN; }; f` — the stored value holds the variable's *name*, unexpanded |
| 30 | The provisioning backfill reached an existing sandbox | `backfilled sandbox setup v3 -> v4`, marker `alpine=3.21.3 abi=arm64-v8a setup=4` |

### Not proved

- **No real push to a real GitHub repository has happened.** That needs a real
  fine-grained PAT. Everything up to "GitHub rejected this specific token" is
  verified; whether a *valid* token completes a push is not.
- **The agent has not been asked to push.** Claims 26–29 exercise git directly.
  Driving it through a prompt is the remaining half of step C.

### A test that proved nothing, recorded so it is not repeated

The first attempt pointed `ls-remote` at `octocat/Hello-World`. That repository is
public, so all three cases — no helper, helper with no token, helper with a wrong
token — succeeded identically. The run looked like a pass and demonstrated
nothing. An auth-requiring target is what makes the comparison discriminating.

## 2026-08-27 — Android test harness

Plan step B. `android/` had no tests of any kind, which the defect scan named as
the mechanism behind its own severity distribution: the Node wrapper carried 25
tests and handled every hostile input it was given, while both high findings sat
in untested Kotlin.

### What exists now

13 JVM unit tests, no emulator, wired into CI ahead of `assembleDebug`.
`FakeWrapperProcess` stands in for the wrapper — the client only ever touches
`java.lang.Process`, so stdout/stderr become pipes the test writes into, stdin is
captured for assertions, and the exit path is triggered on demand.

Real dispatchers rather than virtual time, deliberately: the readers are blocking
stream loops and the defects are about a separate OS process dying or going
quiet, so faking the clock would fake away the thing under test. Every test is
wrapped in `withTimeout`, so a regression that reopens an unbounded wait fails
the suite instead of stalling it.

### Passed

| # | Claim | Evidence |
|---|---|---|
| 23 | The tests are discriminating, not decorative | **Negative control run per fix.** Reverting `DROP_OLDEST` fails exactly the overflow test; reverting the timeouts fails exactly the two timeout tests; restoring both returns the suite to green. A test that passes before and after a fix proves nothing, so this was checked rather than assumed |
| 24 | The wrapper-death contract holds under load | 40 concurrent callers racing a process exit: all 40 terminate, all with `WrapperProcessExitedException`, none hang |
| 25 | Protocol handling survives hostile input | Malformed JSON, a line with neither `event` nor `id`, and free-form stderr all leave the reader alive and the handshake reachable |

### Not proved

- **The race in finding 3.1 was never reproduced.** Its window is microseconds
  wide, so the concurrency test pins the *contract* — once the process has
  exited, no call may hang — rather than demonstrating the original defect. A
  negative control for it would be probabilistic, so none is claimed.
- **No UI, lifecycle or Compose behaviour is covered.** These are unit tests of
  the wrapper client. Rotation, process death and background restore remain
  untested, and would need Robolectric or instrumented tests.
- `AgentController`, `LinuxSandboxManager` and `RootfsDownloader` still have no
  tests. `RootfsDownloader`'s tar handling is the most defect-prone of those and
  is the obvious next target — two of the six device bugs were in it.

## 2026-08-27 — wrapper-death hardening

Plan step A. Four defect-report findings, all on the same failure path: what
happens when the wrapper process dies or wedges.

### Passed

| # | Claim | Evidence |
|---|---|---|
| 22 | **A dead wrapper fails the session instead of killing the app** | Killed the wrapper's node process, then sent a prompt into it. App pid identical before and after (no crash or restart); logcat carried `AgentController: wrapper exited during prompt`; UI showed `Failed: wrapper exited (code 255)` and offered **Start session** again |

The log line is the load-bearing part of that evidence. "The app did not crash"
is equally consistent with "the exception never occurred" — only the log line
shows the guarded path actually executed. Before the fix this exception left
`scope.launch` into a scope with no `CoroutineExceptionHandler`, which on Android
means the process dies.

### Not proved

- **The timeout path has never fired.** `WrapperTimeoutException`,
  `withTimeoutOrNull` on `call` and `awaitReady` are implemented and compile, but
  reproducing them needs a wrapper that is *alive and unresponsive*. No current
  harness can produce that, so this is implemented-not-verified. Tracked as
  `q-wrapper-timeout-unverified`.
- **The registration race (3.1) was fixed by reasoning, not reproduced.** The
  window is between a null `exitCode` read and a `ConcurrentHashMap` insert; the
  fix re-checks after inserting. Correctness depends on `onProcessExit` setting
  `exitCode` *before* draining `pending` — the code now says so, and reordering
  those two statements silently reopens it.

## 2026-08-26 — model comparison, and a missing shell

### A sixth bug: the sandbox has no bash

The agent is told "You have read, write, edit, and bash tools. The bash tool runs
git directly", and the tool is named `bash` — but provisioning installed only
`nodejs npm git`, and Alpine's `/bin/sh` is busybox. Models reasonably emitted
bash-only syntax and got:

```
sh: bash: not found                                  (exit 127)
hello.sh: line 12: syntax error: bad substitution    (exit 2)
```

Seen across two different models, so it is an environment defect rather than one
model's quirk. Cheaper to make the environment match what the agent is told it
has than to teach every model that the "bash tool" is not bash. `bash` added to
the provisioning package set (`SETUP_VERSION` 3, backfilled into existing
sandboxes).

### Same task, same starting repo, one run each

Prompt: create `hello.sh` with a greeting function, then stage and commit it.
Repo reset to an identical single-commit state before every run. Tool-call and
error counts come from the wrapper's own event stream.

| Model | Tool calls | Tool errors | Tokens | Result |
|---|---:|---:|---:|---|
| `gpt-oss:120b` (before bash) | 8 | 0 | 1213 | committed; left `memory.md` uncommitted |
| `minimax-m3` (before bash) | 20 | 15 | 2370 | committed, after heavy flailing |
| `deepseek-v4-flash:0731` (before bash) | 6 | 0 | 1480 | committed cleanly |
| `minimax-m3` (after bash) | 6 | 0 | 1513 | committed cleanly |
| `gpt-oss:120b` (after bash) | 8 | 5 → 1 | ~1200 | committed; errors unrelated to bash |

**What this does and does not show.** The `bash: not found` / `bad substitution`
class is gone after the fix — zero bash-related errors in any post-fix run.
`minimax-m3` improved sharply and repeatably (20/15 → 6/0). `gpt-oss:120b` is
noisy run to run (0, then 5, then 1 errors) and its post-fix failures are a
different, benign class — `edit` on a `memory.md` it had not created yet
(`ENOENT`). Single runs per model are not a benchmark; treat the ordering as
indicative, not measured.

`deepseek-v4-flash:0731` and post-fix `minimax-m3` were the cleanest at 6 calls
and 0 errors.

### A credential-exposure path worth knowing about

While driving the UI, an API key was typed into the **repo path** field by
mistake. The wrapper was then launched with `--repo <key>/root/repo` and exited 2,
and its fatal message — which echoes the offending path — was piped to logcat by
the app's `wrapper` log tag. So the key landed in the device log buffer.

It was **not** persisted: nothing in app-private storage or the sandbox contained
it, and clearing the log buffers removed it. But the shape of the problem is
real and does not need an agent to trigger it: **anything typed into a field that
becomes a wrapper CLI argument can reach logcat via the wrapper's own error
output.** The wrapper carefully scrubs the key from `process.env`, and then the
host logs its stderr verbatim. Worth either not forwarding wrapper stderr to
logcat in release builds, or redacting it.

### Method note

The mistyped field was caused by driving the UI from coordinates captured on an
earlier screen: selecting a provider that already had a stored key skips the key
entry screen entirely, so the remembered "API key field" position was by then the
repo path field. This is exactly the failure the device-verification procedure
warns about — re-dump immediately before every gesture, never reuse coordinates
across a state change.

## 2026-08-26 — the agent edits and commits on device

The V1_SPEC workflow — "edits files, runs git operations, and commits the
changes" — exercised on hardware for the first time.

### A fourth bug: no git identity in the sandbox

Alpine ships no git identity, so the agent's first `git commit` failed with
`Author identity unknown`. What it did next is the part worth recording: it
burned five `bash` turns on failed commits and then **invented** an identity,
writing `Assistant Bot <assistant@example.com>` into the *user's repository*
local config. The commit landed, so a UI-level check would have called this a
pass — the defect is only visible in `git log --format=%an` and
`git config --local`.

Two things wrong with that: commits are attributed to a fabricated author that
looks like a person, and the agent silently mutated config in a repo the
operator owns. Committing is the product's entire point, so the environment
should supply an identity rather than leaving the model to improvise a
plausible-looking one.

Fixed in `LinuxSandboxManager`: provisioning now configures
`Pi-Filling Agent <agent@pi-filling.local>` globally inside the sandbox —
deliberately obviously-an-agent, on the reserved `.local` TLD, so it cannot read
as a real person. Stage 1.5 should replace it with the operator's own identity
once GitHub auth exists, because pushed commits should carry their name.

A second problem surfaced while fixing the first: `SandboxState` starts `Ready`
from the marker file alone, so `setup()` is never called again for an existing
sandbox and a new provisioning step would silently never reach installs in the
wild. Added a versioned marker and `ensureCurrent()`, called from
`startSession`, which backfills missing steps rather than forcing a ~350 MB
re-provision. Verified on a sandbox provisioned by the previous build:
`backfilled sandbox setup to v2`, marker `alpine=3.21.3 abi=arm64-v8a setup=2`.

### Passed

| # | Claim | Evidence |
|---|---|---|
| 16 | The sandbox gets a git identity | `git config --global --list` inside proot returns `user.name=Pi-Filling Agent`, `user.email=agent@pi-filling.local` |
| 17 | The backfill reaches already-provisioned sandboxes | Marker went `alpine=… abi=…` → `… setup=2` on a sandbox created by the previous build, with the log line above |
| 18 | **The agent edits and commits on device** | From a prompt typed in the app: `65e952d Add greeting function and update README`, 3 files / 16 insertions, working tree clean |
| 19 | Commits carry the configured author, not an invented one | `git log -1 --format="%an <%ae>"` → `Pi-Filling Agent <agent@pi-filling.local>` |
| 20 | The agent no longer mutates the operator's repo config | `git config --local --list \| grep -c user` → `0`, where the pre-fix run wrote two entries |
| 21 | `memory.md` works on device | The agent created and committed `memory.md` unprompted, recording what it had done — the ARCHITECTURE "Bet 2" behaviour, previously only exercised off-device |

The failure took roughly ten agent turns; the fixed path took one.

### Not proved

- **Anthropic still has never made a live call from the device.** Every device run
  so far has used `openai-completions`.
- **No push.** The agent commits locally; `git push`, GitHub auth and credential
  handling (Stage 1.5) are untouched.
- Hardware-binding of the stored key remains unproven.

### Notes

- The model created `greeting.py` when the prompt asked for `hello.sh`, and wrote
  Python rather than shell. That is model behaviour, not app behaviour, and is
  recorded so a later reader does not mistake it for a defect.
- `safe.directory=*` is set alongside the identity. proot maps the repo owner to
  root, and git's ownership check would otherwise reject the working tree.

## 2026-08-26 — end-to-end agent run from the app

Same device. Provider selection wired through Layer 1 so the wrapper's
`--provider` can be driven from the UI.

### A third bug: the agent's answer never reached the screen

The transcript builder read the streamed chunk from
`assistantMessageEvent.text`, falling back to a top-level `data.delta`.
The wrapper emits neither. The real shape is:

```jsonc
{"event":"message_update","data":{"assistantMessageEvent":{"type":"text_delta","delta":"..."}}}
```

so no assistant text was ever appended — the agent worked and the UI showed
only `▸ bash`, `▸ read`, `— done —`. Fixed by reading `delta` from
`assistantMessageEvent` and gating on `type == "text_delta"`. The type check is
load-bearing: `thinking_delta` carries a `delta` too, and reading it
unconditionally would splice the model's reasoning into the visible reply.

### Passed

| # | Claim | Evidence |
|---|---|---|
| 12 | The wrapper runs under proot on the phone against a live provider | `wrapper_ready {"provider":"ollama","model":"gpt-oss:120b","repoPath":"/root/repo"}`, then a `read` tool call returning the file and the correct answer |
| 13 | Credentials are stored per provider | Saving under Ollama wrote `ollama_api_key`; the Anthropic slot stayed empty. Selecting Anthropic re-prompts for a key while Ollama's remains usable |
| 14 | The provider chooser drives the wrapper | Selecting **Ollama Cloud** relabels the field and its hint, and `startWrapper` emits `--provider ollama`, confirmed by `wrapper_ready (provider=ollama …)` in the app's log |
| 15 | **Full round trip from the UI** | Typed a prompt in the app; the agent called `bash` and `read`, and the transcript rendered the answer containing the file's contents. Every layer participated: Compose UI → `AgentController` → proot → Alpine → Node wrapper → provider → tools → back |

### Not proved

- **Anthropic has still never made a live call from the device.** Claim 12 exercises
  `openai-completions`; the provider v1 actually ships against is unexercised on
  hardware.
- No git write path was exercised — the agent read a file but did not edit or
  commit, so the `bash`-driven git flow that V1_SPEC describes is untested on device.
- Hardware-binding of the stored key remains unproven (see the first run below).

### Notes

- **Provider selection is not persisted.** On relaunch the app resets to Anthropic
  and asks for a key, even with an Ollama key stored. Re-selecting Ollama picks the
  stored credential straight up and skips entry, so it is a papercut rather than a
  defect — but it should probably follow the key into storage.
- The model's phrasing varied between runs (bare `4271` once, a prose answer with
  the content quoted the next). That is model behaviour, not app behaviour.
- `uiautomator` truncates long text nodes, so the first read of the transcript
  looked like a partial answer. The full node attribute carried the whole reply.

## 2026-08-26 — proot and the Linux sandbox

Same device and build lineage as the run below, with `proot-bootstrap/build-proot.sh`
run for the first time (NDK r29 stable, `29.0.14206865`) and its output bundled
into the APK.

### Two bugs found, both of which made the sandbox unstartable on any device

**1. `libproot.so` could not link — talloc SONAME vs Android packaging.**

```
CANNOT LINK EXECUTABLE ".../libproot.so":
library "libtalloc.so.2" not found: needed by main executable
```

`libtalloc.so` carries SONAME `libtalloc.so.2` and `libproot.so` records that as
its `NEEDED` entry, but an APK only extracts entries matching `lib*.so`, so the
packaged file is necessarily `libtalloc.so` — a name the linker never asks for.
`build-proot.sh` copies the library without touching the SONAME, and nothing in
Layer 1 bridged it. Fixed by staging a SONAME-correct copy into app-private
storage (`LinuxSandboxManager.stageNativeLibs`) and putting that directory on
proot's library search path. The fix is deliberately **not** in
`build-proot.sh`, which `proot-bootstrap/VENDORED.md` requires stay
byte-identical to Kai's.

**2. The rootfs extractor never applied the archived executable bit.**

```
apk update failed: proot error: '/bin/sh' is not executable
```

`RootfsDownloader` read the tar header's name, size, type and linkname but not
its **mode** (offset 100, 8 bytes octal). Java creates files non-executable, so
every binary in the rootfs landed as `-rw-------`. `/bin/sh` is a symlink to
busybox, and busybox was the file that needed `+x`. Fixed by parsing the mode and
applying owner-execute when any execute bit is set.

Neither bug is reachable from CI. Both required running on hardware.

### Passed

| # | Claim | Evidence |
|---|---|---|
| 5 | proot cross-compiles for all three ABIs | `build-proot.sh` produced `libproot.so`, `libproot-loader.so`, `libproot-loader32.so`, `libtalloc.so` for arm64-v8a, armeabi-v7a and x86_64 |
| 6 | The binaries ship in the APK and reach the device | All four present under `lib/arm64-v8a/` in the APK and unpacked to the native library dir with exec permission; `primaryCpuAbi=arm64-v8a` |
| 7 | **proot executes on the device** | `libproot.so --version` reports `4dba3afb-dirty` — the pinned commit — and `built-in accelerators: process_vm = yes, seccomp_filter = yes` |
| 8 | The app's own staging fix works | After provisioning, `files/linux-sandbox/nativelib/libtalloc.so.2` exists and proot links. Verified with the manual workaround removed first, so the app's code path is what was exercised |
| 9 | The exec-bit fix works | `rootfs/bin/busybox` is `-rwx------` after re-extraction (was `-rw-------`) |
| 10 | **The sandbox provisions end to end** | `apk update` and `apk add nodejs npm git` both succeed. Inside proot: Alpine `3.21.3`, `aarch64`, Node `v22.23.2`, npm `10.9.1`, git `2.47.3`, `uid=0(root)` from proot's `-0` emulation. ~354 MB on disk |
| 11 | Layer 3 is deployed into Layer 2 | `/root/wrapper` contains `src`, `package.json`, `package-lock.json` and a populated `node_modules` — `npm install` ran inside Alpine on the phone |

Claim 10 matters most: Node 22 is the wrapper's minimum, and it is running on the
phone under proot.

### Not proved

- **No agent session has been started from the app.** The UI reaches its session
  screen, but no prompt has been sent and the wrapper has not been launched by
  `startWrapper`. Layers 1–3 are individually verified; the end-to-end path is not.
- The app hard-codes Anthropic: `startWrapper` passes no `--provider`, so the
  wrapper's `ollama` provider is unreachable from the app.
- Hardware-binding of the stored key is still unproven (see the run below).

### Notes

- The device clock was corrected by the owner between the two runs; timestamps in
  this section are accurate.
- The UI gates the sandbox screen behind a saved API key (`!hasKey -> ApiKeyEntry`),
  so provisioning — which needs no key — cannot be reached without entering one.
  A deliberately fake key was used and removed afterwards.

## 2026-08-26 — first run on hardware

**Device:** Samsung SM-F946U (Galaxy Z Fold 5), Android 14 (API 34), inner
display 904×2316, three-button navigation (`navigation_mode=0`).
**Build:** `industries.huginn.pifilling`, versionCode 1, versionName `0.1.0-dev`,
debug-signed, from commit `bb1b36f`. APK confirmed to match the source tree at
install time (no source file newer than the artifact).

### Passed

| # | Claim | Evidence |
|---|---|---|
| 1 | The APK installs on an Android 14 device | `adb install --user 0` returned `Success`; `dumpsys package` reports versionCode 1 / `0.1.0-dev`, flags `[DEBUGGABLE HAS_CODE]` |
| 2 | The app launches and renders its Compose UI | `MainActivity` reached first frame (`BLASTBufferQueue … first frame is available`), window focus gained, no exception in the process log. The key-entry screen renders its title, explanatory copy, masked field and **Save key** button |
| 3 | The API-key field masks input | 32-character input rendered as 32 `•` glyphs in the `EditText` node |
| 4 | **The API key is encrypted at rest, not merely stored** | Saved a deliberately fake key. `shared_prefs/pifilling_secure_prefs.xml` appeared (positive control: file count 2 → 3, so a write definitely happened). The stored value is `base64(IV):base64(ciphertext)` with **IV = 12 bytes** (AES-GCM nonce) and **ciphertext = 48 bytes for a 32-byte plaintext**, i.e. exactly plaintext + a 16-byte GCM tag. The plaintext appears nowhere in app-private storage — verified by `grep -rl` and by a `strings` sweep of every file. The ciphertext contains no ASCII run of 6+ characters |

Claim 4 is the one worth having: `SECURITY.md` asserts AES-256-GCM key storage,
and that assertion is now measured rather than trusted.

### Not proved

- **Hardware-binding of the key.** The ciphertext is AES-GCM-shaped, which is
  consistent with `SecureKeyStore` using an AndroidKeyStore key, but this run did
  **not** establish that the key material lives in the TEE/StrongBox rather than
  in software. Proving that needs a key-attestation certificate chain. The
  encryption claim is verified; the *hardware-bound* half of it is not.
- **The Linux sandbox.** The APK ships no `proot` — `build-proot.sh` has not been
  run — so `LinuxSandboxManager` has nothing to exec. Nothing in the sandbox,
  rootfs-extraction or `ProotExecutor` path has been exercised on a device.
- **Any agent run.** No prompt has been sent from the app. Layers 2 and 3 are
  untested on hardware; the wrapper's own agent loop is verified only off-device.
- **Gesture back.** The device is on three-button navigation
  (`navigation_mode=0`), so there is no gesture-back to exercise, and synthetic
  edge swipes do not reach the system gesture layer. Switching navigation mode is
  the owner's setting, not test scaffolding.

### Notes for the next run

- **The device clock was ~3.5 months behind the host** (device 2026-05-10 vs host
  2026-08-26). Log timestamps in this run reflect device time. Expect TLS
  failures that look like application bugs when the app first calls a real API —
  check the clock before debugging certificate errors.
- **A second Android user (id 150, a Secure Folder profile) exists on this
  device.** `pm` commands without `--user 0` fail with
  `SecurityException: Shell does not have permission to access user 150`. Scope
  every `pm`/`dumpsys` invocation to `--user 0`. This app is `installed=false`
  for user 150 and must stay that way.
- `POST_NOTIFICATIONS` was granted by the device owner during this run
  (`granted=true`, flag `USER_SET`), not by the harness.
- **BACK exits the app, not just a dialog.** Dismissing the permission prompt
  with `keyevent 4` popped the task stack to the launcher, even though
  `mInputShown=false` — the keyboard check alone is not sufficient protection.
  Prefer the app's own affordances.

### Cleanup performed

The fake key and its preferences file were removed (`am force-stop`, then
`run-as … rm`), returning app-private storage to its pre-run file set. A
`strings` sweep confirms no residue. The app itself was left installed.
