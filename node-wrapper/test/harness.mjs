// Shared test harness for the node-wrapper.
//
// Two entry points:
//   - WrapperHarness: boots the wrapper as a long-lived child and drives it over
//     stdio. Responses are matched to calls via a promise registry (resolved the
//     instant the matching id arrives) rather than a busy-poll loop, so RPC tests
//     are deterministic and not wall-clock-flaky.
//   - runWrapper: spawns the wrapper once with arbitrary argv/env and resolves
//     with its exit code + captured stderr — for startup/exit-code assertions.
//
// All of this is hermetic: a fake ANTHROPIC_API_KEY is injected by default so the
// wrapper boots past its key check without ever making a network call (no prompt
// is sent unless a test sends one).

import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));

export const WRAPPER = resolve(__dirname, "..", "src", "wrapper.mjs");

// Slow cross-filesystem (WSL /mnt/c) module loads can exceed a minute, so the
// boot ceiling is high and overridable. RPC round-trips are cheap once up.
export const BOOT_TIMEOUT_MS = Number(process.env.PI_SMOKE_BOOT_TIMEOUT_MS) || 180_000;
export const RPC_TIMEOUT_MS = 30_000;

export const delay = (ms) => new Promise((r) => setTimeout(r, ms));

function childEnv({ env = {}, logLevel = "silent", withKey = true } = {}) {
  const base = { ...process.env };
  // Force a fake key regardless of the outer env so the suite stays hermetic
  // even on a machine that has a real key exported.
  delete base.ANTHROPIC_API_KEY;
  if (withKey) base.ANTHROPIC_API_KEY = "fake-offline-key";
  return { ...base, WRAPPER_LOG_LEVEL: logLevel, ...env };
}

/**
 * Spawn the wrapper once with explicit argv/env and resolve when it exits.
 * Used for startup / exit-code tests. Returns { code, stderr, stdout }.
 */
export function runWrapper(argv, opts = {}) {
  const proc = spawn(process.execPath, [WRAPPER, ...argv], {
    env: childEnv(opts),
    stdio: ["pipe", "pipe", "pipe"],
  });
  let stderr = "";
  let stdout = "";
  proc.stderr.setEncoding("utf8");
  proc.stdout.setEncoding("utf8");
  proc.stderr.on("data", (c) => (stderr += c));
  proc.stdout.on("data", (c) => (stdout += c));
  // Close stdin so a wrapper that does boot doesn't wait forever for input.
  proc.stdin.end();
  return new Promise((resolveFn) => {
    proc.on("exit", (code) => resolveFn({ code, stderr, stdout }));
  });
}

/** A booted, drivable wrapper process. */
export class WrapperHarness {
  constructor(repoPath, opts = {}) {
    const argv = opts.argv ?? ["--repo", repoPath];
    this.proc = spawn(process.execPath, [WRAPPER, ...argv], {
      env: childEnv(opts),
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.repoPath = repoPath;
    this.outBuf = "";
    this.errBuf = "";
    this.events = [];
    this.responsesById = new Map();
    this.pending = new Map(); // id -> { resolve }
    this.eventListeners = new Set();
    this.nextId = 1;
    this.exitCode = null;
    this.exitPromise = new Promise((res) => {
      this.proc.on("exit", (code) => {
        this.exitCode = code;
        // Fail any in-flight calls so a test never hangs on a dead process.
        for (const [, p] of this.pending) p.reject(new Error(`wrapper exited (code ${code})`));
        this.pending.clear();
        res(code);
      });
    });
    this.proc.stdout.setEncoding("utf8");
    this.proc.stdout.on("data", (c) => this._consume(c));
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
      try {
        msg = JSON.parse(line);
      } catch {
        continue;
      }
      if (msg.event !== undefined) {
        this.events.push(msg);
        for (const fn of this.eventListeners) fn(msg);
      } else if (msg.id !== undefined) {
        this.responsesById.set(msg.id, msg);
        const waiter = this.pending.get(msg.id);
        if (waiter) {
          this.pending.delete(msg.id);
          waiter.resolve(msg);
        }
      }
    }
  }

  onEvent(fn) {
    this.eventListeners.add(fn);
    return () => this.eventListeners.delete(fn);
  }

  get ready() {
    return this.events.find((e) => e.event === "wrapper_ready");
  }

  // Resolve on wrapper_ready; reject fast (don't wait the full ceiling) if the
  // process dies during startup, surfacing captured stderr for diagnosis.
  waitForReady(timeoutMs = BOOT_TIMEOUT_MS) {
    const existing = this.ready;
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
        () =>
          settle(
            rejectFn,
            new Error(`wrapper_ready timed out after ${timeoutMs}ms.\nstderr:\n${this.errBuf}`),
          ),
        timeoutMs,
      );
      const unlisten = this.onEvent((e) => {
        if (e.event === "wrapper_ready") settle(resolveFn, e);
      });
      const onExit = (code) =>
        settle(
          rejectFn,
          new Error(`wrapper exited (code ${code}) before wrapper_ready.\nstderr:\n${this.errBuf}`),
        );
      this.proc.on("exit", onExit);
    });
  }

  waitForEvent(predicate, { timeoutMs = RPC_TIMEOUT_MS } = {}) {
    const existing = this.events.find(predicate);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolveFn, rejectFn) => {
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

  // Promise-based RPC: resolves when the matching id arrives (no poll loop).
  call(method, params, { timeoutMs = RPC_TIMEOUT_MS } = {}) {
    if (this.exitCode !== null) {
      return Promise.reject(new Error(`wrapper already exited (code ${this.exitCode})`));
    }
    const id = this.nextId++;
    const promise = new Promise((resolveFn, rejectFn) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        rejectFn(new Error(`response timeout for id=${id} after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (msg) => {
          clearTimeout(timer);
          resolveFn(msg);
        },
        reject: (err) => {
          clearTimeout(timer);
          rejectFn(err);
        },
      });
    });
    this.proc.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
    return promise;
  }

  /** Write a raw line to stdin (for malformed-input tests). */
  writeRaw(line) {
    this.proc.stdin.write(line.endsWith("\n") ? line : `${line}\n`);
  }

  /**
   * Send a deliberately-shaped request object and await the response by id.
   * Unlike call(), this does not enforce a valid method type, so it can probe
   * malformed-but-id-bearing requests. An id is assigned if absent.
   */
  rawCall(obj, { timeoutMs = RPC_TIMEOUT_MS } = {}) {
    const id = typeof obj.id === "number" ? obj.id : this.nextId++;
    const promise = new Promise((resolveFn, rejectFn) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        rejectFn(new Error(`response timeout for id=${id} after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve: (msg) => {
          clearTimeout(timer);
          resolveFn(msg);
        },
        reject: (err) => {
          clearTimeout(timer);
          rejectFn(err);
        },
      });
    });
    this.writeRaw(JSON.stringify({ id, ...obj }));
    return promise;
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
