import { describe, expect, it } from "vitest";
import {
  initialStream,
  parseEnvelope,
  receiveFrame,
  reconcileHistory,
  type Envelope,
} from "./protocol";

const frame = (
  sequence: number,
  type: string,
  payload: Record<string, unknown> = {},
): Envelope => ({
  protocolVersion: "1.0",
  type,
  sequence,
  eventId: `event-${sequence}`,
  timestamp: "2026-09-11T00:00:00Z",
  conversationId: "conversation",
  requestId: "request",
  messageId: "message",
  payload,
});

describe("conversation event ordering", () => {
  it("buffers out-of-order tokens and drains them once the gap arrives", () => {
    let state = receiveFrame(initialStream(), frame(1, "assistant.started"));
    state = receiveFrame(
      state,
      frame(3, "assistant.delta", { delta: "world" }),
    );
    expect(state.gap).toBe(true);
    expect(state.messages[0].content).toBe("");
    state = receiveFrame(
      state,
      frame(2, "assistant.delta", { delta: "Hello " }),
    );
    expect(state.messages[0].content).toBe("Hello world");
    expect(state.lastSequence).toBe(3);
    expect(state.gap).toBe(false);
  });
  it("deduplicates replayed events and never revives a completed message", () => {
    let state = receiveFrame(initialStream(), frame(1, "assistant.started"));
    const delta = frame(2, "assistant.delta", { delta: "Evidence" });
    state = receiveFrame(state, delta);
    expect(receiveFrame(state, delta)).toBe(state);
    state = receiveFrame(
      state,
      frame(3, "assistant.completed", {
        status: "COMPLETED",
        groundingStatus: "SUPPORTED",
      }),
    );
    state = receiveFrame(
      state,
      frame(4, "assistant.delta", { delta: " injected later" }),
    );
    expect(state.messages[0].content).toBe("Evidence");
    expect(state.messages[0].status).toBe("COMPLETED");
  });
  it("rejects frames from another conversation or incompatible protocol", () => {
    const event = frame(1, "assistant.started");
    expect(parseEnvelope(JSON.stringify(event), "different")).toBeNull();
    expect(
      parseEnvelope(
        JSON.stringify({ ...event, protocolVersion: "2.0" }),
        "conversation",
      ),
    ).toBeNull();
    expect(
      parseEnvelope(
        JSON.stringify({ ...event, sequence: 0.2 }),
        "conversation",
      ),
    ).toBeNull();
  });
  it("treats durable cancelled history as authoritative", () => {
    let state = receiveFrame(initialStream(), frame(1, "assistant.started"));
    state = receiveFrame(
      state,
      frame(2, "assistant.delta", { delta: "partial" }),
    );
    state = reconcileHistory(state, [
      {
        id: "message",
        conversationId: "conversation",
        role: "ASSISTANT",
        sequenceNo: 2,
        status: "CANCELLED",
        content: "",
      },
    ]);
    expect(state.messages[0].status).toBe("CANCELLED");
    expect(state.messages[0].content).toBe("");
  });
});
