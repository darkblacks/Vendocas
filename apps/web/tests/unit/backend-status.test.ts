import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, UNAVAILABLE } from "@/lib/api";
import { backendStatus } from "@/lib/backend-status";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

describe("API unavailable (homologation server off)", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    vi.useFakeTimers();
    backendStatus.__reset();
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

  it("turns a proxy 502 into BACKEND_UNAVAILABLE and stops sending requests", async () => {
    fetchMock.mockResolvedValue(new Response("<html>Bad gateway</html>", { status: 502, headers: { "content-type": "text/html" } }));
    await expect(api("/contacts")).rejects.toMatchObject({ code: UNAVAILABLE });
    expect(backendStatus.get().up).toBe(false);
    for (let i = 0; i < 20; i++) await expect(api("/contacts")).rejects.toBeInstanceOf(ApiError);
    expect(fetchMock).toHaveBeenCalledTimes(1); // no request loop
  });

  it("treats network errors the same way", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    await expect(api("/contacts")).rejects.toMatchObject({ code: UNAVAILABLE });
    expect(backendStatus.get().up).toBe(false);
  });

  it("probes /api/health with exponential backoff and recovers when it is back", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("down"));
    await expect(api("/contacts")).rejects.toThrow();
    fetchMock.mockResolvedValue(json({ up: false }, 503));
    await vi.advanceTimersByTimeAsync(10_000);
    await vi.advanceTimersByTimeAsync(20_000);
    const probes = fetchMock.mock.calls.filter((c) => c[0] === "/api/health").length;
    expect(probes).toBe(2);
    expect(backendStatus.get().up).toBe(false);
    fetchMock.mockResolvedValue(json({ up: true }));
    await vi.advanceTimersByTimeAsync(40_000);
    expect(backendStatus.get().up).toBe(true);
    fetchMock.mockResolvedValue(json([{ id: 1 }]));
    await expect(api("/contacts")).resolves.toEqual([{ id: 1 }]);
  });

  it("keeps real API errors (JSON) as normal errors", async () => {
    fetchMock.mockResolvedValue(json({ code: "VALIDATION", detail: "bad" }, 400));
    await expect(api("/contacts")).rejects.toMatchObject({ code: "VALIDATION" });
    fetchMock.mockResolvedValue(json({ code: "RATE_LIMITED", detail: "slow down" }, 503));
    await expect(api("/contacts")).rejects.toMatchObject({ code: "RATE_LIMITED" });
    expect(backendStatus.get().up).toBe(true);
  });

  it("never calls anything that could start the server", async () => {
    fetchMock.mockRejectedValue(new TypeError("down"));
    await expect(api("/contacts")).rejects.toThrow();
    await vi.advanceTimersByTimeAsync(600_000);
    const urls = fetchMock.mock.calls.map((c) => String(c[0]));
    expect(urls.every((u) => u === "/api/v1/contacts" || u === "/api/health")).toBe(true);
  });
});
