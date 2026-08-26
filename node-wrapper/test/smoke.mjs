// Hermetic smoke tests for the node-wrapper protocol layer.
//
// Unlike integration.mjs — which needs a real ANTHROPIC_API_KEY and makes paid
// Anthropic calls — this exercises only the paths that never touch the network:
// startup, the wrapper_ready handshake, state, memory.md detection, bad params,
// unknown methods, idle abort, and clean shutdown. It runs anywhere: no API
// key, no Docker, no git required.
//
// The wrapper is spawned with a deliberately fake ANTHROPIC_API_KEY (see
// harness.mjs) so it boots past its startup check; no LLM request is ever made.
//
// Performance note: the wrapper's first import of pi-coding-agent is the slow
// part (~1s native, much longer over /mnt/c). So we boot the wrapper ONCE per
// describe block (via `before`) and run every read-only assertion against that
// single process. Two boots total: one without memory.md, one with.
//
// Usage:  node --test test/smoke.mjs   (or just `npm test`)

import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, test, before, after } from "node:test";
import assert from "node:assert/strict";

import { WrapperHarness, BOOT_TIMEOUT_MS } from "./harness.mjs";

const HOOK_TIMEOUT_MS = BOOT_TIMEOUT_MS + 30_000;

function makeDir({ withMemory = false } = {}) {
  const path = mkdtempSync(join(tmpdir(), "pi-wrapper-smoke-"));
  if (withMemory) writeFileSync(join(path, "memory.md"), "## ctx\nhello\n");
  return path;
}

function cleanup(dir) {
  // maxRetries guards against the Windows quirk where the dir handle isn't
  // released the instant the child process exits.
  rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}

describe("wrapper protocol — no memory.md", () => {
  let dir;
  let h;

  before(
    async () => {
      dir = makeDir();
      h = new WrapperHarness(dir);
      await h.waitForReady();
    },
    { timeout: HOOK_TIMEOUT_MS },
  );

  after(async () => {
    if (h) await h.dispose();
    if (dir) cleanup(dir);
  });

  test("wrapper_ready announces protocol v1, repo path, and no memory", () => {
    const ready = h.ready;
    assert.ok(ready, "wrapper_ready event was emitted");
    assert.equal(ready.data.protocolVersion, 1);
    assert.equal(ready.data.repoPath, dir);
    assert.equal(ready.data.hasMemory, false);
    assert.match(ready.data.model, /^claude-/);
    // The default provider stays anthropic; V1_SPEC scopes v1 to it.
    assert.equal(ready.data.provider, "anthropic");
  });

  test("wrapper_ready is the first event and emitted exactly once", () => {
    // DESIGN.md guarantees wrapper_ready exactly once, before any request.
    const readyEvents = h.events.filter((e) => e.event === "wrapper_ready");
    assert.equal(readyEvents.length, 1);
    assert.equal(h.events[0].event, "wrapper_ready");
  });

  test("state RPC returns the fresh-agent snapshot", async () => {
    const resp = await h.call("state");
    assert.deepEqual(resp.result, {
      isStreaming: false,
      messageCount: 0,
      pendingToolCalls: [],
      errorMessage: null,
      model: "claude-haiku-4-5-20251001",
      repoPath: dir,
    });
  });

  test("prompt with empty text is rejected with bad_params", async () => {
    const resp = await h.call("prompt", { text: "" });
    assert.equal(resp.error?.code, "bad_params");
  });

  test("prompt with no params is rejected with bad_params", async () => {
    const resp = await h.call("prompt");
    assert.equal(resp.error?.code, "bad_params");
  });

  test("unknown method is rejected with unknown_method", async () => {
    const resp = await h.call("frobnicate", {});
    assert.equal(resp.error?.code, "unknown_method");
  });

  test("abort while idle returns aborted:false", async () => {
    const resp = await h.call("abort");
    assert.equal(resp.result.aborted, false);
  });
});

describe("wrapper protocol — memory.md present, then clean shutdown", () => {
  let dir;
  let h;

  before(
    async () => {
      dir = makeDir({ withMemory: true });
      h = new WrapperHarness(dir);
      await h.waitForReady();
    },
    { timeout: HOOK_TIMEOUT_MS },
  );

  after(async () => {
    if (h) await h.dispose();
    if (dir) cleanup(dir);
  });

  test("wrapper_ready reports hasMemory:true", () => {
    assert.equal(h.ready.data.hasMemory, true);
  });

  // Runs last in this block: shutdown terminates the process, which is also the
  // natural teardown for the describe.
  test("shutdown acks and exits cleanly with code 0", async () => {
    const resp = await h.call("shutdown");
    assert.equal(resp.result.shuttingDown, true);
    const code = await h.waitForExit();
    assert.equal(code, 0);
  });
});
