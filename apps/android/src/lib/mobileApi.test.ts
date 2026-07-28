import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  ApiTimeoutError,
  apiGetJson,
  buildApiHeaders,
  buildApiPath,
  withApiTimeout,
} from "./mobileApi";

afterEach(() => {
  vi.useRealTimers();
});

describe("buildApiHeaders", () => {
  it("adds a bearer token without dropping existing headers", () => {
    const headers = buildApiHeaders("mobile-token", { Accept: "application/json" });

    expect(headers.get("Authorization")).toBe("Bearer mobile-token");
    expect(headers.get("Accept")).toBe("application/json");
  });

  it("does not create an authorization header without a token", () => {
    expect(buildApiHeaders(null).has("Authorization")).toBe(false);
  });

  it("replaces a stale authorization header with the current token", () => {
    const headers = buildApiHeaders("current-token", {
      Authorization: "Bearer stale-token",
    });

    expect(headers.get("Authorization")).toBe("Bearer current-token");
  });
});

describe("buildApiPath", () => {
  it("encodes metadata identifiers and omits undefined values", () => {
    const path = buildApiPath("/api/schema/tables", {
      connection_id: "prod/一",
      database: "sales data",
      schema: undefined,
    });

    expect(path).toBe("/api/schema/tables?connection_id=prod%2F%E4%B8%80&database=sales+data");
  });

  it("returns the original path when no parameters are defined", () => {
    expect(buildApiPath("/api/schema/databases", { connection_id: undefined })).toBe("/api/schema/databases");
  });
});

describe("withApiTimeout", () => {
  it("aborts an operation that does not finish before the deadline", async () => {
    vi.useFakeTimers();
    const request = withApiTimeout(
      (signal) =>
        new Promise<void>((_resolve, reject) => {
          signal.addEventListener("abort", () => reject(signal.reason), { once: true });
        }),
      1_000,
    );
    const rejection = expect(request).rejects.toBeInstanceOf(ApiTimeoutError);

    await vi.advanceTimersByTimeAsync(1_000);

    await rejection;
  });

  it("keeps the timeout active until response processing finishes", async () => {
    vi.useFakeTimers();
    let responseReceived = false;
    const request = withApiTimeout(
      async (signal) => {
        await Promise.resolve();
        responseReceived = true;
        await new Promise<void>((_resolve, reject) => {
          signal.addEventListener("abort", () => reject(signal.reason), { once: true });
        });
        return "unreachable";
      },
      1_000,
    );
    const rejection = expect(request).rejects.toBeInstanceOf(ApiTimeoutError);

    await Promise.resolve();
    expect(responseReceived).toBe(true);
    await vi.advanceTimersByTimeAsync(1_000);

    await rejection;
  });

  it("returns a completed operation and clears its deadline", async () => {
    vi.useFakeTimers();
    const clearTimeoutSpy = vi.spyOn(globalThis, "clearTimeout");

    await expect(withApiTimeout(async () => "done", 1_000)).resolves.toBe("done");

    expect(clearTimeoutSpy).toHaveBeenCalledOnce();
    await vi.advanceTimersByTimeAsync(1_000);
  });

  it("does not disguise an operation failure as a timeout", async () => {
    const failure = new Error("TLS certificate rejected");

    await expect(withApiTimeout(async () => Promise.reject(failure), 1_000)).rejects.toBe(failure);
  });
});

describe("apiGetJson", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends encoded query parameters and the mobile bearer token", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([{ name: "orders" }]), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      apiGetJson<{ name: string }[]>("https://dbx.example", "/api/schema/tables", "mobile-token", {
        connection_id: "prod/一",
        schema: "sales data",
        limit: 100,
      }),
    ).resolves.toEqual([{ name: "orders" }]);

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(
      "https://dbx.example/api/schema/tables?connection_id=prod%2F%E4%B8%80&schema=sales+data&limit=100",
    );
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer mobile-token");
    expect(new Headers(init.headers).get("Accept")).toBe("application/json");
  });

  it("throws an ApiError that retains the response status", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));

    const request = apiGetJson("https://dbx.example", "/api/schema/databases", "expired-token", {});

    await expect(request).rejects.toMatchObject({
      name: "ApiError",
      message: "服务器返回 401",
      status: 401,
    } satisfies Partial<ApiError>);
  });
});
