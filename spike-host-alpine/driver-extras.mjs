// Pi-Filling spike driver — extras pass.
//
// Adds two checks beyond driver.mjs:
//   Q4. Does pi-coding-agent install on Alpine/musl, and does
//       createCodingTools(cwd) return a usable AgentTool[] without
//       crashing on optional native deps (clipboard / photon-node)?
//   Q3-real. Does Agent.abort() during a real Anthropic streaming call
//       actually close the underlying HTTP/SSE connection (closes the
//       caveat from driver.mjs's mock-streamFn Q3 result)?
//
// Reads ANTHROPIC_API_KEY from process.env. If the key is missing the
// real-LLM check is skipped and reported as such — Q4 still runs.
//
// Output is JSON to stdout. We never print the API key or the full
// transcript (the SDK may include the key in error messages).

import { Agent } from "@earendil-works/pi-agent-core";
import { getModel } from "@earendil-works/pi-ai";

const results = {
  environment: {
    node: process.version,
    platform: process.platform,
    arch: process.arch,
  },
};

// --- Q4: pi-coding-agent createCodingTools ---
{
  const t0 = Date.now();
  const out = {
    pass: false,
    import_ms: null,
    create_ms: null,
    tool_count: null,
    tool_names: null,
    error: null,
  };
  try {
    const importStart = Date.now();
    const { createCodingTools } = await import("@earendil-works/pi-coding-agent");
    out.import_ms = Date.now() - importStart;

    const createStart = Date.now();
    const tools = createCodingTools(process.cwd());
    out.create_ms = Date.now() - createStart;
    out.tool_count = tools.length;
    out.tool_names = tools.map((t) => t.name).sort();
    out.pass = tools.length > 0 && tools.every((t) => typeof t.execute === "function");
  } catch (err) {
    out.error = err?.message ?? String(err);
  }
  out.total_ms = Date.now() - t0;
  results.q4_create_coding_tools = out;
}

// --- Q3-real: Anthropic streaming + abort ---
{
  const out = {
    skipped: false,
    pass: false,
    elapsed_ms: null,
    final_stop_reason: null,
    text_chars_received_before_abort: 0,
    final_error_message: null,
    text_deltas_seen: 0,
  };

  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    out.skipped = true;
    out.skipped_reason = "ANTHROPIC_API_KEY not set in env";
  } else {
    try {
      const model = getModel("anthropic", "claude-haiku-4-5-20251001");
      if (!model) {
        throw new Error("Model claude-haiku-4-5-20251001 not in registry");
      }

      const agent = new Agent({
        initialState: {
          systemPrompt:
            "You count slowly. Always reply with one integer per line. Never add explanation.",
          model,
        },
        getApiKey: () => apiKey,
      });

      let textDeltasSeen = 0;
      let textCharsAtAbort = 0;
      agent.subscribe((event) => {
        if (event.type === "message_update") {
          const ev = event.assistantMessageEvent;
          if (ev?.type === "text_delta") {
            textDeltasSeen += 1;
            // event.message.content holds the partial; track length cheaply
            const text = (event.message.content ?? [])
              .filter((c) => c.type === "text")
              .map((c) => c.text)
              .join("");
            textCharsAtAbort = text.length;
          }
        }
      });

      const t0 = Date.now();
      const promptPromise = agent.prompt(
        "Count from 1 to 200, one number per line, no other text.",
      );
      // Wait until we've seen at least a few text deltas (proves the SSE
      // stream is open), then abort. Falls back to a hard deadline so we
      // don't hang if the stream never starts.
      const ABORT_AFTER_DELTAS = 5;
      const HARD_DEADLINE_MS = 8000;
      const abortStart = Date.now();
      const tickInterval = setInterval(() => {
        if (textDeltasSeen >= ABORT_AFTER_DELTAS) {
          clearInterval(tickInterval);
          agent.abort();
        } else if (Date.now() - abortStart > HARD_DEADLINE_MS) {
          clearInterval(tickInterval);
          agent.abort();
        }
      }, 50);
      await promptPromise;
      clearInterval(tickInterval);
      out.elapsed_ms = Date.now() - t0;

      const last = agent.state.messages[agent.state.messages.length - 1];
      out.final_stop_reason = last?.stopReason ?? null;
      // Don't echo errorMessage verbatim — it may include URL params with the key.
      out.final_error_message =
        last?.errorMessage ? "(present, not echoed)" : null;
      out.text_deltas_seen = textDeltasSeen;
      out.text_chars_received_before_abort = textCharsAtAbort;
      // Pass = aborted within a few seconds AND we actually saw streaming
      // before the abort (proves we hit the wire and cancelled mid-flight).
      out.pass =
        out.final_stop_reason === "aborted" &&
        out.elapsed_ms < 5000 &&
        textDeltasSeen > 0;
    } catch (err) {
      out.error = err?.message ?? String(err);
    }
  }
  results.q3_real_anthropic_abort = out;
}

console.log(JSON.stringify(results, null, 2));

const allPass =
  results.q4_create_coding_tools.pass &&
  (results.q3_real_anthropic_abort.skipped ||
    results.q3_real_anthropic_abort.pass);
process.exit(allPass ? 0 : 1);
