// Integration test for node-wrapper. Spawns the wrapper as a subprocess,
// drives it via stdio, and verifies behavior against a real Anthropic call.
//
// Reads ANTHROPIC_API_KEY from env. If not set, the test skips itself with
// a clear message rather than failing — same convention as the spike's
// driver-extras.mjs.
//
// Usage:
//   ANTHROPIC_API_KEY=... node test/integration.mjs
// Or via npm:
//   npm test
//
// Cost on a successful run: ~$0.02-$0.05 on claude-haiku-4-5.

import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";
import { test } from "node:test";
import assert from "node:assert/strict";

const __dirname = dirname(fileURLToPath(import.meta.url));
const WRAPPER = resolve(__dirname, "..", "src", "wrapper.mjs");

const apiKey = process.env.ANTHROPIC_API_KEY;
if (!apiKey) {
  console.log("ANTHROPIC_API_KEY not set — skipping integration test.");
  process.exit(0);
}

// ---- helpers --------------------------------------------------------------

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

class WrapperHarness {
  constructor(repoPath, env = {}) {
    this.proc = spawn(process.execPath, [WRAPPER, "--repo", repoPath], {
      env: { ...process.env, WRAPPER_LOG_LEVEL: "info", ...env },
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.repoPath = repoPath;
    this.outBuf = "";
    this.errBuf = "";
    this.events = [];
    this.responsesById = new Map();
    this.eventListeners = new Set();
    this.nextId = 1;
    this.exitCode = null;
    this.exitPromise = new Promise((resolve) => {
      this.proc.on("exit", (code) => {
        this.exitCode = code;
        resolve(code);
      });
    });
    this.proc.stdout.setEncoding("utf8");
    this.proc.stdout.on("data", (chunk) => this._consumeStdout(chunk));
    this.proc.stderr.setEncoding("utf8");
    this.proc.stderr.on("data", (chunk) => {
      this.errBuf += chunk;
    });
  }

  _consumeStdout(chunk) {
    this.outBuf += chunk;
    let nl;
    while ((nl = this.outBuf.indexOf("\n")) !== -1) {
      const line = this.outBuf.slice(0, nl);
      this.outBuf = this.outBuf.slice(nl + 1);
      if (line.length === 0) continue;
      let msg;
      try { msg = JSON.parse(line); }
      catch { continue; }
      if (msg.event !== undefined) {
        this.events.push(msg);
        for (const fn of this.eventListeners) fn(msg);
      } else if (msg.id !== undefined) {
        this.responsesById.set(msg.id, msg);
      }
    }
  }

  onEvent(fn) {
    this.eventListeners.add(fn);
    return () => this.eventListeners.delete(fn);
  }

  async waitForEvent(predicate, { timeoutMs = 30_000 } = {}) {
    const existing = this.events.find(predicate);
    if (existing) return existing;
    return await new Promise((resolveFn, rejectFn) => {
      const timer = setTimeout(() => {
        unlisten();
        rejectFn(new Error(`waitForEvent timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      const unlisten = this.onEvent((evt) => {
        if (predicate(evt)) {
          clearTimeout(timer);
          unlisten();
          resolveFn(evt);
        }
      });
    });
  }

  async call(method, params) {
    const id = this.nextId++;
    const body = `${JSON.stringify({ id, method, params })}\n`;
    this.proc.stdin.write(body);
    return await this._waitForResponse(id);
  }

  async _waitForResponse(id, timeoutMs = 5_000) {
    const start = Date.now();
    while (!this.responsesById.has(id)) {
      if (Date.now() - start > timeoutMs) {
        throw new Error(`response timeout for id=${id}`);
      }
      await new Promise((r) => setTimeout(r, 25));
    }
    return this.responsesById.get(id);
  }

  closeStdin() {
    this.proc.stdin.end();
  }

  async waitForExit() {
    return this.exitPromise;
  }
}

// ---- tests ----------------------------------------------------------------

test("wrapper emits wrapper_ready before accepting requests", async () => {
  const repo = makeRepo();
  const h = new WrapperHarness(repo);
  try {
    const ready = await h.waitForEvent((e) => e.event === "wrapper_ready");
    assert.equal(ready.data.protocolVersion, 1);
    assert.equal(ready.data.repoPath, repo);
    assert.equal(ready.data.hasMemory, false);
    assert.match(ready.data.model, /^claude-/);
  } finally {
    h.closeStdin();
    await h.waitForExit();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("state RPC reflects fresh agent", async () => {
  const repo = makeRepo();
  const h = new WrapperHarness(repo);
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
    h.closeStdin();
    await h.waitForExit();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("prompt + agent_end + verifies real edit and commit", async () => {
  const repo = makeRepo();
  const h = new WrapperHarness(repo);
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
    const end = await h.waitForEvent(
      (e) => e.event === "agent_end",
      { timeoutMs: 60_000 },
    );
    assert.ok(end);

    const headSha = execSync("git rev-parse HEAD", { cwd: repo }).toString().trim();
    const headMsg = execSync("git log -1 --format=%s", { cwd: repo }).toString().trim();
    const readme = readFileSync(join(repo, "README.md"), "utf8");

    assert.notEqual(headSha, baseSha, "expected a new commit");
    assert.match(headMsg, /wrapper/i);
    assert.match(readme, /Wrapper integration test passed/);

    const stateResp = await h.call("state");
    assert.equal(stateResp.result.isStreaming, false);
    assert.ok(stateResp.result.messageCount > 0);
  } finally {
    h.closeStdin();
    await h.waitForExit();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("abort during a real run settles with stopReason aborted", async () => {
  const repo = makeRepo();
  const h = new WrapperHarness(repo);
  try {
    await h.waitForEvent((e) => e.event === "wrapper_ready");

    let textDeltasSeen = 0;
    h.onEvent((evt) => {
      if (
        evt.event === "message_update" &&
        evt.data?.assistantMessageEvent?.type === "text_delta"
      ) {
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

    const end = await h.waitForEvent(
      (e) => e.event === "agent_end",
      { timeoutMs: 5_000 },
    );
    const last = end.data.messages?.[end.data.messages.length - 1];
    assert.equal(last?.stopReason, "aborted");
  } finally {
    h.closeStdin();
    await h.waitForExit();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("memory.md is loaded into system prompt when present", async () => {
  const repo = makeRepo();
  // Seed memory.md with a fact only the agent can know if it read the file.
  const memoryContent = "## context\nThe magic phrase for this test is BANANA-7.";
  writeFileSync(join(repo, "memory.md"), memoryContent);
  execSync("git add memory.md && git commit -q -m 'add memory'", { cwd: repo });

  const h = new WrapperHarness(repo);
  try {
    const ready = await h.waitForEvent((e) => e.event === "wrapper_ready");
    assert.equal(ready.data.hasMemory, true);

    await h.call("prompt", {
      text:
        "What is the magic phrase mentioned in your prior memory? " +
        "Reply with just the phrase, no other text. Do not call any tools.",
    });

    const end = await h.waitForEvent(
      (e) => e.event === "agent_end",
      { timeoutMs: 30_000 },
    );
    const text = (end.data.messages ?? [])
      .flatMap((m) => (Array.isArray(m.content) ? m.content : []))
      .filter((c) => c.type === "text")
      .map((c) => c.text)
      .join("\n");
    assert.match(text, /BANANA-7/);
  } finally {
    h.closeStdin();
    await h.waitForExit();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("second prompt while first is in flight is rejected with busy", async () => {
  const repo = makeRepo();
  const h = new WrapperHarness(repo);
  try {
    await h.waitForEvent((e) => e.event === "wrapper_ready");
    const first = await h.call("prompt", { text: "Count to 50, one per line." });
    assert.equal(first.result.started, true);
    // Don't wait for end; immediately try a second.
    const second = await h.call("prompt", { text: "ignored" });
    assert.equal(second.error?.code, "busy");
    // Clean up by aborting and waiting for end.
    await h.call("abort");
    await h.waitForEvent((e) => e.event === "agent_end", { timeoutMs: 5_000 });
  } finally {
    h.closeStdin();
    await h.waitForExit();
    rmSync(repo, { recursive: true, force: true });
  }
});

test("shutdown method exits cleanly", async () => {
  const repo = makeRepo();
  const h = new WrapperHarness(repo);
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
