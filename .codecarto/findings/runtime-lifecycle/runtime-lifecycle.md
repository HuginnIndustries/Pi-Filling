# Runtime Lifecycle

## Boot

`PiFillingApplication.onCreate` constructs `AppContainer` (manual DI — four lazy
properties, no Hilt/Dagger, KSP absent). `SandboxState` is seeded `Ready` or
`NotInstalled` **from the existence of the marker file alone**, without probing
the sandbox.

That shortcut has a documented consequence: `setup()` is never re-entered once
the marker exists, so a provisioning step added later never reaches existing
installs. Mitigated by a versioned marker (`setup=N`) plus `ensureCurrent()`,
called from `startSession`, which backfills only the missing steps.

## Provisioning (once per install)

1. Verify `libproot.so` is present in the native library dir.
2. Download the Alpine rootfs (mirror fallback), reporting fractional progress.
3. Extract, guarding path traversal and symlink escape, applying tar mode bits.
4. Write `resolv.conf` and `repositories` into the guest.
5. `apk update`, then `apk add --no-cache nodejs npm git bash`.
6. Configure a git identity plus `safe.directory=*`.
7. Copy Layer 3 from APK assets into `/root/wrapper` and `npm install` in-guest.
8. Write the marker.

## Session

`startSession(repoGuestPath, provider, model)`:
decrypt the provider key on `Dispatchers.IO` → `ensureCurrent()` →
start `DaemonService` → `startWrapper` spawns
`node /root/wrapper/src/wrapper.mjs --repo … --provider …` under proot →
`WrapperClient.start()` drains stdout/stderr → `awaitReady()` blocks on
`wrapper_ready` → state becomes `Ready`.

Each command runs through `ProotExecutor`, which rebuilds argv per call:
`--rootfs`, binds for `/dev`, `/proc`, `/sys`, home and tmp, `-0` for uid
emulation, `-w` working dir, then `/bin/sh -c <command>`. The environment is
cleared and rebuilt, never inherited.

## Shutdown

`shutdown` RPC → ack → exit 0. Closing stdin also triggers a clean shutdown;
an in-flight run is aborted, which is why a naive `printf | node …` harness sees
`stopReason: aborted` within milliseconds.

## Background work

`DaemonService` is a `dataSync` foreground service (`START_STICKY`, try/catch
around `startForeground`, `onTimeout` teardown for the API-34 budget, re-assert
on `onStart`) whose only job is keeping the process alive across backgrounding.
Whether it survives real memory pressure is unmeasured.
