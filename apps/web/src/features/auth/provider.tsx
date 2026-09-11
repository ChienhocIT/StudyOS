"use client";
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiClient, ApiError } from "@/shared/api/client";
import type { Entity, Tokens, User } from "@/shared/api/types";

const SESSION_KEY = "studyos.session.v1";
interface Session { user: User; tokens: Tokens }
interface AuthContextValue {
  api: ApiClient; user: User | null; ready: boolean;
  authenticate: (mode: "login" | "register", body: { email: string; password: string; displayName?: string }) => Promise<void>;
  logout: () => Promise<void>;
}
const AuthContext = createContext<AuthContextValue | null>(null);
export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: {
    staleTime: 15000, retry: (count, error) => count < 1 && !(error instanceof ApiError && [400, 401, 403, 404].includes(error.status)), refetchOnWindowFocus: true
  }, mutations: { retry: false } } }));
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);
  const [api] = useState(() => new ApiClient((process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080").replace(/\/$/, ""), tokens => {
    if (!tokens) { sessionStorage.removeItem(SESSION_KEY); setUser(null); queryClient.clear(); return; }
    try { const previous = JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null") as Session | null;
      if (previous) sessionStorage.setItem(SESSION_KEY, JSON.stringify({ ...previous, tokens }));
    } catch { sessionStorage.removeItem(SESSION_KEY); }
  }));
  useEffect(() => {
    try {
      const saved = JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null") as Session | null;
      if (saved?.tokens?.refreshToken && saved?.user?.id) { api.setTokens(saved.tokens); setUser(saved.user); }
    } catch { sessionStorage.removeItem(SESSION_KEY); }
    setReady(true);
  }, [api]);
  const authenticate: AuthContextValue["authenticate"] = async (mode, body) => {
    const result = await api.post<Entity<"AuthResponse">>(`/api/v1/auth/${mode}`, body);
    queryClient.clear();
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(result));
    api.setTokens(result.tokens); setUser(result.user);
  };
  const logout = async () => {
    const refreshToken = api.getTokens()?.refreshToken;
    try { if (refreshToken) await api.post("/api/v1/auth/logout", { refreshToken }); }
    finally { api.setTokens(null); setUser(null); queryClient.clear(); }
  };
  return <QueryClientProvider client={queryClient}><AuthContext.Provider value={{ api, user, ready, authenticate, logout }}>{children}</AuthContext.Provider></QueryClientProvider>;
}
export function useAuth() { const context = useContext(AuthContext); if (!context) throw new Error("Missing authentication provider"); return context; }
