# Amendment — wrapper-death hardening

## What was applied

Plan step A from the rewritten ROADMAP. Four defect-report findings, all in the
same failure path:

- **3.1 (high)** `WrapperClient.call` re-checks `exitCode` *after* registering in
  `pending`, closing the window where a process exit could drain the map between
  the liveness check and the insert, orphaning the caller forever.
- **2.1 (high)** `AgentController.prompt` now catches both
  `WrapperProcessExitedException` and `WrapperTimeoutException`, and the scope
  carries a `CoroutineExceptionHandler` as a backstop.
- **2.2** `call` and `awaitReady` are bounded by `withTimeoutOrNull`, with a new
  `WrapperTimeoutException` distinguishing "died" from "alive but wedged" —
  different situations that deserve different messages.
- **1.1** The event flow now declares `onBufferOverflow = DROP_OLDEST`, making
  behaviour match the comment that already claimed it.

## Evidence

A discriminating test, not an absence of symptoms. The wrapper's node process was
killed and a prompt sent into the dead wrapper. Three observations together:

1. the app pid was identical before and after — no crash or restart;
2. logcat carried `AgentController: wrapper exited during prompt`, proving the
   guarded path executed rather than the exception never arising;
3. the UI showed `Failed: wrapper exited (code 255)` and offered Start session.

Without (2) this would prove nothing: "no crash" is equally consistent with "no
exception".

## What this does not establish

The timeout path is implemented and compiles but has never fired. Reproducing it
needs a wrapper that is alive and unresponsive, which no current harness can
produce — a gap that plan step B (an Android test harness) is the right place to
close.
