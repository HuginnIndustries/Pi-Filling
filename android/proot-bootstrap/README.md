# proot-bootstrap

Cross-compile the proot binaries the Android app needs to run an Alpine
Linux sandbox inside its proot. This is Layer 2's foundation: without
these `.so` artifacts in the APK's `jniLibs/`, the app can't start the
sandbox.

The actual cross-compile script is **vendored from
[Kai](https://github.com/TheAmericanMaker/Kai)**; see
[`VENDORED.md`](./VENDORED.md) for what was copied and why.

## What this produces

After a successful build you'll have a `.out/` directory next to this
README, with one subdirectory per ABI:

```
.out/
├── arm64-v8a/
│   ├── libproot.so
│   ├── libproot-loader.so
│   ├── libproot-loader32.so
│   └── libtalloc.so
├── armeabi-v7a/
│   ├── libproot.so
│   ├── libproot-loader.so
│   └── libtalloc.so
└── x86_64/
    ├── libproot.so
    ├── libproot-loader.so
    ├── libproot-loader32.so
    └── libtalloc.so
```

These get bundled into the Android APK under `app/src/main/jniLibs/<abi>/`
once Stage 1.2 (the Android app skeleton) lands. The Android runtime
unpacks them into `applicationInfo.nativeLibraryDir/` automatically; our
sandbox manager invokes them from there.

## Prerequisites

| | |
|---|---|
| **Android NDK** | r29.x (stable; F-Droid requires non-beta). Either set `ANDROID_NDK_HOME` to the NDK install dir, or have it under `$ANDROID_HOME/ndk/29.*` and the script will find it. |
| **Python 3** | talloc's WAF build system needs it. Any recent Python 3 works. |
| **Build tools** | `git`, `curl`, `make`. |
| **Network access** | `github.com` (proot source) and `samba.org` (talloc tarball). |
| **Disk** | ~1 GB for sources, build trees, and outputs. |
| **Time** | ~5-15 minutes on a modern laptop, longer on first run (cold clone + download). Subsequent runs are incremental. |

## Quickstart

```sh
# From this directory (android/proot-bootstrap/):
./build-proot.sh
# Outputs: .out/<abi>/lib*.so for arm64-v8a, armeabi-v7a, x86_64.

# To force a clean rebuild:
./build-proot.sh --clean
./build-proot.sh
```

`.out/` and the internal build cache (`.build-native/`) are gitignored.

## Installing the NDK

If you don't already have it:

**Android Studio / sdkmanager:**
```sh
sdkmanager --install "ndk;29.0.13599879"   # or whatever r29 is current
```

**Manual download:**
- https://developer.android.com/ndk/downloads — pick r29 (linux/macos/windows).
- Extract somewhere, then `export ANDROID_NDK_HOME=/path/to/android-ndk-r29`.

## What gets built

Each ABI gets four artifacts:

- **`libproot.so`** — the proot binary, renamed `lib*.so` so Android's APK
  extractor unpacks it into the app's native library directory at install
  time.
- **`libproot-loader.so`** — proot's loader for the matching architecture.
- **`libproot-loader32.so`** — for 64-bit ABIs only. proot can launch
  32-bit guests, and that path needs a loader linked for the 32-bit ELF.
  NDK clang doesn't do `-m32`, so we build this with the matching 32-bit
  toolchain (`armeabi-v7a` for `arm64-v8a`, `x86` for `x86_64`).
- **`libtalloc.so`** — proot's only runtime dependency.

## How this layer plugs into the rest of the project

```
Stage 1.2 (Android app, not yet built):
  app/src/main/jniLibs/<abi>/lib*.so       ← copied from .out/<abi>/
  ↓
  AndroidApp.context.applicationInfo.nativeLibraryDir
  ↓
  LinuxSandboxManager invokes libproot.so via Runtime.exec
  ↓
  proot mounts Alpine rootfs, runs Node + git inside
  ↓
  Node loads our wrapper (../../node-wrapper/) and talks to Layer 1
  via JSONL on stdio.
```

When Stage 1.2 lands, the Android app's gradle will either invoke
`build-proot.sh` directly as a pre-build step, or expect the `.out/`
artifacts to already exist. (Final decision is a Stage 1.2 detail.)

## What's verified vs. what isn't, as of this commit

| | |
|---|---|
| Syntax (`bash -n`) | ✅ clean |
| Diff vs upstream `build-proot.sh` | ✅ only `OUTPUT_DIR` + header comment changed |
| `find_ndk` picks NDK from `ANDROID_HOME/ndk/29.*` | ✅ tested with mock layout |
| Host-tag auto-detection | ✅ resolves `linux-x86_64` |
| proot clone + checkout at pinned commit | ✅ resolves from github.com |
| Full end-to-end cross-compile producing `.so` artifacts | ⏳ **not run in this dev sandbox** — the egress proxy blocks `samba.org` (talloc) and `dl.google.com` (NDK). Run on a normal dev machine to complete this verification. |

## Troubleshooting

- **`ERROR: Android NDK r29 not found`** — install the NDK or set
  `ANDROID_NDK_HOME` to the install path.
- **`WARNING: NDK r29-betaN is a pre-release version`** — F-Droid won't
  ship pre-release NDK builds. For local dev it's fine; for release
  builds switch to a stable r29.
- **talloc configure hangs** — usually the WAF cross-answers got out of
  sync with the talloc version. Check `.build-native/build-talloc-<abi>/`
  logs; the file is regenerated each build from a literal heredoc in the
  script.
- **`.comment` section warnings** — harmless. The objcopy step strips
  them post-build for F-Droid reproducibility.

## See also

- [`VENDORED.md`](./VENDORED.md) — what was vendored, with which changes.
- [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) — how Layer 2 fits
  into the overall design.
- [`../../ROADMAP.md`](../../ROADMAP.md) — Stage 1.2 timeline.
- Kai's source: https://github.com/TheAmericanMaker/Kai
- proot upstream: https://github.com/termux/proot
- talloc: https://talloc.samba.org/
