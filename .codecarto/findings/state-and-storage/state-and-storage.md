# State and Storage

## Credentials

`shared_prefs/pifilling_secure_prefs.xml`, one entry per provider keyed
`<provider>_api_key` (`anthropic_api_key` retains its historical name so existing
installs keep their key).

Value format: `base64(IV) ":" base64(ciphertext)`, AES-256-GCM under an
AndroidKeyStore key aliased `pifilling_master`.

Verified on hardware: 12-byte IV, ciphertext exactly plaintext + 16-byte GCM tag,
plaintext absent from all app-private storage. What is **not** established is
hardware-binding — proving the key lives in the TEE needs a key-attestation
chain.

At run time the key is passed to the wrapper as `<PROVIDER>_API_KEY` at spawn.
The wrapper captures it into a closure and deletes **every** known provider key
from `process.env`, so the agent's `bash` tool children never see any credential.

## Sandbox

`files/linux-sandbox/`
- `rootfs/` — Alpine 3.21.3, ~354 MB after `nodejs npm git bash`
- `tmp/` — `PROOT_TMP_DIR`, also Node's compile cache
- `nativelib/` — staged `libtalloc.so.2`, present because an APK can only ship
  `lib*.so` while the ELF records the versioned SONAME
- `.setup-complete` — `alpine=<v> abi=<abi> setup=<n>`; the setup version drives
  backfill

## Agent memory

`memory.md` in the *user's* repository — git-tracked and therefore synced across
devices, which is exactly why it is treated as untrusted input: folded into the
system prompt as reference data inside `<prior_memory>` with closing-delimiter
breakout neutralised.

Confirmed on device that the agent creates and commits it unprompted.

## Ephemeral

Wrapper stderr is forwarded to logcat under the `wrapper` tag. Wrapper
diagnostics echo CLI arguments, so anything typed into a field that becomes an
argument can reach the device log — recorded in SECURITY.md.

## Not stored

Provider selection is **not** persisted; the app resets to Anthropic on relaunch
even when another provider's key exists.
