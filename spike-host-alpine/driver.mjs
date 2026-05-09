// Pi-Filling host-Alpine spike driver.
//
// Answers the three empirical questions from V1_SPEC.md:
//   Q1. Does @mariozechner/pi-agent-core + pi-ai install + import cleanly on Alpine/musl?
//   Q2. Can we inject an Anthropic API key per-call via getApiKey, and is the resolved
//       value actually plumbed into the stream call?
//   Q3. Does Agent.abort() during an in-flight stream surface as a clean "aborted" stop?
//
// We use a custom streamFn (no real LLM call) so the spike is hermetic, fast, and
// doesn't require an API key. The contract we're verifying is the agent loop's,
// not Anthropic's.

import { Agent } from "@mariozechner/pi-agent-core";
import { createAssistantMessageEventStream } from "@mariozechner/pi-ai";

const ZERO_USAGE = {
  input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

const FAUX_MODEL = {
  id: "spike-fake",
  name: "spike-fake",
  api: "faux",
  provider: "anthropic",
  baseUrl: "",
  reasoning: false,
  input: ["text"],
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  contextWindow: 100000,
  maxTokens: 1000,
};

function makeAssistantMessage(text, { stopReason = "stop", errorMessage } = {}) {
  return {
    role: "assistant",
    content: text ? [{ type: "text", text }] : [{ type: "text", text: "" }],
    api: FAUX_MODEL.api,
    provider: FAUX_MODEL.provider,
    model: FAUX_MODEL.id,
    usage: ZERO_USAGE,
    stopReason,
    ...(errorMessage ? { errorMessage } : {}),
    timestamp: Date.now(),
  };
}

async function testQ2() {
  let getApiKeyCalledWith = null;
  let streamFnReceivedKey = null;

  const streamFn = (model, context, options) => {
    streamFnReceivedKey = options.apiKey;
    const stream = createAssistantMessageEventStream();
    const finalMessage = makeAssistantMessage("hi");
    queueMicrotask(() => {
      stream.push({
        type: "start",
        partial: { ...finalMessage, content: [] },
      });
      stream.push({ type: "done", reason: "stop", message: finalMessage });
    });
    return stream;
  };

  const agent = new Agent({
    initialState: { systemPrompt: "spike", model: FAUX_MODEL },
    streamFn,
    getApiKey: async (provider) => {
      getApiKeyCalledWith = provider;
      return "spike-fake-key-abc123";
    },
  });

  await agent.prompt("hello");

  return {
    getApiKey_was_called_with: getApiKeyCalledWith,
    streamFn_observed_apiKey: streamFnReceivedKey,
    pass:
      getApiKeyCalledWith === FAUX_MODEL.provider &&
      streamFnReceivedKey === "spike-fake-key-abc123",
  };
}

async function testQ3() {
  let receivedSignal = null;
  let signalAbortedInStreamFn = false;

  const streamFn = (model, context, options) => {
    receivedSignal = options.signal ?? null;
    const stream = createAssistantMessageEventStream();
    // Hold the stream open. When the agent aborts, react to the signal and
    // emit a protocol-compliant "aborted" error event. That mirrors what a
    // well-behaved real provider would do.
    options.signal?.addEventListener("abort", () => {
      signalAbortedInStreamFn = true;
      stream.push({
        type: "error",
        reason: "aborted",
        error: makeAssistantMessage(null, {
          stopReason: "aborted",
          errorMessage: "user requested abort",
        }),
      });
    });
    return stream;
  };

  const agent = new Agent({
    initialState: { systemPrompt: "spike", model: FAUX_MODEL },
    streamFn,
  });

  const startedAt = Date.now();
  const promptPromise = agent.prompt("never finishes on its own");
  setTimeout(() => agent.abort(), 50);
  await promptPromise;
  const elapsedMs = Date.now() - startedAt;

  const lastMessage = agent.state.messages[agent.state.messages.length - 1];
  return {
    signal_arrived_in_streamFn: receivedSignal !== null,
    signal_aborted_during_stream: signalAbortedInStreamFn,
    final_stop_reason: lastMessage?.stopReason ?? null,
    final_error_message: lastMessage?.errorMessage ?? null,
    elapsed_ms: elapsedMs,
    pass:
      receivedSignal !== null &&
      signalAbortedInStreamFn &&
      lastMessage?.stopReason === "aborted" &&
      elapsedMs < 1000,
  };
}

async function main() {
  const results = {
    environment: {
      node: process.version,
      platform: process.platform,
      arch: process.arch,
      libc: "musl (Alpine 3.21 base image)",
    },
    q1_install_and_import: {
      pass: true,
      note: "Reaching this code proves npm install succeeded on musl and both packages imported cleanly.",
    },
    q2_custom_auth: await testQ2(),
    q3_abort_signal: await testQ3(),
  };

  console.log(JSON.stringify(results, null, 2));

  const allPass =
    results.q1_install_and_import.pass &&
    results.q2_custom_auth.pass &&
    results.q3_abort_signal.pass;
  process.exit(allPass ? 0 : 1);
}

main().catch((err) => {
  console.error("Driver crashed:", err);
  process.exit(2);
});
