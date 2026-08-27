/**
 * The host-capability channel: a reverse RPC letting the agent runtime (Layer 3)
 * ask the host that embeds it (Layer 1) to do something it cannot do itself.
 *
 * The agent runs inside a proot rootfs. Node can reach Alpine; Alpine cannot
 * reach Android. So anything phone-native — speaking, notifying, the clipboard,
 * the share sheet, the camera — has to be *asked for* rather than executed. This
 * module is that asking.
 *
 * Wire format, same JSONL framing as the rest of the protocol, opposite
 * direction:
 *
 *     → {"host_request":  {"id":1,"capability":"tts.speak","params":{...}}}
 *     ← {"host_response": {"id":1,"ok":true,"result":{...}}}
 *     ← {"host_response": {"id":1,"ok":false,"error":{"code":"...","message":"..."}}}
 *
 * Three constraints are deliberate, and exist so this could become a pi-level
 * mechanism later rather than staying ours forever (see ARCHITECTURE.md):
 *
 *  1. Capability names are namespaced and **host-neutral** — `tts.speak`, never
 *     `androidSpeak`. Nothing here knows what platform is on the other end.
 *  2. No host specifics appear in the wire format. No intents, no Android types.
 *  3. The transport is injected, not imported. `send` is supplied by the caller,
 *     so pointing this at a future `pi.host.request(...)` is a one-file change —
 *     and it is what makes this testable without a device.
 */

/** A capability request that the host refused or could not complete. */
export class HostCapabilityError extends Error {
  constructor(code, message, capability) {
    super(message);
    this.name = "HostCapabilityError";
    this.code = code;
    this.capability = capability;
  }
}

/** The host never answered within the caller's bound. */
export class HostTimeoutError extends Error {
  constructor(capability, timeoutMs) {
    super(`host did not answer ${capability} within ${timeoutMs}ms`);
    this.name = "HostTimeoutError";
    this.capability = capability;
  }
}

export const DEFAULT_HOST_TIMEOUT_MS = 15_000;

/**
 * @param {object} opts
 * @param {(msg: object) => void} opts.send  Emit one protocol object to the host.
 * @param {number} [opts.timeoutMs]          Default per-request bound.
 */
export function createHostChannel({ send, timeoutMs = DEFAULT_HOST_TIMEOUT_MS }) {
  const pending = new Map();
  let nextId = 1;
  let closed = false;

  function settle(id, fn) {
    const entry = pending.get(id);
    if (!entry) return false;
    pending.delete(id);
    clearTimeout(entry.timer);
    fn(entry);
    return true;
  }

  return {
    /**
     * Ask the host for a capability. Resolves with the host's `result`, or
     * rejects with [HostCapabilityError] / [HostTimeoutError].
     *
     * Callers are expected to degrade rather than fail: a host that does not
     * implement a capability is a normal condition, not an error in the agent.
     */
    request(capability, params = {}, opts = {}) {
      if (closed) {
        return Promise.reject(
          new HostCapabilityError("channel_closed", "host channel is closed", capability),
        );
      }
      const id = nextId++;
      const bound = opts.timeoutMs ?? timeoutMs;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          settle(id, () => reject(new HostTimeoutError(capability, bound)));
        }, bound);
        // Deliberately NOT unref'd. An unref'd timer does not keep the event
        // loop alive, so when this timeout is the only thing pending it never
        // fires — and the request it was meant to bound hangs forever, which is
        // the exact failure the timeout exists to prevent. The cost of holding
        // the loop is bounded by the timeout itself.
        pending.set(id, { resolve, reject, capability, timer });
        send({ host_request: { id, capability, params } });
      });
    },

    /**
     * Offer a parsed inbound object to the channel.
     * @returns {boolean} true if it was a host_response and has been consumed.
     */
    handleMessage(msg) {
      const res = msg?.host_response;
      if (!res || typeof res !== "object") return false;
      const { id, ok, result, error } = res;
      if (typeof id !== "number") return true; // ours in shape, but unusable
      settle(id, ({ resolve, reject, capability }) => {
        if (ok) {
          resolve(result ?? {});
        } else {
          reject(
            new HostCapabilityError(
              error?.code ?? "host_error",
              error?.message ?? "host refused the request",
              capability,
            ),
          );
        }
      });
      return true;
    },

    /** Fail everything outstanding — the host is gone or we are shutting down. */
    close(reason = "host channel closed") {
      closed = true;
      for (const id of [...pending.keys()]) {
        settle(id, ({ reject, capability }) => {
          reject(new HostCapabilityError("channel_closed", reason, capability));
        });
      }
    },

    /** Outstanding request count. Test and diagnostic use. */
    get pendingCount() {
      return pending.size;
    },
  };
}
