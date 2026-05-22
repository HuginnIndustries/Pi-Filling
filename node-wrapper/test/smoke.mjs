// Hermetic smoke test for the node-wrapper protocol layer.
//
// Unlike integration.mjs — which needs a real ANTHROPIC_API_KEY and makes paid
// Anthropic calls — this exercises only the paths that never touch the network:
// startup, the wrapper_ready handshake, state, memory.md detection, bad params,
// unknown methods, idle abort, and clean shutdown. It runs anywhere: no API
// key, no Docker, no git required.
//
// The wrapper is spawned with a deliberately fake ANTHROPIC_API_KEY so it boots
// past its startup check (the key is only ever used once a prompt runs, and we
// never send a valid prompt) — so no LLM request is ever made.
//
// Performance / robustness notes:
//   - The wrapper's first import of pi-coding-agent is the slow part. On a
//     native filesystem it's ~1s; from WSL over /mnt/c it can exceed a minute.
//     So we boot the wrapper ONCE per describe block (via `before`) and run
//     every read-only assertion against that single process, rather than
//     respawning per assertion. Two boots total: one without memory.md, one
//     with — both startup states still get covered.
//   - The startup wait is generous (PI_SMOKE_BOOT_TIMEOUT_MS, default 180s) so
//     a slow cross-filesystem import doesn't trip a false failure. It's a
//     ceiling, not a fixed delay: a fast boot proceeds immediately.
//
// Usage:  node --test test/smoke.mjs   (or just `npm test`)

import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, test, before, after } from "node:test";
import assert from "node:assert/strict";

const __dirname = dirname(fileURLToPath(import.meta.url));
const WRAPPER = resolve(__dirname, "..", "src", "wrapper.mjs");

// Slow cross-filesystem (WSL /mnt/c) module loads can take well over a minute,
// so the boot ceiling is high and overridable. RPC round-trips are cheap once
// the process is up, but stay generous for the same slow-FS reason.
const BOOT_TIMEOUT_MS = Number(process.env.PI_SMOKE_BOOT_TIMEOUT_MS) || 180_000;
const HOOK_TIMEOUT_MS = BOOT_TIMEOUT_MS + 30_000;
const RPC_TIMEOUT_MS = 30_000;

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

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

class Harness {
  constructor(repoPath) {
    this.proc = spawn(process.execPath, [WRAPPER, "--repo", repoPath], {
      // Force a fake key regardless of the outer env so the test stays hermetic
      // even on a machine that has a real key exported.
      env: { ...process.env, ANTHROPIC_API_KEY: "fake-offline-key", WRAPPER_LOG_LEVEL: "silent" },
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
    this.exitPromise = new Promise((res) => {
      this.proc.on("exit", (code) => {
        this.exitCode = code;
        res(code);
      });
    });
    this.proc.stdout.setEncoding("utf8");
    this.proc.stdout.on("data", (c) => this._consume(c));
    // Keep stderr so boot failures are debuggable instead of silent.
    this.proc.stderr.setEncoding("utf8");
    this.proc.stderr.on("data", (c) => {
      this.errBuf += c;
    });
  }

  _consume(chunk) {
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

  // Resolve on wrapper_ready. Fail fast (don't wait the full ceiling) if the
  // process dies during startup, surfacing captured stderr for diagnosis.
  waitForReady(timeoutMs = BOOT_TIMEOUT_MS) {
    const existing = this.events.find((e) => e.event === "wrapper_ready");
    if (existing) return Promise.resolve(existing);
    return new Promise((resolveFn, rejectFn) => {
      let done = false;
      const settle = (fn, arg) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        unlisten();
        this.proc.removeListener("exit", onExit);
        fn(arg);
      };
      const timer = setTimeout(
        () => settle(rejectFn, new Error(`wrapper_ready timed out after ${timeoutMs}ms.\nstderr:\n${this.errBuf}`)),
        timeoutMs,
      );
      const unlisten = this.onEvent((e) => {
        if (e.event === "wrapper_ready") settle(resolveFn, e);
      });
      const onExit = (code) =>
        settle(rejectFn, new Error(`wrapper exited (code ${code}) before wrapper_ready.\nstderr:\n${this.errBuf}`));
      this.proc.on("exit", onExit);
    });
  }

  get ready() {
    return this.events.find((e) => e.event === "wrapper_ready");
  }

  async call(method, params, { timeoutMs = RPC_TIMEOUT_MS } = {}) {
    const id = this.nextId++;
    this.proc.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
    const start = Date.now();
    while (!this.responsesById.has(id)) {
      if (this.exitCode !== null) {
        throw new Error(`wrapper exited (code ${this.exitCode}) before responding to id=${id}`);
      }
      if (Date.now() - start > timeoutMs) {
        throw new Error(`response timeout for id=${id} after ${timeoutMs}ms`);
      }
      await delay(20);
    }
    return this.responsesById.get(id);
  }

  closeStdin() {
    this.proc.stdin.end();
  }

  waitForExit() {
    return this.exitPromise;
  }

  // Graceful teardown with a force-kill backstop so a wedged process can never
  // hang the suite or orphan itself.
  async dispose() {
    if (this.exitCode !== null) return;
    this.closeStdin();
    const outcome = await Promise.race([this.exitPromise, delay(8_000).then(() => "timeout")]);
    if (outcome === "timeout") {
      this.proc.kill();
      await this.exitPromise;
    }
  }
}

describe("wrapper protocol — no memory.md", () => {
  let dir;
  let h;

  before(async () => {
    dir = makeDir();
    h = new Harness(dir);
    await h.waitForReady();
  }, { timeout: HOOK_TIMEOUT_MS });

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

  before(async () => {
    dir = makeDir({ withMemory: true });
    h = new Harness(dir);
    await h.waitForReady();
  }, { timeout: HOOK_TIMEOUT_MS });

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
