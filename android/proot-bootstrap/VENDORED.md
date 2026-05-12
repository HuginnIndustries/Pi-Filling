# Vendored from Kai

This directory contains code vendored from the
[Kai](https://github.com/TheAmericanMaker/Kai) Android project, under the
Apache-2.0 license. We vendor (rather than depend on) because Kai is an
Android app, not a library — there is no published artifact to import
from.

## What's here

| File | Source | Modifications |
|---|---|---|
| `build-proot.sh` | Kai repo root `build-proot.sh` | Header comment expanded; `OUTPUT_DIR` changed from `androidApp/src/main/jniLibs` (Kai's gradle layout) to `.out` (project-neutral). All build logic is byte-identical. |

## Source commit at vendor time

The Kai branch we vendored from: `claude/setup-codecarto-pipeline-NamtZ`,
which was the head used for the Stage-0 CodeCarto read. The
`build-proot.sh` file is at the repo root in upstream.

Pinned upstream artifact versions inside the script (do not change without
rebenchmarking F-Droid reproducibility):

- proot: termux/proot @ `4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f`
- talloc: `2.4.3` from `samba.org/ftp/talloc/`
- NDK: r29.x (stable required for F-Droid)
- Android API minimum: 26

## Why we keep the script byte-identical

Kai's recipe is the one that:
- Strips `.comment` sections for reproducible builds (Kai issue #91).
- Builds the 32-bit loader separately because NDK clang doesn't support
  `-m32`.
- Uses `-Wl,-N` to prevent lld from padding the 32-bit ELF to gigabytes.
- Pre-fills WAF cross-answers because cross-compilation can't run test
  programs.

Each of those is a one-line difference that, if drifted, costs hours to
re-debug. We treat the script as a black box: when we need to upgrade
NDK or proot or talloc, we re-vendor from Kai rather than patching
locally.

## How to re-vendor when upstream moves

```sh
# From the Pi-Filling repo root, with kai checked out alongside:
diff /path/to/kai/build-proot.sh android/proot-bootstrap/build-proot.sh \
    | less
# Confirm the only diffs are the header comment and OUTPUT_DIR.
# Then apply the upstream changes by hand into our copy and update
# the "Source commit at vendor time" section above.
```

If upstream introduces structural changes (new artifact, new ABI, new
build phase) that affect the contract with Layer 1, update
[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) at the same time.
