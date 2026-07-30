import { describe, expect, it } from "vitest";
import { ApiError, apiDeleteJson, apiFetch, apiGetJson, apiPostJson } from "./mobileApi";

const retiredWebUrl = "https://dbx.example";

describe("standalone Android transport", () => {
  it("rejects HTTP fetches to a DBX Web server", () => {
    expect(() => apiFetch(retiredWebUrl, "/api/mobile/query", null)).toThrow(
      expect.objectContaining({
        name: "ApiError",
        status: 501,
      } satisfies Partial<ApiError>),
    );
  });

  it("rejects remote JSON API helpers before sending a request", async () => {
    await expect(apiGetJson(retiredWebUrl, "/api/mobile/connections", null, {})).rejects.toMatchObject({
      name: "ApiError",
      status: 501,
    } satisfies Partial<ApiError>);
    await expect(apiPostJson(retiredWebUrl, "/api/mobile/query", null, {})).rejects.toMatchObject({
      name: "ApiError",
      status: 501,
    } satisfies Partial<ApiError>);
    await expect(apiDeleteJson(retiredWebUrl, "/api/mobile/history", null)).rejects.toMatchObject({
      name: "ApiError",
      status: 501,
    } satisfies Partial<ApiError>);
  });
});
