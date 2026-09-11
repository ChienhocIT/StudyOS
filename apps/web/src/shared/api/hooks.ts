"use client";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/features/auth/provider";
export function useResource<T>(
  path: string | null,
  poll?: number | ((data: T | undefined) => number | false),
) {
  const { api, user } = useAuth();
  return useQuery({
    queryKey: [path],
    queryFn: ({ signal }) => api.get<T>(path!, signal),
    enabled: Boolean(path && user),
    refetchInterval:
      typeof poll === "function" ? (query) => poll(query.state.data) : poll,
  });
}
export function useAction<T, V>(
  action: (value: V) => Promise<T>,
  paths: string[] = [],
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: action,
    onSuccess: async () => {
      await Promise.all(
        paths.map((path) => client.invalidateQueries({ queryKey: [path] })),
      );
    },
  });
}
