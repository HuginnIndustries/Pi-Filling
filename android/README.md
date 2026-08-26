# Android app — Layer 1 (Stage 1.2c scaffold)

The Android shell that owns the device side of Pi-Filling: key storage, the
foreground service that keeps the sandbox alive, the proot/Alpine bootstrap, and
the UI that drives the Layer 3 wrapper over its stdio JSONL protocol.

> **Status: scaffold.** This is a structurally-complete, pattern-faithful
> starting point for Stage 1.2c — **not yet built or run on a device.** The
> code follows Kai's verified patterns (see [`KAI_PATTERNS.md`](./KAI_PATTERNS.md))
> and the wrapper contract in [`../node-wrapper/DESIGN.md`](../node-wrapper/DESIGN.md),
> but it has not been compiled here (no Android SDK/JDK in the authoring
> environment). Expect to resolve SDK/version specifics on first build. The
> acceptance test in [`../V1_SPEC.md`](../V1_SPEC.md) is not met until this runs
> end-to-end on hardware.

## Layout

```
android/
├── settings.gradle.kts          Gradle root (module :app)
├── build.gradle.kts             root build (plugin declarations)
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml        version catalog (conservative stable pins)
│   └── wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts          app module; bundles ../node-wrapper into assets
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml   dataSync FGS, permissions, launcher
│       ├── kotlin/industries/huginn/pifilling/
│       │   ├── PiFillingApplication.kt   process-lifetime singletons
│       │   ├── MainActivity.kt           Compose entry + onStart daemon re-assert
│       │   ├── service/DaemonService.kt  foreground service (Kai pattern)
│       │   ├── sandbox/                   Layer 2 control
│       │   │   ├── LinuxSandboxManager.kt  provision + launch wrapper
│       │   │   ├── ProotExecutor.kt        proot exec (one-shot + long-lived)
│       │   │   ├── RootfsDownloader.kt     Alpine 3.21.3 download + tar extract
│       │   │   └── SandboxState.kt
│       │   ├── wrapper/                    Layer 3 client
│       │   │   ├── WrapperClient.kt        stdio JSONL driver
│       │   │   └── Protocol.kt             wire types (mirrors DESIGN.md)
│       │   ├── storage/SecureKeyStore.kt   AndroidKeyStore AES-GCM key storage
│       │   ├── runtime/AgentController.kt  orchestrates sandbox+key+wrapper
│       │   └── ui/                         Compose UI + ViewModel
│       └── res/                            manifest resources, adaptive icon
└── proot-bootstrap/             Layer 2 native build (vendored; Stage 1.2a)
```

## Prerequisites

- **Android Studio** (Ladybug or newer) or a standalone Android SDK + JDK 17.
- **proot native libs.** Run [`proot-bootstrap/build-proot.sh`](./proot-bootstrap/)
  on a machine with the NDK r29 first; it produces `proot-bootstrap/.out/<abi>/lib*.so`,
  which the app's `build.gradle.kts` wires into `jniLibs` automatically when present.
  Without them the app compiles and the UI runs, but the sandbox can't start.

## Building

The Gradle wrapper is committed, so a fresh clone builds with no system Gradle:

```sh
cd android
./gradlew assembleDebug
```

**Requires JDK 17.** Kotlin 2.0.21 cannot run on Java 25, and the failure is
opaque — Kotlin's bundled IntelliJ `JavaVersion.parse` rejects a version string
it predates, so Gradle reports only:

```
* What went wrong:
25.0.4
```

That is not AGP and not Gradle (Gradle 8.10.2 runs on Java 25 fine), which is
what makes it confusing. Note that `jvmToolchain(17)` does **not** rescue you:
it provisions a JDK for *compilation*, while the JVM running the Gradle daemon
is the one that has to be old enough. Current distros are dropping older JDKs —
Fedora 44 packages nothing below 25 — so you may need a JDK 17 from Adoptium.

Keep it out of the repo: `JAVA_HOME` per shell, or `org.gradle.java.home` in
`~/.gradle/gradle.properties`, never the committed `gradle.properties`.

```sh
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

The Android SDK path comes from `android/local.properties` (gitignored), or
`ANDROID_HOME`:

```sh
echo "sdk.dir=$HOME/Android/Sdk" > android/local.properties
```

`copyWrapperAssets` (a `preBuild` dependency) copies `node-wrapper/{src,
package.json,package-lock.json}` into a generated build dir
(`app/build/generated/wrapperAssets/wrapper/`) that is registered as an assets
source — so the wrapper ships at asset path `wrapper/` without polluting the
tracked tree. `node_modules` is **not** bundled — `npm ci --omit=dev` runs inside
Alpine after `apk add nodejs npm git`, against the committed lockfile.

## How a run flows

1. **Key.** User pastes an Anthropic key → `SecureKeyStore` encrypts it with a
   hardware-bound AndroidKeyStore AES-256-GCM key; only ciphertext + IV touch
   disk. (Backup is disabled for the prefs file; the Keystore key is
   non-exportable and doesn't survive restore.)
2. **Provision.** `LinuxSandboxManager.setup()` downloads the Alpine 3.21.3
   minirootfs (mirror fallback), extracts it (zip-slip-guarded), `apk add`s
   `nodejs npm git`, and deploys the wrapper. Progress surfaces via `SandboxState`.
3. **Session.** `AgentController.startSession()` starts `DaemonService`, launches
   `node wrapper.mjs --repo <path>` inside proot with the key supplied **in-memory
   via env at spawn** (never written to the sandbox FS — per ARCHITECTURE.md),
   and waits for the `wrapper_ready` handshake.
4. **Drive.** Prompts/abort/state/shutdown go over stdin as JSONL; responses and
   agent events stream back on stdout (`WrapperClient`). stderr is logs.

## What's deliberately stubbed / next

These are honest gaps a Stage 1.2c → 1.6 implementer must close; they are marked
in code and tracked against the roadmap:

- **GitHub auth + clone/push (Stage 1.5).** The UI takes a repo *path inside the
  sandbox*; cloning a GitHub repo over HTTPS with a PAT and pushing back is not
  wired yet. The agent's `bash` tool can run `git` once a repo + credentials are
  present.
- **Launcher icon** is a placeholder vector (`ic_launcher_foreground.xml`).
- **Compose UI is minimal** — single-session chat. Tool-call/diff rendering,
  cost meter, and run history are Stage 1.4/1.5.
- **Version pins are conservative** (AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35);
  bump deliberately. Kai itself runs newer (see KAI_PATTERNS.md).
- **Partly tested on device.** The app installs, launches and renders its UI on
  a Galaxy Z Fold 5 / Android 14, and key-at-rest encryption is verified — see
  [VERIFICATION.md](./VERIFICATION.md). The sandbox is **not** exercised: the APK
  ships no proot, so nothing in `LinuxSandboxManager`, rootfs extraction or
  `ProotExecutor` has run on hardware, and no agent prompt has been sent from the
  app. No instrumented tests yet.
- **`distributionSha256Sum` is not pinned** in `gradle-wrapper.properties`.
  Worth adding for supply-chain hardening; it needs the checksum Gradle
  publishes alongside the 8.10.2 distribution.

## Security posture

See [`../SECURITY.md`](../SECURITY.md) and ARCHITECTURE.md "Authentication".
Key invariants this scaffold upholds: the API key is encrypted at rest, is
passed to the wrapper only in process env at spawn, and is never written into the
sandbox filesystem or logs. Auto Backup and device-transfer exclude the key
prefs and the sandbox.
