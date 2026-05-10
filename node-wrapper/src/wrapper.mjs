#!/usr/bin/env node
// Pi-Filling Node wrapper.
// See ../DESIGN.md for protocol, responsibilities, and lifecycle contract.

import { Agent } from "@mariozechner/pi-agent-core";
import { getModel } from "@mariozechner/pi-ai";
import { createCodingTools } from "@mariozechner/pi-coding-agent";
import { createInterface } from "node:readline";
import { existsSync, readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { parseArgs } from "node:util";

const PROTOCOL_VERSION = 1;
const DEFAULT_MODEL = "claude-haiku-4-5-20251001";
const DEFAULT_BASE_PROMPT = `You are a coding agent operating inside a single git repository.
You have read, write, edit, and bash tools. The bash tool runs git directly.
You also own a file named memory.md in the repository — read it when relevant, update it when meaningful state changes (decisions, context, todos), and commit your changes alongside any code you write.
Complete the user's task without asking clarifying questions when the intent is reasonably clear, then stop.`;

// ---- logging --------------------------------------------------------------

const LOG_LEVELS = { silent: 0, error: 1, info: 2, debug: 3 };
const logLevel = LOG_LEVELS[process.env.WRAPPER_LOG_LEVEL ?? "info"] ?? LOG_LEVELS.info;
const log = {
  error: (...a) => logLevel >= LOG_LEVELS.error && process.stderr.write(`[wrapper:error] ${a.join(" ")}\n`),
  info:  (...a) => logLevel >= LOG_LEVELS.info  && process.stderr.write(`[wrapper:info]  ${a.join(" ")}\n`),
  debug: (...a) => logLevel >= LOG_LEVELS.debug && process.stderr.write(`[wrapper:debug] ${a.join(" ")}\n`),
};

// ---- exit helpers ---------------------------------------------------------

function fatal(code, message) {
  log.error(message);
  process.exit(code);
}

// ---- arg parsing ----------------------------------------------------------

const args = parseArgs({
  options: {
    repo: { type: "string" },
    model: { type: "string" },
    "system-prompt": { type: "string" },
    help: { type: "boolean", short: "h" },
  },
  strict: true,
});

if (args.values.help) {
  process.stdout.write(
    "Usage: node wrapper.mjs --repo <path> [--model <id>] [--system-prompt <file>]\n" +
    "Env:   ANTHROPIC_API_KEY (required), WRAPPER_LOG_LEVEL (silent|error|info|debug)\n"
  );
  process.exit(0);
}

const apiKey = process.env.ANTHROPIC_API_KEY;
if (!apiKey) fatal(1, "ANTHROPIC_API_KEY is required");

const repoArg = args.values.repo;
if (!repoArg) fatal(1, "--repo is required");

const repoPath = resolve(repoArg);
if (!existsSync(repoPath) || !statSync(repoPath).isDirectory()) {
  fatal(2, `--repo path does not exist or is not a directory: ${repoPath}`);
}

const modelId = args.values.model ?? DEFAULT_MODEL;
const systemPromptOverridePath = args.values["system-prompt"];

// ---- agent construction ---------------------------------------------------

process.chdir(repoPath); // pi-coding-agent's bash tool inherits process.cwd()

const model = getModel("anthropic", modelId);
if (!model) fatal(3, `model not in pi-ai registry: anthropic/${modelId}`);

const basePrompt = systemPromptOverridePath
  ? readFileSync(systemPromptOverridePath, "utf8")
  : DEFAULT_BASE_PROMPT;

const memoryPath = resolve(repoPath, "memory.md");
let composedSystemPrompt = basePrompt;
if (existsSync(memoryPath)) {
  const memoryContents = readFileSync(memoryPath, "utf8");
  composedSystemPrompt =
    `${basePrompt}\n\n<prior_memory>\n${memoryContents}\n</prior_memory>`;
  log.info(`loaded memory.md (${memoryContents.length} chars)`);
} else {
  log.info("no memory.md present; starting clean");
}

const tools = createCodingTools(repoPath);
log.info(`tools: ${tools.map((t) => t.name).join(", ")}`);

const agent = new Agent({
  initialState: {
    systemPrompt: composedSystemPrompt,
    model,
    tools,
  },
  getApiKey: () => apiKey,
});

// ---- protocol I/O ---------------------------------------------------------

let shuttingDown = false;

function send(obj) {
  process.stdout.write(`${JSON.stringify(obj)}\n`);
}

function pushEvent(type, data) {
  send({ event: type, data });
}

function respond(id, result) {
  send({ id, result });
}

function respondError(id, code, message) {
  send({ id, error: { code, message } });
}

// Forward every agent event to stdout, with the small envelope from DESIGN.md.
agent.subscribe((event) => {
  // The signal arg from the loop isn't part of the wire protocol; drop it.
  pushEvent(event.type, event);
});

// ---- request handling -----------------------------------------------------

async function handlePrompt(id, params) {
  if (typeof params?.text !== "string" || params.text.length === 0) {
    return respondError(id, "bad_params", "params.text must be a non-empty string");
  }
  if (agent.state.isStreaming) {
    return respondError(id, "busy", "agent already processing");
  }
  // Don't await — the prompt can run for many seconds. Acknowledge start
  // immediately and let agent_start / agent_end events drive the rest.
  agent.prompt(params.text).catch((err) => {
    log.error(`prompt failed: ${err?.message ?? err}`);
    pushEvent("wrapper_error", {
      phase: "prompt",
      message: err?.message ?? String(err),
    });
  });
  respond(id, { started: true });
}

function handleAbort(id) {
  if (!agent.state.isStreaming) {
    return respond(id, { aborted: false });
  }
  agent.abort();
  respond(id, { aborted: true });
}

function handleState(id) {
  respond(id, {
    isStreaming: agent.state.isStreaming,
    messageCount: agent.state.messages.length,
    pendingToolCalls: Array.from(agent.state.pendingToolCalls),
    errorMessage: agent.state.errorMessage ?? null,
    model: model.id,
    repoPath,
  });
}

async function handleShutdown(id) {
  respond(id, { shuttingDown: true });
  beginShutdown();
}

function beginShutdown() {
  if (shuttingDown) return;
  shuttingDown = true;
  log.info("shutting down");
  if (agent.state.isStreaming) {
    agent.abort();
  }
  // Wait for any in-flight run to settle, then exit cleanly.
  agent.waitForIdle().then(() => process.exit(0));
}

const HANDLERS = {
  prompt: handlePrompt,
  abort: handleAbort,
  state: handleState,
  shutdown: handleShutdown,
};

async function dispatch(line) {
  let req;
  try {
    req = JSON.parse(line);
  } catch (err) {
    log.error(`invalid JSON on stdin: ${err.message}`);
    return;
  }
  const { id, method, params } = req;
  if (typeof id !== "number" || typeof method !== "string") {
    log.error(`malformed request: ${line}`);
    return;
  }
  if (shuttingDown) {
    return respondError(id, "shutting_down", "wrapper is shutting down");
  }
  const handler = HANDLERS[method];
  if (!handler) {
    return respondError(id, "unknown_method", `unknown method: ${method}`);
  }
  try {
    await handler(id, params);
  } catch (err) {
    log.error(`handler ${method} threw: ${err?.message ?? err}`);
    respondError(id, "handler_error", err?.message ?? String(err));
  }
}

// ---- stdin loop -----------------------------------------------------------

const rl = createInterface({ input: process.stdin });
rl.on("line", (line) => {
  if (line.trim().length > 0) dispatch(line);
});
rl.on("close", () => beginShutdown());

// Signal handlers — clean shutdown on SIGINT/SIGTERM too.
process.on("SIGINT", beginShutdown);
process.on("SIGTERM", beginShutdown);

// ---- ready ----------------------------------------------------------------

pushEvent("wrapper_ready", {
  protocolVersion: PROTOCOL_VERSION,
  model: model.id,
  repoPath,
  hasMemory: existsSync(memoryPath),
});
log.info(`wrapper_ready (model=${model.id} repo=${repoPath})`);
