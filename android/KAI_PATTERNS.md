# Kai patterns — reference for the Pi-Filling Android scaffold

The `android/app/` scaffold **ports patterns** (not verbatim code) from the
open-source [Kai](https://github.com/TheAmericanMaker/Kai) Android project
(Apache-2.0). This file records the Kai specifics the scaffold is based on, so a
Stage-1.2c implementer can cross-check against upstream and re-vendor when Kai
moves. Items are tagged **[KAI]** (confirmed from the Kai repo) or **[BP]**
(current Android best-practice used where this scaffold diverges from Kai — e.g.
Kai is Compose-Multiplatform; this scaffold is single-module Android-only).

## Toolchain

- **[KAI]** Kai: AGP 9.x, Kotlin 2.3.x, compileSdk/targetSdk 36, **minSdk 26**,
  Compose Multiplatform plugin (not the AndroidX BOM), Koin DI.
- **[BP]** This scaffold: single `:app` module, AGP 8.7.3 / Kotlin 2.0.21 /
  compileSdk 35 / **minSdk 26**, AndroidX Compose BOM, no DI framework (lazy
  singletons on `Application`). Conservative-stable so a fresh build just works;
  bump deliberately toward Kai's versions.

## Foreground service — `DaemonService` **[KAI]**

- Extends `Service`; `onStartCommand` returns `START_STICKY`; `onBind` null.
- `onCreate` builds an `IMPORTANCE_LOW` notification channel + ongoing
  notification, then `startForeground(...)` **in try/catch**, `stopSelf()` on
  failure.
- Overrides `onTimeout(startId, fgsType)` (API 34+ dataSync ~6h/24h budget) →
  `stopForeground(REMOVE)` + `stopSelf()` rather than crashing.
- Started via `startForegroundService` (catch `ForegroundServiceStartNotAllowedException`).
- **`MainActivity.onStart()` re-asserts** the service every foregrounding
  (idempotent) to recover from OEM battery-manager kills. (Scaffold:
  `AgentController.reassertDaemonIfActive`.)

## proot invocation — `ProotExecutor` / `LinuxSandboxManager` **[KAI]**

- proot ELF shipped as `libproot.so` under `jniLibs/<abi>/`, executed from
  `applicationInfo.nativeLibraryDir` (files there are exempt from W^X). Loader
  `libproot-loader.so` + `libtalloc.so` sit beside it (+ `libproot-loader32.so`
  for 64-bit ABIs).
- argv: `libproot.so --rootfs=<rootfs> --bind=/dev --bind=/proc --bind=/sys
  --bind=<home>:/root --bind=<tmp>:/tmp -0 -w <workdir> /bin/sh -c <command>`.
- env: `HOME=/root`, a standard `PATH`, `TERM`, `LANG=C.UTF-8`,
  `LD_LIBRARY_PATH=<libDir>`, `PROOT_TMP_DIR=<tmp>`, `PROOT_LOADER=<loader>`.
- Launch with `ProcessBuilder` (working dir = rootfs parent), **stderr NOT merged
  into stdout** (`redirectErrorStream(false)`) — drain both on separate threads.
  One-shot output bounded to 15,000 chars; timeout clamped 1–180 s;
  `destroyForcibly()` on timeout. Result envelope:
  `{success, stdout, stderr, exit_code, timed_out}`.

## Rootfs — `RootfsDownloader` **[KAI]**

- Alpine **3.21.3** minirootfs (branch `v3.21`). Arch map:
  aarch64 / armv7 / x86_64 / x86. (Kai maps armeabi-v7a→armhf; this scaffold uses
  armv7, which matches Android's ARMv7 hard-float ABI.)
- **Six mirrors tried in order** (dl-cdn, edge.kernel.org, halifax.rwth-aachen,
  alpine.ethz.ch, csclub.uwaterloo, tuna.tsinghua) with fallback.
- Hand-rolled tar extractor with a **zip-slip / path-traversal guard** (no system
  `tar` available); writes `/etc/resolv.conf` (Google DNS) + `/etc/apk/repositories`.
- Unpacked into app-private `filesDir/linux-sandbox/rootfs`; temp at `.../tmp`.
- First-run: `apk update` then `apk add`. Kai installs a broad set
  (bash curl wget git jq python3 py3-pip nodejs openssh-client lftp rsync);
  this scaffold narrows to **`apk add --no-cache nodejs npm git`** (note: on
  Alpine 3.21 `npm` is a separate package from `nodejs`).
- State machine: `NotInstalled / Downloading / Extracting / Installing / Ready /
  Error`; setup guards concurrent runs; `reset()` deletes the sandbox dir.

## API key storage **[KAI]/[BP]**

- **[KAI]** Kai uses `dev.spght:encryptedprefs` (a maintained fork of the
  deprecated `androidx.security:security-crypto`) behind `multiplatform-settings`,
  with `AEADBadTagException` recovery (delete+recreate prefs after a backup
  restore moves ciphertext without the hardware key).
- **[BP]** This scaffold avoids the third-party crypto dep and uses the
  **AndroidKeyStore directly** (`SecureKeyStore`): a hardware-bound AES-256-GCM
  key encrypts the API key; only IV+ciphertext land in `SharedPreferences`. Auto
  Backup is disabled for that prefs file (the Keystore key is non-exportable).

## Driving the Node child over stdio **[KAI]/[BP]**

- **[KAI]** `PersistentSandboxShell` drives a long-lived stdio child: separate IO
  readers for stdout/stderr (never one thread — pipe-buffer deadlock), `\n`+flush
  writes, observe exit via `waitFor()`, `destroyForcibly()` on cancel.
- **[BP]** Pi-Filling's wrapper already frames everything as JSONL with id-echoed
  responses + `wrapper_ready`/event pushes (see `../node-wrapper/DESIGN.md`), so
  `WrapperClient` matches responses by `id` and routes `event` lines separately —
  no need for Kai's record-separator sentinel trick.

## Re-vendoring

When Kai's sandbox patterns change materially (new ABI, new proot flags, Alpine
bump), re-read the files below and update both this doc and the scaffold:

- `androidApp/src/main/AndroidManifest.xml`, `.../MainActivity.kt`
- `composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/DaemonService.kt`,
  `DaemonController.android.kt`, `SandboxController.android.kt`
- `.../sandbox/{LinuxSandboxManager,ProotExecutor,RootfsDownloader,PersistentSandboxShell}.kt`
- `.../Platform.android.kt` (encrypted settings)
- The proot cross-compile recipe is already vendored at
  [`proot-bootstrap/`](./proot-bootstrap/) (see its `VENDORED.md`).
