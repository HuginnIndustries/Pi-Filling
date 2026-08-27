import { describe, test } from "node:test";
import assert from "node:assert/strict";

import { createHostChannel, HostCapabilityError, HostTimeoutError } from "../src/host-channel.mjs";
import { createVoiceTools } from "../src/tools/voice.mjs";

/**
 * The channel's transport is injected, which is the whole reason these run
 * without a device: `send` is a function, so a test is the host.
 */
function harness({ timeoutMs = 500 } = {}) {
  const sent = [];
  const channel = createHostChannel({ send: (msg) => sent.push(msg), timeoutMs });
  return {
    channel,
    sent,
    last: () => sent[sent.length - 1]?.host_request,
    /** Answer the most recent request as the host would. */
    reply(result) {
      const req = sent[sent.length - 1].host_request;
      channel.handleMessage({ host_response: { id: req.id, ok: true, result } });
    },
    refuse(code, message = "no") {
      const req = sent[sent.length - 1].host_request;
      channel.handleMessage({ host_response: { id: req.id, ok: false, error: { code, message } } });
    },
  };
}

describe("host channel", () => {
  test("a request is emitted host-neutrally and correlated by id", async () => {
    const h = harness();
    const p = h.channel.request("tts.speak", { text: "hi" });
    const req = h.last();
    assert.equal(req.capability, "tts.speak");
    assert.deepEqual(req.params, { text: "hi" });
    assert.equal(typeof req.id, "number");
    // Nothing platform-specific may appear on the wire — that constraint is what
    // keeps this promotable to a pi-level mechanism later.
    assert.doesNotMatch(JSON.stringify(h.sent), /android|termux|intent/i);
    h.reply({ utteranceId: "u1" });
    assert.deepEqual(await p, { utteranceId: "u1" });
  });

  test("concurrent requests resolve to their own responses", async () => {
    const h = harness();
    const a = h.channel.request("tts.speak", { text: "a" });
    const b = h.channel.request("tts.config", { autoSpeak: true });
    const [ra, rb] = [h.sent[0].host_request, h.sent[1].host_request];
    // Answer out of order, which the host is entitled to do.
    h.channel.handleMessage({ host_response: { id: rb.id, ok: true, result: { which: "b" } } });
    h.channel.handleMessage({ host_response: { id: ra.id, ok: true, result: { which: "a" } } });
    assert.deepEqual(await a, { which: "a" });
    assert.deepEqual(await b, { which: "b" });
  });

  test("a refusal rejects with the host's code", async () => {
    const h = harness();
    const p = h.channel.request("tts.speak", { text: "hi" });
    h.refuse("unsupported_capability", "this host cannot speak");
    await assert.rejects(p, (e) => {
      assert.ok(e instanceof HostCapabilityError);
      assert.equal(e.code, "unsupported_capability");
      assert.equal(e.capability, "tts.speak");
      return true;
    });
  });

  test("a silent host times out rather than hanging", async () => {
    const h = harness({ timeoutMs: 60 });
    const p = h.channel.request("tts.speak", { text: "hi" });
    await assert.rejects(p, (e) => e instanceof HostTimeoutError);
    assert.equal(h.channel.pendingCount, 0, "a timed-out request must not leak");
  });

  test("a late response for a timed-out id is ignored, not crashed on", async () => {
    const h = harness({ timeoutMs: 40 });
    const p = h.channel.request("tts.speak", { text: "hi" });
    await assert.rejects(p, (e) => e instanceof HostTimeoutError);
    const req = h.last();
    h.channel.handleMessage({ host_response: { id: req.id, ok: true, result: {} } });
  });

  test("close fails everything outstanding", async () => {
    const h = harness({ timeoutMs: 10_000 });
    const p = h.channel.request("tts.speak", { text: "hi" });
    h.channel.close("wrapper shutting down");
    await assert.rejects(p, (e) => e instanceof HostCapabilityError && e.code === "channel_closed");
  });

  test("non-host messages are left for the request dispatcher", () => {
    const h = harness();
    assert.equal(h.channel.handleMessage({ id: 1, method: "prompt" }), false);
    assert.equal(h.channel.handleMessage(null), false);
    assert.equal(h.channel.handleMessage({ event: "agent_end" }), false);
    // Shaped like ours but unusable: consumed so it cannot fall through and be
    // misread as a request, but nothing is settled.
    assert.equal(h.channel.handleMessage({ host_response: { ok: true } }), true);
  });
});

describe("voice tools", () => {
  const toolsFor = (host) => Object.fromEntries(createVoiceTools(host).map((t) => [t.name, t]));

  test("voice_speak asks the host and reports success", async () => {
    const h = harness();
    const t = toolsFor(h.channel);
    const p = t.voice_speak.execute("call-1", { text: "hello there" });
    assert.equal(h.last().capability, "tts.speak");
    assert.equal(h.last().params.text, "hello there");
    h.reply({ utteranceId: "u1" });
    const res = await p;
    assert.match(res.content[0].text, /Spoken aloud/);
  });

  test("voice_speak degrades instead of failing when the host cannot speak", async () => {
    const h = harness();
    const t = toolsFor(h.channel);
    const p = t.voice_speak.execute("call-1", { text: "hello" });
    h.refuse("unsupported_capability", "no tts here");
    const res = await p;
    // A host without speech is a normal condition. The tool must return a
    // result the model can act on, not throw — and it should say plainly that
    // retrying is pointless.
    assert.match(res.content[0].text, /no speech support/i);
    assert.match(res.content[0].text, /do not try again/i);
  });

  test("voice_speak refuses politely when speech is switched off", async () => {
    const h = harness();
    const t = toolsFor(h.channel);
    const p = t.voice_speak.execute("call-1", { text: "hello" });
    h.refuse("not_permitted", "user disabled speech");
    const res = await p;
    assert.match(res.content[0].text, /turned off by the user/i);
  });

  test("voice_speak does not call the host for empty text", async () => {
    const h = harness();
    const t = toolsFor(h.channel);
    const res = await t.voice_speak.execute("call-1", { text: "   " });
    assert.equal(h.sent.length, 0);
    assert.match(res.content[0].text, /Nothing to speak/);
  });

  test("voice_config reports the state the host actually applied", async () => {
    const h = harness();
    const t = toolsFor(h.channel);
    const p = t.voice_config.execute("call-1", { autoSpeak: true, rate: 1.2 });
    assert.equal(h.last().capability, "tts.config");
    // The host is the authority on the resulting state, not the request.
    h.reply({ autoSpeak: false, rate: 1.2, pitch: 1.0 });
    const res = await p;
    assert.match(res.content[0].text, /Auto-speak is off/);
  });

  test("a timeout degrades rather than throwing out of the tool", async () => {
    const h = harness({ timeoutMs: 40 });
    const t = toolsFor(h.channel);
    const res = await t.voice_speak.execute("call-1", { text: "hello" });
    assert.match(res.content[0].text, /did not respond in time/i);
  });
});
