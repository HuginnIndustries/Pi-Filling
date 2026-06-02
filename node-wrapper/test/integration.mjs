// Integration tests for node-wrapper. Spawns the wrapper as a subprocess via the
// shared harness, drives it over stdio, and verifies behavior against a real
// Anthropic call.
//
// Reads ANTHROPIC_API_KEY from env. If not set, the test skips itself with a
// clear message rather than failing — same convention as the spike drivers.
//
// Usage:
//   ANTHROPIC_API_KEY=... node test/integration.mjs   (or: npm test)
//
// Cost on a successful run: ~$0.02-$0.10 on claude-haiku-4-5.

import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execSync } from "node:child_process";
import { test } from "node:test";
import assert from "node:assert/strict";

import { WrapperHarness } from "./harness.mjs";

const apiKey = process.env.ANTHROPIC_API_KEY;
if (!apiKey) {
  console.log("ANTHROPIC_API_KEY not set — skipping integration test.");
  process.exit(0);
}

// Drive the wrapper with the REAL key (withKey:false stops the harness injecting
// its fake one; the explicit env entry supplies the real key) and verbose logs.
function harnessFor(repo) {
  return new WrapperHarness(repo, {
    withKey: false,
    logLevel: "info",
    env: { ANTHROPIC_API_KEY: apiKey },
  });
}

function makeRepo() {
  const path = mkdtempSync(join(tmpdir(), "pi-wrapper-test-"));
  const sh = (cmd) => execSync(cmd, { cwd: path, stdio: "pipe" }).toString().trim();
  sh("git init -q -b main");
  sh("git config user.email test@wrapper.local");
  sh("git config user.name 'Wrapper Test'");
  sh("git config commit.gpgsign false");
  sh("git config tag.gpgsign false");
  writeFileSync(join(path, "README.md"), "# Test Repo\n\nInitial content.\n");
  sh("git add README.md");
  sh('git commit -q -m "initial"');
  return path;
}

test("wrapper emits wrapper_ready before accepting requests", async () => {
  const repo = makeRepo();
  const h = harnessFor(repo);
  try {
    const ready = await h.waitForEvent((e) => e.event === "wrapper_ready");
    assert.equal(ready.data.protocolVersion, 1);
    assert.equal(ready.data.repoPath, repo);
    assert.equal(ready.data.hasMemory, false);
    assert.match(ready.data.model, /^claude-/);
  } finally {
    await h.dispose();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("state RPC reflects fresh agent", async () => {
  const repo = makeRepo();
  const h = harnessFor(repo);
  try {
    await h.waitForEvent((e) => e.event === "wrapper_ready");
    const resp = await h.call("state");
    assert.deepEqual(resp.result, {
      isStreaming: false,
      messageCount: 0,
      pendingToolCalls: [],
      errorMessage: null,
      model: "claude-haiku-4-5-20251001",
      repoPath: repo,
    });
  } finally {
    await h.dispose();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("prompt + agent_end verifies real edit and commit, with sane event ordering", async () => {
  const repo = makeRepo();
  const h = harnessFor(repo);
  try {
    await h.waitForEvent((e) => e.event === "wrapper_ready");

    const baseSha = execSync("git rev-parse HEAD", { cwd: repo }).toString().trim();

    const promptResp = await h.call("prompt", {
      text:
        "Append a new line to README.md that reads exactly: " +
        "'Wrapper integration test passed.' Then commit with message " +
        "'wrapper: integration test'.",
    });
    assert.equal(promptResp.result.started, true);

    // agent_end can take 5–15 s on a real call. 60 s ceiling is generous.
    const end = await h.waitForEvent((e) => e.event === "agent_end", { timeoutMs: 60_000 });
    assert.ok(end);

    const headSha = execSync("git rev-parse HEAD", { cwd: repo }).toString().trim();
    const headMsg = execSync("git log -1 --format=%s", { cwd: repo }).toString().trim();
    const readme = readFileSync(join(repo, "README.md"), "utf8");

    assert.notEqual(headSha, baseSha, "expected a new commit");
    assert.match(headMsg, /wrapper/i);
    assert.match(readme, /Wrapper integration test passed/);

    // Event-ordering contract (DESIGN.md): wrapper_ready exactly once and first;
    // within the run, agent_start precedes agent_end.
    const types = h.events.map((e) => e.event);
    assert.equal(types.filter((t) => t === "wrapper_ready").length, 1);
    assert.equal(types[0], "wrapper_ready");
    assert.ok(types.indexOf("agent_start") !== -1, "saw agent_start");
    assert.ok(
      types.indexOf("agent_start") < types.lastIndexOf("agent_end"),
      "agent_start precedes agent_end",
    );

    const stateResp = await h.call("state");
    assert.equal(stateResp.result.isStreaming, false);
    assert.ok(stateResp.result.messageCount > 0);
  } finally {
    await h.dispose();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("abort during a real run settles with stopReason aborted", async () => {
  const repo = makeRepo();
  const h = harnessFor(repo);
  try {
    await h.waitForEvent((e) => e.event === "wrapper_ready");

    let textDeltasSeen = 0;
    h.onEvent((evt) => {
      if (evt.event === "message_update" && evt.data?.assistantMessageEvent?.type === "text_delta") {
        textDeltasSeen += 1;
      }
    });

    const promptResp = await h.call("prompt", {
      text:
        "Do NOT call any tools. Reply in plain text only. " +
        "Write a 200-word essay about why musl libc is well-suited " +
        "for embedded Linux runtimes. Begin immediately.",
    });
    assert.equal(promptResp.result.started, true);

    // Wait until streaming has demonstrably started.
    const t0 = Date.now();
    while (textDeltasSeen < 3 && Date.now() - t0 < 10_000) {
      await new Promise((r) => setTimeout(r, 50));
    }
    assert.ok(textDeltasSeen >= 3, `expected streaming to start, saw ${textDeltasSeen} deltas`);

    const abortResp = await h.call("abort");
    assert.equal(abortResp.result.aborted, true);

    const end = await h.waitForEvent((e) => e.event === "agent_end", { timeoutMs: 5_000 });
    const last = end.data.messages?.[end.data.messages.length - 1];
    assert.equal(last?.stopReason, "aborted");
  } finally {
    await h.dispose();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("memory.md is loaded into system prompt when present", async () => {
  const repo = makeRepo();
  const memoryContent = "## context\nThe magic phrase for this test is BANANA-7.";
  writeFileSync(join(repo, "memory.md"), memoryContent);
  execSync("git add memory.md && git commit -q -m 'add memory'", { cwd: repo });

  const h = harnessFor(repo);
  try {
    const ready = await h.waitForEvent((e) => e.event === "wrapper_ready");
    assert.equal(ready.data.hasMemory, true);

    await h.call("prompt", {
      text:
        "What is the magic phrase mentioned in your prior memory? " +
        "Reply with just the phrase, no other text. Do not call any tools.",
    });

    const end = await h.waitForEvent((e) => e.event === "agent_end", { timeoutMs: 30_000 });
    const text = (end.data.messages ?? [])
      .flatMap((m) => (Array.isArray(m.content) ? m.content : []))
      .filter((c) => c.type === "text")
      .map((c) => c.text)
      .join("\n");
    assert.match(text, /BANANA-7/);
  } finally {
    await h.dispose();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("second prompt while first is in flight is rejected with busy", async () => {
  const repo = makeRepo();
  const h = harnessFor(repo);
  try {
    await h.waitForEvent((e) => e.event === "wrapper_ready");
    const first = await h.call("prompt", { text: "Count to 50, one per line." });
    assert.equal(first.result.started, true);
    // Don't wait for end; immediately try a second.
    const second = await h.call("prompt", { text: "ignored" });
    assert.equal(second.error?.code, "busy");
    await h.call("abort");
    await h.waitForEvent((e) => e.event === "agent_end", { timeoutMs: 5_000 });
  } finally {
    await h.dispose();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("stdin close during a live run aborts and exits 0", async () => {
  const repo = makeRepo();
  const h = harnessFor(repo);
  try {
    await h.waitForEvent((e) => e.event === "wrapper_ready");
    await h.call("prompt", { text: "Count slowly to 200, one number per line." });
    // Let streaming get going, then close stdin (not abort) — the clean-shutdown
    // path must abort the in-flight run and exit 0.
    await h.waitForEvent((e) => e.event === "message_update" || e.event === "agent_start", {
      timeoutMs: 15_000,
    });
    h.closeStdin();
    const code = await h.waitForExit();
    assert.equal(code, 0);
    const ended = h.events.find((e) => e.event === "agent_end");
    if (ended) {
      const last = ended.data.messages?.[ended.data.messages.length - 1];
      assert.equal(last?.stopReason, "aborted");
    }
  } finally {
    rmSync(repo, { recursive: true, force: true });
  }
});

test("shutdown method exits cleanly", async () => {
  const repo = makeRepo();
  const h = harnessFor(repo);
  try {
    await h.waitForEvent((e) => e.event === "wrapper_ready");
    const resp = await h.call("shutdown");
    assert.equal(resp.result.shuttingDown, true);
    const code = await h.waitForExit();
    assert.equal(code, 0);
  } finally {
    rmSync(repo, { recursive: true, force: true });
  }
});
