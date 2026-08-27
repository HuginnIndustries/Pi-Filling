/**
 * Voice tools, vendored from TheAmericanMaker/pi-termux-android-voice
 * (`extensions/android-tts.ts`, MIT) and adapted for Pi-Filling.
 *
 * What carried over unchanged: the shape of the surface. Two assistant-callable
 * tools — one to speak a specific thing, one to change how and whether the agent
 * speaks — with the same guidance about not changing voice mode unless the user
 * asked. That design was already validated in real use on Termux.
 *
 * What changed, and why:
 *
 *  - **Transport.** The original shells out to `termux-tts-speak`, which works
 *    because Termux ships Termux:API binaries into the same filesystem. Our
 *    agent runs inside a proot rootfs with no bridge to Android, so speaking is
 *    a host capability request instead. That project's own architecture notes
 *    name "a small Android companion app/service that calls Android
 *    TextToSpeech.stop()" as the fix for problems it could not solve from
 *    Termux; Layer 1 is that app.
 *  - **Names.** `android_tts_speak` became `voice_speak`. Nothing on this side
 *    should know or care which platform is listening — the same constraint that
 *    keeps capability names host-neutral.
 *  - **Slash commands are gone.** `/voice-auto`, `/say` and friends were TUI
 *    affordances. Pi-Filling has a Compose UI, so those belong to Layer 1 as
 *    controls, not here as text commands.
 *  - **Auto-speak state lives in the host.** The original persisted it to
 *    `~/.pi/agent/android-tts-settings.json`. Here the host owns it: it survives
 *    sandbox rebuilds, and speaking aloud is a device-level preference rather
 *    than a per-workspace one.
 */

import { HostCapabilityError, HostTimeoutError } from "../host-channel.mjs";

const SPEAK = "tts.speak";
const CONFIG = "tts.config";

/**
 * Turn a failed capability request into a tool result the agent can act on.
 *
 * Degrading is the point: a host with no speech is a normal condition, not an
 * agent error. Saying so plainly stops the model from retrying a capability that
 * is never going to appear.
 */
function describeFailure(err, what) {
  if (err instanceof HostTimeoutError) {
    return `Could not ${what}: the host did not respond in time. Continue without speech.`;
  }
  if (err instanceof HostCapabilityError) {
    if (err.code === "unsupported_capability") {
      return `Could not ${what}: this host has no speech support. Continue without speech and do not try again this session.`;
    }
    if (err.code === "not_permitted") {
      return `Could not ${what}: speaking aloud is turned off by the user. Respect that and continue in text.`;
    }
    return `Could not ${what}: ${err.message}`;
  }
  return `Could not ${what}: ${err?.message ?? err}`;
}

const ok = (text, details) => ({ content: [{ type: "text", text }], details });

/**
 * Build the voice tools against a host channel.
 * @param {{request: (cap: string, params?: object) => Promise<object>}} host
 */
export function createVoiceTools(host) {
  return [
    {
      name: "voice_speak",
      label: "Speak Aloud",
      description:
        "Speak text aloud on the user's device. Use only when the user explicitly asks for something to be said out loud.",
      parameters: {
        type: "object",
        required: ["text"],
        properties: {
          text: { type: "string", description: "Text to speak aloud" },
          rate: { type: "number", description: "Speech rate, e.g. 1.0" },
          pitch: { type: "number", description: "Speech pitch, e.g. 1.0" },
        },
      },
      async execute(_toolCallId, params) {
        const text = String(params?.text ?? "").trim();
        if (!text) return ok("Nothing to speak: no text given.");
        try {
          const res = await host.request(SPEAK, {
            text,
            rate: params?.rate,
            pitch: params?.pitch,
          });
          return ok("Spoken aloud.", { chars: text.length, ...res });
        } catch (err) {
          return ok(describeFailure(err, "speak that aloud"));
        }
      },
    },
    {
      name: "voice_config",
      label: "Voice Settings",
      description:
        "Turn spoken replies on or off and adjust speech rate and pitch. Change these only when the user asks for a voice or speech mode change.",
      parameters: {
        type: "object",
        properties: {
          autoSpeak: {
            type: "boolean",
            description: "Whether assistant replies should be spoken automatically",
          },
          rate: { type: "number", description: "Speech rate, e.g. 1.0" },
          pitch: { type: "number", description: "Speech pitch, e.g. 1.0" },
        },
      },
      async execute(_toolCallId, params) {
        try {
          const res = await host.request(CONFIG, {
            autoSpeak: params?.autoSpeak,
            rate: params?.rate,
            pitch: params?.pitch,
          });
          const state = res?.autoSpeak ? "on" : "off";
          const rate = res?.rate ?? 1.0;
          const pitch = res?.pitch ?? 1.0;
          return ok(`Voice settings saved. Auto-speak is ${state}, rate ${rate}, pitch ${pitch}.`, res);
        } catch (err) {
          return ok(describeFailure(err, "change the voice settings"));
        }
      },
    },
  ];
}
