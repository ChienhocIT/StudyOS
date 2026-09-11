import type { Citation, Message } from "@/shared/api/types";

const eventTypes = new Set([
  "session.ready",
  "chat.message.send",
  "chat.generation.cancel",
  "chat.resume",
  "ping",
  "pong",
  "assistant.started",
  "retrieval.completed",
  "assistant.delta",
  "citation.provisional",
  "tool.started",
  "tool.completed",
  "assistant.completed",
  "assistant.failed",
  "usage.updated",
  "error",
]);
export interface Envelope {
  type: string;
  protocolVersion: "1.0";
  eventId: string;
  timestamp: string;
  conversationId: string;
  requestId?: string | null;
  messageId?: string | null;
  traceId?: string | null;
  sequence?: number | null;
  payload: Record<string, unknown>;
}
export interface StreamCitation extends Partial<Citation> {
  key: string;
  sourceId: string;
  provisional?: boolean;
}
export interface StreamMessage extends Omit<Message, "citations"> {
  citations?: StreamCitation[];
  requestId?: string;
  error?: string;
}
export interface StreamState {
  messages: StreamMessage[];
  lastSequence: number;
  seen: string[];
  pending: Record<number, Envelope>;
  gap: boolean;
  activeRequestId: string | null;
}
export const initialStream = (): StreamState => ({
  messages: [],
  lastSequence: 0,
  seen: [],
  pending: {},
  gap: false,
  activeRequestId: null,
});

export function parseEnvelope(
  raw: string,
  conversationId: string,
): Envelope | null {
  if (new TextEncoder().encode(raw).length > 65536) return null;
  try {
    const data: unknown = JSON.parse(raw);
    if (!data || typeof data !== "object") return null;
    const frame = data as Envelope;
    if (
      !eventTypes.has(frame.type) ||
      frame.protocolVersion !== "1.0" ||
      typeof frame.eventId !== "string" ||
      !frame.eventId ||
      typeof frame.timestamp !== "string" ||
      frame.conversationId !== conversationId ||
      !frame.payload ||
      typeof frame.payload !== "object" ||
      Array.isArray(frame.payload)
    )
      return null;
    if (
      frame.sequence != null &&
      (!Number.isSafeInteger(frame.sequence) || frame.sequence < 0)
    )
      return null;
    return frame;
  } catch {
    return null;
  }
}

function applyFrame(state: StreamState, event: Envelope): StreamState {
  const { payload, messageId, requestId } = event;
  let messages = state.messages;
  let activeRequestId = state.activeRequestId;
  if (event.type === "assistant.started" && messageId) {
    activeRequestId = requestId || null;
    if (!messages.some((message) => message.id === messageId))
      messages = [
        ...messages,
        {
          id: messageId,
          conversationId: event.conversationId,
          role: "ASSISTANT",
          content: "",
          status: "STREAMING",
          sequenceNo:
            Math.max(0, ...messages.map((message) => message.sequenceNo)) + 1,
          citations: [],
          requestId: requestId || undefined,
        },
      ];
  }
  if (
    messageId &&
    [
      "assistant.delta",
      "citation.provisional",
      "assistant.completed",
      "assistant.failed",
    ].includes(event.type)
  ) {
    messages = messages.map((message) => {
      if (message.id !== messageId) return message;
      // Terminal messages cannot be revived by late/replayed deltas.
      if (["COMPLETED", "CANCELLED", "FAILED"].includes(message.status))
        return message;
      if (event.type === "assistant.delta" && typeof payload.delta === "string")
        return {
          ...message,
          content: message.content + payload.delta,
          status: "STREAMING",
        };
      if (
        event.type === "citation.provisional" &&
        typeof payload.key === "string" &&
        typeof payload.sourceId === "string"
      ) {
        const citation = {
          ...payload,
          key: payload.key,
          sourceId: payload.sourceId,
          provisional: true,
        } as StreamCitation;
        return {
          ...message,
          citations: [
            ...(message.citations || []).filter(
              (item) => item.key !== citation.key,
            ),
            citation,
          ],
        };
      }
      if (event.type === "assistant.completed") {
        const grounding = ["SUPPORTED", "PARTIAL", "INSUFFICIENT"].includes(
          String(payload.groundingStatus),
        )
          ? (payload.groundingStatus as Message["groundingStatus"])
          : null;
        return {
          ...message,
          status: payload.status === "CANCELLED" ? "CANCELLED" : "COMPLETED",
          groundingStatus: grounding,
          content:
            typeof payload.content === "string"
              ? payload.content
              : message.content,
          // Only durable REST citations are promoted to verified links.
          citations: (message.citations || []).filter(
            (citation) => !citation.provisional,
          ),
        };
      }
      if (event.type === "assistant.failed")
        return {
          ...message,
          status: "FAILED",
          error:
            typeof payload.message === "string"
              ? payload.message
              : "Không thể tạo câu trả lời.",
        };
      return message;
    });
  }
  if (
    ["assistant.completed", "assistant.failed"].includes(event.type) &&
    (!requestId || activeRequestId === requestId)
  )
    activeRequestId = null;
  return { ...state, messages, activeRequestId };
}

/** Buffer gaps and drain in transport order; durable message sequence is independent. */
export function receiveFrame(state: StreamState, event: Envelope): StreamState {
  if (state.seen.includes(event.eventId)) return state;
  if (
    event.sequence == null ||
    ["session.ready", "pong", "error"].includes(event.type)
  )
    return state;
  if (event.sequence <= state.lastSequence) return state;
  if (state.pending[event.sequence]) return state;
  // Bounded buffer: caller recovers using REST when replay is unavailable.
  if (Object.keys(state.pending).length >= 512) return { ...state, gap: true };
  let next: StreamState = {
    ...state,
    pending: { ...state.pending, [event.sequence]: event },
  };
  while (next.pending[next.lastSequence + 1]) {
    const current = next.pending[next.lastSequence + 1];
    const pending = { ...next.pending };
    delete pending[current.sequence!];
    next = applyFrame(
      {
        ...next,
        pending,
        lastSequence: current.sequence!,
        seen: [...next.seen, current.eventId].slice(-2048),
      },
      current,
    );
  }
  return { ...next, gap: Object.keys(next.pending).length > 0 };
}

export function reconcileHistory(
  state: StreamState,
  history: Message[],
  merge = false,
): StreamState {
  const durableMessages: StreamMessage[] = history.map((message) => ({
    ...message,
  }));
  const map = new Map(durableMessages.map((message) => [message.id, message]));
  const messages: StreamMessage[] = [...durableMessages];
  for (const local of state.messages) {
    const durable = map.get(local.id);
    if (!durable && (local.status === "STREAMING" || (merge && !local.id.startsWith("local-")))) messages.push(local);
    if (
      durable &&
      ["PENDING", "STREAMING"].includes(durable.status) &&
      local.content.length > durable.content.length
    ) {
      const index = messages.findIndex((message) => message.id === local.id);
      messages[index] = { ...durable, content: local.content };
    }
  }
  const activeMessage = messages.find(message => message.requestId === state.activeRequestId && message.role === "ASSISTANT");
  return {
    ...state,
    messages: messages.sort((a, b) => a.sequenceNo - b.sequenceNo),
    activeRequestId: activeMessage && ["COMPLETED", "CANCELLED", "FAILED"].includes(activeMessage.status) ? null : state.activeRequestId,
  };
}

export function command(
  type: string,
  conversationId: string,
  payload: Record<string, unknown>,
  requestId: string | null = null,
  messageId: string | null = null,
): Envelope {
  return {
    type,
    protocolVersion: "1.0",
    eventId: crypto.randomUUID(),
    timestamp: new Date().toISOString(),
    conversationId,
    requestId,
    messageId,
    sequence: null,
    payload,
  };
}
