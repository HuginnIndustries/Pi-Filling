# Build and Deploy

Three independent toolchains meet only at the APK.

## Layer 1 — Gradle

Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, minSdk 26,
Java/jvmTarget 17. Wrapper committed, so a fresh clone builds without a system
Gradle.

**JDK 17 is mandatory and the failure is opaque:** Kotlin 2.0.21 refuses to run
on Java 25 and Gradle reports only the version string. It is neither AGP nor
Gradle, and `jvmToolchain(17)` does not help — the *daemon* JVM must be old
enough. Distros are dropping older JDKs, so an Adoptium JDK 17 is usually needed.

Two generated inputs are wired conditionally:
- `copyWrapperAssets` copies `node-wrapper/{src,package.json,package-lock.json}`
  into a generated assets dir, as a `preBuild` dependency.
- `proot-bootstrap/.out/` is added as a `jniLibs` source dir **only if present**,
  so a UI-only build still works on a machine that has not cross-compiled.

`useLegacyPackaging = true` for jniLibs: the proot ELFs are shipped as `.so` but
are not real shared libraries and must not be compressed or page-aligned away.

## Layer 2 — `build-proot.sh`

Vendored byte-identical from Kai and treated as a black box; re-vendor rather
than patch. Needs NDK **r29 stable** (F-Droid requirement). Pins termux/proot at
a commit and talloc 2.4.3. Emits four `.so` per ABI for arm64-v8a, armeabi-v7a
and x86_64.

`distributionSha256Sum` is **not** pinned in `gradle-wrapper.properties` — an
outstanding supply-chain follow-up.

## Layer 3 — npm

Exact-pinned dependencies, `npm ci` everywhere, `engine-strict=true` enforcing
the Node 22 floor. A Dockerfile reproduces the Alpine/musl runtime for tests.

## CI — six GitHub Actions jobs

wrapper lint + hermetic tests; dependency audit via an allowlisted gate rather
than a bare threshold; Alpine/musl Docker run; spike API-contract + hermetic
driver; `android · assembleDebug` on JDK 17 uploading the APK; shellcheck.

Coverage gap: no release/minified build and no instrumented tests, so R8
behaviour is unverified.
