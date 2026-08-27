# Conventions

<!--
  Project-level skeleton. Copy this to `.codecarto/CONVENTIONS.md` (one level up from templates/)
  the first time the orchestrator promotes a convention. Then add entries as they accumulate.

  This file holds cross-cutting patterns that have been promoted to project-wide invariants.
  Every new session reads this file and either honors these conventions or documents why it
  diverges.

  This file is **orchestrator-maintained**. Phase executors propose additions in their session
  closeout; the orchestrator promotes them at the phase boundary. In an inline run the same chat
  does both — the rule is about *when* (between phases, deliberately), not about which thread.
-->

Cross-cutting patterns promoted to project-wide invariants. Every session reads this file at start
and either honors these conventions or documents why it diverges.

This file is **orchestrator-maintained**. Phase executors propose additions in their closeout's
"Proposed Conventions" section; the orchestrator promotes them here at the phase boundary — in an
inline run, the same chat changing hats between phases.

## How conventions get added

A new entry lands here when ONE of the following holds:

1. **Three independent sessions** reach for the same pattern (the "lift if it generalizes" rule
   applied to conventions themselves), OR
2. **One session** explicitly promotes a pattern in its closeout report and the orchestrator
   confirms it generalizes, OR
3. **The spec or framework feedback corpus** identifies a project-wide invariant that future
   implementing sessions need to know about.

The orchestrator owns this file. Implementing sessions propose; orchestrator promotes.

## Entry shape

Each convention is a numbered section (`## C<NN>. <Title>`) with three required parts:

- **Body** — the rule itself, in prose. May include a code block for shape contracts.
- **Why:** — the reason the rule exists. Often a past incident or a defect class the rule
  prevents. Future maintainers judging edge cases need to know *why* to judge whether the rule
  applies.
- **How to apply:** — when and where the rule kicks in. Should answer "is this case in scope?"

Optional:
- **Current implementers:** — files/modules that already follow the rule. Useful as worked examples.
- **Source:** — the closeout entry where the orchestrator promoted this convention.

---

## C01. Layer numbering is a shared vocabulary

The Android app is **Layer 1**, the proot/Alpine sandbox is **Layer 2**, and the
Node wrapper is **Layer 3**. Use those numbers; do not introduce parallel names
("the app", "the container", "the agent host") in new docs or code comments.

**Why:** The numbering is already used consistently across ARCHITECTURE.md,
V1_SPEC.md, KDoc and commit messages. Its value is entirely in being unambiguous
across a three-language boundary, which a second vocabulary destroys.

**How to apply:** Any new doc, KDoc, log line or commit message that refers to
one of the three tiers. Naming a component is fine ("LinuxSandboxManager"); a
competing tier name is not.

**Current implementers:** ARCHITECTURE.md, V1_SPEC.md, LinuxSandboxManager KDoc,
node-wrapper/DESIGN.md.

**Source:** closeouts/2026-08-27-architecture.md

---

## C02. Cross-boundary contracts are mirrored by hand and change in pairs

The provider table, the RPC method names, the error codes and the wrapper's exit
codes each exist in **both** Kotlin and JavaScript with no generator between
them. Treat either side's change as a single edit spanning both files.

**Why:** Nothing enforces agreement, so drift does not fail the build — it fails
at run time, on a device, usually as a confusing symptom rather than a clear
error. `AgentProvider.kt` and `PROVIDERS` in `wrapper.mjs` are the live example:
the wrapper rejects an unknown `--provider`, and each provider reads only its own
key variable.

**How to apply:** Editing `AgentProvider.kt`, `Protocol.kt`, the `PROVIDERS`
table, the `fatal()` exit codes, or the RPC dispatch. Ask "what is the other half
of this contract, and did I change it?"

**Current implementers:** `sandbox/AgentProvider.kt` ⇄ `PROVIDERS` in
`node-wrapper/src/wrapper.mjs`; `wrapper/Protocol.kt` ⇄ `node-wrapper/DESIGN.md`.

**Source:** closeouts/2026-08-27-architecture.md

---

## C03. Vendored code is re-vendored, not patched

`android/proot-bootstrap/build-proot.sh` is byte-identical to Kai's apart from
output paths. Fixes for our platform go in our own layer.

**Why:** Each line of that script encodes a hard-won build detail (32-bit loader
built separately, `-Wl,-N` to stop lld padding, pre-filled WAF cross-answers).
Local patches make the next re-vendor a merge instead of a copy. The libtalloc
SONAME mismatch is the worked example: Android's packaging rule is *our*
constraint, so the fix went into `LinuxSandboxManager`, not the script.

**How to apply:** Any change that would edit a vendored file. Upgrade by
re-vendoring from upstream and updating VENDORED.md instead.

**Current implementers:** `LinuxSandboxManager.stageNativeLibs()`.

**Source:** closeouts/2026-08-27-architecture.md

---

<!-- Repeat the C<NN> block for each convention. Number sequentially. -->

## Pending proposals

Staged by completion from each phase handoff's `proposed_conventions`. The orchestrator promotes an entry into a numbered convention above (or removes it with a note) at the phase boundary — see GUIDE.md §Roles.

_None pending. All three proposals from the architecture phase were promoted to C01–C03 on 2026-08-27._
