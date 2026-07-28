import { describe, expect, it } from "vitest";
import { buildApiHeaders } from "./mobileApi";

describe("buildApiHeaders", () => {
  it("adds a bearer token without dropping existing headers", () => {
    const headers = buildApiHeaders("mobile-token", { Accept: "application/json" });

    expect(headers.get("Authorization")).toBe("Bearer mobile-token");
    expect(headers.get("Accept")).toBe("application/json");
  });

  it("does not create an authorization header without a token", () => {
    expect(buildApiHeaders(null).has("Authorization")).toBe(false);
  });
});
