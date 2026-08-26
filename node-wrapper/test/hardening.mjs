// Hermetic hardening tests: startup exit-code contract + protocol robustness
// against malformed input. All paths are offline (no API key, Docker, or git).
//
// Two groups:
//   1. Exit codes — spawn the wrapper with broken startup conditions and assert
//      the numeric exit code Layer 1 relies on (DESIGN.md exit-code table).
//   2. Robustness — boot a healthy wrapper and feed it malformed/edge-case input,
//      asserting it neither crashes nor leaves a well-behaved client hanging.

import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, test, before, after } from "node:test";
import assert from "node:assert/strict";

import { WrapperHarness, runWrapper, BOOT_TIMEOUT_MS } from "./harness.mjs";

const HOOK_TIMEOUT_MS = BOOT_TIMEOUT_MS + 30_000;

function makeDir() {
  return mkdtempSync(join(tmpdir(), "pi-wrapper-hard-"));
}
function cleanup(dir) {
  rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}

describe("startup exit codes", () => {
  let dir;
  before(() => {
    dir = makeDir();
  });
  after(() => cleanup(dir));

  test(
    "missing ANTHROPIC_API_KEY exits 1",
    async () => {
      const { code, stderr } = await runWrapper(["--repo", dir], { withKey: false, logLevel: "error" });
      assert.equal(code, 1);
      assert.match(stderr, /ANTHROPIC_API_KEY/);
    },
    { timeout: HOOK_TIMEOUT_MS },
  );

  test(
    "unknown --provider exits 1 and lists the valid ones",
    async () => {
      const { code, stderr } = await runWrapper(["--repo", dir, "--provider", "nope"], { logLevel: "error" });
      assert.equal(code, 1);
      assert.match(stderr, /unknown --provider/);
      assert.match(stderr, /anthropic/);
    },
    { timeout: HOOK_TIMEOUT_MS },
  );

  test(
    "--provider ollama requires OLLAMA_API_KEY, not ANTHROPIC_API_KEY",
    async () => {
      // withKey seeds ANTHROPIC_API_KEY, which must not satisfy a different
      // provider: each provider reads its own env var.
      const { code, stderr } = await runWrapper(["--repo", dir, "--provider", "ollama"], {
        logLevel: "error",
      });
      assert.equal(code, 1);
      assert.match(stderr, /OLLAMA_API_KEY is required/);
    },
    { timeout: HOOK_TIMEOUT_MS },
  );

  test(
    "missing --repo exits 1",
    async () => {
      const { code } = await runWrapper([], { logLevel: "error" });
      assert.equal(code, 1);
    },
    { timeout: HOOK_TIMEOUT_MS },
  );

  test(
    "unknown CLI flag exits 1 (parseArgs caught, not an uncaught throw)",
    async () => {
      const { code, stderr } = await runWrapper(["--repo", dir, "--bogus"], { logLevel: "error" });
      assert.equal(code, 1);
      assert.match(stderr, /bad CLI args/);
    },
    { timeout: HOOK_TIMEOUT_MS },
  );

  test(
    "nonexistent --repo exits 2",
    async () => {
      const { code } = await runWrapper(["--repo", join(dir, "nope-does-not-exist")], { logLevel: "error" });
      assert.equal(code, 2);
    },
    { timeout: HOOK_TIMEOUT_MS },
  );

  test(
    "invalid --model exits 3",
    async () => {
      const { code, stderr } = await runWrapper(["--repo", dir, "--model", "not-a-real-model-xyz"], {
        logLevel: "error",
      });
      assert.equal(code, 3);
      assert.match(stderr, /registry/);
    },
    { timeout: HOOK_TIMEOUT_MS },
  );

  test(
    "unreadable --system-prompt exits 1 (caught, not an uncaught ENOENT)",
    async () => {
      const { code, stderr } = await runWrapper(
        ["--repo", dir, "--system-prompt", join(dir, "no-such-prompt.txt")],
        { logLevel: "error" },
      );
      assert.equal(code, 1);
      assert.match(stderr, /system-prompt/);
    },
    { timeout: HOOK_TIMEOUT_MS },
  );
});

describe("protocol robustness against malformed input", () => {
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

  test("non-JSON line is dropped without crashing; later requests still work", async () => {
    h.writeRaw("this is not json {");
    const resp = await h.call("state");
    assert.equal(resp.result.isStreaming, false);
  });

  test("bare JSON null is dropped without crashing (no destructure throw)", async () => {
    h.writeRaw("null");
    const resp = await h.call("state");
    assert.ok(resp.result, "wrapper still responds after a bare null");
  });

  test("JSON array is dropped without crashing", async () => {
    h.writeRaw("[1,2,3]");
    const resp = await h.call("state");
    assert.ok(resp.result);
  });

  test("valid id with non-string method gets a bad_request error (no hang)", async () => {
    const resp = await h.rawCall({ method: 123 });
    assert.equal(resp.error?.code, "bad_request");
  });

  test("request with non-numeric id is dropped; the stream stays usable", async () => {
    h.writeRaw(JSON.stringify({ id: "not-a-number", method: "state" }));
    const resp = await h.call("state");
    assert.ok(resp.result);
  });

  test(
    "after shutdown, further requests are rejected with shutting_down",
    async () => {
      // shutdown acks, begins teardown, then exits. Before exit, a racing request
      // must be rejected with shutting_down (DESIGN.md: shutdown is sticky).
      const shutdownResp = await h.call("shutdown");
      assert.equal(shutdownResp.result.shuttingDown, true);
      // Fire a follow-up immediately; it should either be rejected with
      // shutting_down or the process exits first (both are correct terminal races).
      try {
        const resp = await h.call("state", undefined, { timeoutMs: 4000 });
        assert.equal(resp.error?.code, "shutting_down");
      } catch (err) {
        // Process exited before answering — also an acceptable outcome.
        assert.match(String(err.message), /exited|timeout/);
      }
      await h.waitForExit();
    },
    { timeout: HOOK_TIMEOUT_MS },
  );
});
