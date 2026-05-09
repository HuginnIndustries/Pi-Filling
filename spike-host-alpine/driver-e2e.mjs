// Pi-Filling spike — end-to-end driver.
//
// Goal: prove the v1 acceptance test from V1_SPEC.md (minus push) end-to-end:
// "agent reads README.md, makes a one-line edit, commits."
//
// Setup:
//   - Creates a throwaway git repo in a temp dir.
//   - Seeds it with an initial README + initial commit.
//
// Run:
//   - Constructs Agent with claude-haiku-4-5 + createCodingTools(repoPath).
//   - Subscribes to agent events to log tool calls live.
//   - Prompts the agent to add a line and commit.
//   - Awaits completion (no streaming UI; the loop runs to natural stop).
//
// Verify:
//   - README contains the new line.
//   - HEAD commit differs from base.
//   - Agent stopped naturally (stopReason "stop"), not aborted/errored.
//
// Cost: ~$0.02-$0.10 per run depending on how many tool turns the agent
// takes. claude-haiku-4-5 is the cheap model.
//
// Reads ANTHROPIC_API_KEY from env. Exits non-zero if missing or if any
// verification fails.

import { Agent } from "@mariozechner/pi-agent-core";
import { getModel } from "@mariozechner/pi-ai";
import { createCodingTools } from "@mariozechner/pi-coding-agent";
import { execSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const apiKey = process.env.ANTHROPIC_API_KEY;
if (!apiKey) {
  console.error("ANTHROPIC_API_KEY not set in env");
  process.exit(1);
}

// --- 1. Throwaway repo ---
const repoPath = mkdtempSync(join(tmpdir(), "pi-e2e-"));
const sh = (cmd) => execSync(cmd, { cwd: repoPath, stdio: "pipe" }).toString().trim();

sh("git init -q -b main");
sh("git config user.email agent@spike.local");
sh("git config user.name 'Pi Spike Agent'");
// Disable any inherited commit-signing config so this throwaway repo can
// commit without hitting an external signing server.
sh("git config commit.gpgsign false");
sh("git config tag.gpgsign false");

const initialReadme = "# Test Repo\n\nA throwaway repo for the Pi-Filling end-to-end spike.\n";
writeFileSync(join(repoPath, "README.md"), initialReadme);
sh("git add README.md");
sh('git commit -q -m "initial commit"');
const baseCommitSha = sh("git rev-parse HEAD");

console.error(`[setup] repo: ${repoPath}`);
console.error(`[setup] base commit: ${baseCommitSha.slice(0, 8)}`);

// --- 2. Agent ---
const model = getModel("anthropic", "claude-haiku-4-5-20251001");
if (!model) {
  console.error("model claude-haiku-4-5-20251001 not in pi-ai registry");
  process.exit(1);
}

const tools = createCodingTools(repoPath);
console.error(`[setup] tools: ${tools.map((t) => t.name).join(", ")}`);

const SYSTEM_PROMPT = `You are a coding agent operating inside the git repository at ${repoPath}.
Use the provided tools (read, write, edit, bash) to complete the user's task.
The bash tool runs commands inside the repository working tree, so commands like \`git status\`, \`git add\`, \`git commit\` work directly.
Complete the task without asking clarifying questions, then stop.`;

const agent = new Agent({
  initialState: {
    systemPrompt: SYSTEM_PROMPT,
    model,
    tools,
  },
  getApiKey: () => apiKey,
});

// Live tool-call log to stderr so stdout stays parseable JSON.
let toolCallCount = 0;
let assistantTurns = 0;
agent.subscribe((event) => {
  if (event.type === "turn_start") {
    assistantTurns += 1;
  }
  if (event.type === "tool_execution_start") {
    toolCallCount += 1;
    const argsPreview = JSON.stringify(event.args ?? {}).slice(0, 120);
    console.error(`[tool ${toolCallCount}] ${event.toolName}  ${argsPreview}`);
  }
  if (event.type === "tool_execution_end" && event.isError) {
    console.error(`[tool ${toolCallCount}]   ↑ errored`);
  }
});

// --- 3. Prompt + safety stop ---
const TASK =
  "Append a new line to README.md that reads exactly:\n" +
  "  Spike pass: end-to-end agent loop verified.\n" +
  "Then commit the change with the message 'spike: e2e verification'.";

console.error(`[prompt] ${TASK.replace(/\n/g, " ")}`);

const t0 = Date.now();
await agent.prompt(TASK);
const elapsedMs = Date.now() - t0;

// --- 4. Verify ---
const updatedReadme = readFileSync(join(repoPath, "README.md"), "utf8");
const headSha = sh("git rev-parse HEAD");
const headLog = sh("git log -1 --format=%s");
const last = agent.state.messages[agent.state.messages.length - 1];
const finalStopReason = last?.stopReason ?? null;

const readmeContainsLine = updatedReadme.includes(
  "Spike pass: end-to-end agent loop verified",
);
const newCommitMade = headSha !== baseCommitSha;
const stoppedNaturally = finalStopReason === "stop";

const result = {
  pass: readmeContainsLine && newCommitMade && stoppedNaturally,
  elapsed_ms: elapsedMs,
  assistant_turns: assistantTurns,
  tool_calls: toolCallCount,
  readme_updated: readmeContainsLine,
  new_commit_made: newCommitMade,
  base_commit: baseCommitSha.slice(0, 12),
  head_commit: headSha.slice(0, 12),
  head_commit_message: headLog,
  final_stop_reason: finalStopReason,
};

console.log(JSON.stringify(result, null, 2));
process.exit(result.pass ? 0 : 1);
