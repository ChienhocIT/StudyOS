"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "@/features/auth/provider";
import type { Conversation, Entity, Message } from "@/shared/api/types";
import {
  command,
  initialStream,
  parseEnvelope,
  receiveFrame,
  reconcileHistory,
  type StreamState,
} from "@/shared/ws/protocol";

export function useChat(conversation: Conversation | null) {
  const { api } = useAuth();
  const [state, setState] = useState<StreamState>(initialStream);
  const stateRef = useRef(state);
  const [connection, setConnection] = useState("DISCONNECTED");
  const [error, setError] = useState<Error | null>(null);
  const [hasOlder, setHasOlder] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [reconnectKey, setReconnectKey] = useState(0);
  const olderCursor = useRef<string | null>(null);
  const historyLoader = useRef<(() => Promise<void>) | null>(null);
  const socket = useRef<WebSocket | null>(null);
  const update = useCallback((fn: (value: StreamState) => StreamState) => {
    stateRef.current = fn(stateRef.current);
    setState(stateRef.current);
  }, []);

  useEffect(() => {
    update(initialStream);
    setError(null);
    setHasOlder(false);
    olderCursor.current = null;
    setLoadingOlder(false);
    if (!conversation) {
      setConnection("DISCONNECTED");
      return;
    }
    const id = conversation.id;
    let disposed = false,
      attempts = 0,
      resumeSent = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let heartbeat: ReturnType<typeof setInterval> | undefined;
    const controller = new AbortController();
    let historyInitialized = false;
    const refreshHistory = async () => {
      const page = await api.getWithMeta<Message[]>(
        `/api/v1/conversations/${id}/messages`,
        controller.signal,
      );
      if (!disposed) {
        update((value) => reconcileHistory(value, page.data, true));
        if (!historyInitialized) {
          setHasOlder(page.hasMore);
          olderCursor.current = page.nextCursor;
          historyInitialized = true;
        }
      }
    };
    historyLoader.current = async () => {
      if (!olderCursor.current || disposed) return;
      setLoadingOlder(true);
      try {
        const page = await api.getWithMeta<Message[]>(`/api/v1/conversations/${id}/messages?before=${encodeURIComponent(olderCursor.current)}`, controller.signal);
        if (!disposed) {
          update(value => reconcileHistory(value, page.data, true));
          setHasOlder(page.hasMore);
          olderCursor.current = page.nextCursor;
        }
      } catch (err) { if (!disposed) setError(err instanceof Error ? err : new Error("Không tải được hội thoại cũ.")); }
      finally { if (!disposed) setLoadingOlder(false); }
    };
    const sendResume = () => {
      if (socket.current?.readyState === WebSocket.OPEN) {
        socket.current.send(
          JSON.stringify(
            command("chat.resume", id, {
              lastReceivedSequence: stateRef.current.lastSequence,
            }),
          ),
        );
        resumeSent = true;
      }
    };
    const connect = async () => {
      if (disposed) return;
      setConnection(attempts ? "RECONNECTING" : "CONNECTING");
      try {
        const token = await api.post<Entity<"WsToken">>(
          `/api/v1/conversations/${id}/ws-token`,
          undefined,
          { signal: controller.signal },
        );
        if (disposed) return;
        const wsUrl = new URL(token.websocketUrl, api.baseUrl);
        const trusted = new URL(api.baseUrl);
        if (
          !["ws:", "wss:"].includes(wsUrl.protocol) ||
          wsUrl.host !== trusted.host
        )
          throw new Error("Địa chỉ hội thoại không hợp lệ.");
        wsUrl.searchParams.set("token", token.token);
        const ws = new WebSocket(wsUrl);
        socket.current = ws;
        ws.onopen = () => {
          if (!disposed) setConnection("AUTHENTICATING");
        };
        ws.onmessage = (event) => {
          if (disposed || typeof event.data !== "string") return;
          const frame = parseEnvelope(event.data, id);
          if (!frame) {
            setError(
              new Error(
                "Dữ liệu hội thoại không hợp lệ. Hãy mở lại cuộc trò chuyện.",
              ),
            );
            ws.close(4100);
            return;
          }
          if (frame.type === "session.ready") {
            attempts = 0;
            resumeSent = false;
            setConnection("READY");
            setError(null);
            // Session metadata is not a replay event. Resume from the last applied transport event.
            sendResume();
            clearInterval(heartbeat);
            heartbeat = setInterval(
              () => {
                if (ws.readyState === WebSocket.OPEN)
                  ws.send(JSON.stringify(command("ping", id, {})));
              },
              Math.max(5, Number(frame.payload.heartbeatSeconds) || 25) * 1000,
            );
            return;
          }
          if (frame.type === "error") {
            if (frame.payload.code === "RESUME_BUFFER_EXPIRED") {
              void refreshHistory()
                .then(() => {
                  if (!disposed)
                    update((value) => ({
                      ...value,
                      pending: {},
                      gap: false,
                      lastSequence:
                        Number(frame.payload.lastSequence) ||
                        frame.sequence ||
                        value.lastSequence,
                    }));
                  resumeSent = false;
                })
                .catch((err) => {
                  if (!disposed) setError(err);
                });
            } else {
              setError(
                new Error(
                  String(
                    frame.payload.message || "Hội thoại gặp lỗi. Hãy thử lại.",
                  ),
                ),
              );
              update((value) => ({ ...value, activeRequestId: null }));
            }
            return;
          }
          update((value) => receiveFrame(value, frame));
          if (stateRef.current.gap && !resumeSent) sendResume();
          if (!stateRef.current.gap) resumeSent = false;
          if (["assistant.completed", "assistant.failed"].includes(frame.type))
            void refreshHistory().catch((err) => {
              if (!disposed) setError(err);
            });
        };
        ws.onerror = () => {
          if (!disposed)
            setError(
              new Error(
                "Kết nối hội thoại bị gián đoạn. Đang thử kết nối lại…",
              ),
            );
        };
        ws.onclose = (event) => {
          clearInterval(heartbeat);
          if (disposed) return;
          if ([1000, 1009, 4003, 4008, 4100].includes(event.code)) {
            setConnection("CLOSED");
            return;
          }
          attempts++;
          setConnection("RECONNECTING");
          reconnectTimer = setTimeout(
            () => {
              void connect();
            },
            Math.min(30000, 500 * 2 ** Math.min(attempts, 6)) +
              Math.random() * 500,
          );
        };
      } catch (err) {
        if (!disposed) {
          setError(
            err instanceof Error
              ? err
              : new Error("Không kết nối được hội thoại."),
          );
          setConnection("CLOSED");
        }
      }
    };
    void refreshHistory()
      .then(connect)
      .catch((err) => {
        if (!disposed) {
          setError(err);
          setConnection("CLOSED");
        }
      });
    return () => {
      disposed = true;
      historyLoader.current = null;
      controller.abort();
      clearTimeout(reconnectTimer);
      clearInterval(heartbeat);
      socket.current?.close(1000);
      socket.current = null;
    };
  }, [api, conversation?.id, update, reconnectKey]);

  function send(content: string, sourceIds: string[]) {
    if (
      !conversation ||
      socket.current?.readyState !== WebSocket.OPEN ||
      connection !== "READY" ||
      stateRef.current.activeRequestId
    )
      return false;
    const requestId = crypto.randomUUID();
    const frame = command(
      "chat.message.send",
      conversation.id,
      {
        content,
        mode: conversation.mode,
        sourceIds,
        clientContext: { activeSourceId: null },
      },
      requestId,
    );
    // Retry never silently resends a turn with a different request id.
    socket.current.send(JSON.stringify(frame));
    update((value) => ({
      ...value,
      activeRequestId: requestId,
      messages: [
        ...value.messages,
        {
          id: `local-${requestId}`,
          conversationId: conversation.id,
          role: "USER",
          content,
          status: "COMPLETED",
          sequenceNo:
            Math.max(
              0,
              ...value.messages.map((message) => message.sequenceNo),
            ) + 1,
        },
      ],
    }));
    setError(null);
    return true;
  }
  function cancel() {
    if (
      !conversation ||
      !stateRef.current.activeRequestId ||
      socket.current?.readyState !== WebSocket.OPEN
    )
      return;
    const active = stateRef.current.messages.findLast(
      (message) =>
        message.role === "ASSISTANT" && message.status === "STREAMING",
    );
    socket.current.send(
      JSON.stringify(
        command(
          "chat.generation.cancel",
          conversation.id,
          {},
          stateRef.current.activeRequestId,
          active?.id || null,
        ),
      ),
    );
  }
  return { ...state, connection, error, send, cancel, hasOlder, loadingOlder,
    loadOlder: () => historyLoader.current?.(), reconnect: () => setReconnectKey(value => value + 1) };
}
