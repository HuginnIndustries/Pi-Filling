# On-device verification

What has actually been run on real hardware, and what has not. CI proves the app
compiles and packages; it cannot prove anything below. Each entry records the
date, the device, and the observed values — so a later reader can tell evidence
from assumption.

Device serials and any device content are deliberately excluded from this file.

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
