import type { Tokens } from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    public status = 0,
    public code = "NETWORK_ERROR",
    public traceId?: string,
  ) {
    super(message);
  }
}

/** The browser only talks to Core. Access tokens never enter WebSocket URLs. */
export class ApiClient {
  private tokens: Tokens | null = null;
  private refreshFlight: Promise<void> | null = null;
  private epoch = 0;
  constructor(
    public readonly baseUrl: string,
    private onTokens: (tokens: Tokens | null) => void = () => {},
  ) {}
  setTokens(tokens: Tokens | null) {
    this.epoch++;
    this.tokens = tokens;
    this.onTokens(tokens);
  }
  getTokens() {
    return this.tokens;
  }
  private async decode<T>(response: Response): Promise<T> {
    if (
      response.status === 204 ||
      response.headers.get("content-length") === "0"
    )
      return undefined as T;
    const data: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      const error = data as {
        message?: string;
        code?: string;
        traceId?: string;
      } | null;
      throw new ApiError(
        error?.message || `Yêu cầu thất bại (${response.status}).`,
        response.status,
        error?.code,
        error?.traceId,
      );
    }
    return data as T;
  }
  private async refresh() {
    if (!this.refreshFlight) {
      const snapshot = this.epoch;
      const refreshToken = this.tokens?.refreshToken;
      this.refreshFlight = (async () => {
        if (!refreshToken)
          throw new ApiError("Phiên đăng nhập đã hết hạn.", 401);
        const response = await fetch(`${this.baseUrl}/api/v1/auth/refresh`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken }),
          signal: AbortSignal.timeout(20000),
          cache: "no-store",
        });
        const tokens = await this.decode<Tokens>(response);
        if (this.epoch !== snapshot)
          throw new ApiError("Phiên đăng nhập đã thay đổi.", 401);
        this.tokens = tokens;
        this.onTokens(tokens);
      })()
        .catch((error) => {
          if (
            this.epoch === snapshot &&
            error instanceof ApiError &&
            [400, 401, 403].includes(error.status)
          )
            this.setTokens(null);
          throw error;
        })
        .finally(() => {
          this.refreshFlight = null;
        });
    }
    return this.refreshFlight;
  }
  async request<T>(
    path: string,
    options: RequestInit = {},
    retry = true,
    responseHeaders?: (headers: Headers) => void,
  ): Promise<T> {
    if (!path.startsWith("/api/v1/"))
      throw new ApiError("Đường dẫn API không hợp lệ.");
    const headers = new Headers(options.headers);
    if (options.body) headers.set("Content-Type", "application/json");
    if (this.tokens)
      headers.set("Authorization", `Bearer ${this.tokens.accessToken}`);
    let response: Response;
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        ...options,
        headers,
        cache: "no-store",
        signal: options.signal ?? AbortSignal.timeout(30000),
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError")
        throw error;
      throw new ApiError(
        "Không kết nối được máy chủ. Kiểm tra kết nối rồi thử lại.",
      );
    }
    if (
      response.status === 401 &&
      retry &&
      this.tokens &&
      !path.startsWith("/api/v1/auth/")
    ) {
      await this.refresh();
      return this.request<T>(path, options, false, responseHeaders);
    }
    responseHeaders?.(response.headers);
    return this.decode<T>(response);
  }
  async getWithMeta<T>(path: string, signal?: AbortSignal) {
    let responseHeaders = new Headers();
    const data = await this.request<T>(path, { signal }, true, headers => { responseHeaders = headers; });
    return {
      data,
      hasMore: responseHeaders.get("X-Has-More") === "true",
      nextCursor: responseHeaders.get("X-Next-Cursor"),
    };
  }
  get<T>(path: string, signal?: AbortSignal) {
    return this.request<T>(path, { signal });
  }
  post<T>(path: string, body?: unknown, options?: RequestInit) {
    return this.request<T>(path, {
      ...options,
      method: "POST",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }
  patch<T>(path: string, body: unknown) {
    return this.request<T>(path, {
      method: "PATCH",
      body: JSON.stringify(body),
    });
  }
  delete(path: string) {
    return this.request<void>(path, { method: "DELETE" });
  }
}
