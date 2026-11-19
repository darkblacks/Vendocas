/** Single seam for outbound HTTP so tests can substitute provider APIs. Every call has a timeout. */
type FetchFn = (input: string, init?: RequestInit) => Promise<Response>;

let impl: FetchFn = (input, init) => fetch(input, { ...init, signal: init?.signal ?? AbortSignal.timeout(10_000) });

export const outbound = (input: string, init?: RequestInit) => impl(input, init);
export function __setOutbound(f: FetchFn | null) {
  impl = f ?? ((input, init) => fetch(input, { ...init, signal: init?.signal ?? AbortSignal.timeout(10_000) }));
}
