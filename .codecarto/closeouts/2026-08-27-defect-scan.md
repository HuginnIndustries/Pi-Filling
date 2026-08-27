# Closeout - defect-scan

## Summary

Thirteen findings, none critical, two high. Both high findings sit on the same
path - what happens when the wrapper process dies - and they compound:
`WrapperClient.call` can register a request after `onProcessExit` has already
drained the pending map, leaving the caller suspended forever; no timeout exists
anywhere to break that hang; and the adjacent call site, `AgentController.prompt`,
is unguarded in a scope with no exception handler, so the same failure surfaces
as an app crash rather than a failed session.

## The finding behind the findings

The Node wrapper handles serialization failure, stdout backpressure, EPIPE,
malformed requests and unknown methods. The Kotlin layer holds both high
findings. That asymmetry is not about care or authorship - it tracks test
coverage exactly. `node-wrapper` has 25 tests; `android/` has none, and has never
had any. Fixing the individual rows without standing up an Android harness leaves
the mechanism that produced them intact.

## Contradiction sweep

One contradiction against completed-phase state: ROADMAP.md still reports 8/8
tests and describes Stage 1.2c as not built or run on a device, both of which the
architecture phase and on-device verification contradict. Recorded as finding
5.2 rather than smoothed over, because a roadmap that misreports finished work
cannot be used to plan the unfinished work.

## What this does not cover

No release build exists, so R8 defects are invisible. No instrumented tests
exist, so lifecycle defects - rotation, process death, background restore - are
unreachable by a static pass. The two concurrency findings are reasoned from the
code rather than reproduced under a race, and are marked `strong inference`
accordingly.

## Decisions Beyond Prompt

- Ran defect-scan rather than lite or full. A development plan needs to know what is broken before it can sequence work, and contracts/protocols phases would deepen understanding without changing the plan's priorities.
