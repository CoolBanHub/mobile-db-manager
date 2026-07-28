import { describe, expect, it } from "vitest";
import { loadServerProfile, normalizeServerUrl, saveServerProfile, SERVER_PROFILE_STORAGE_KEY } from "./serverProfile";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    values,
  };
}

describe("serverProfile", () => {
  it("normalizes a server address and removes query data", () => {
    expect(normalizeServerUrl("dbx.example.com/team/?from=mobile")).toBe("https://dbx.example.com/team");
  });

  it("rejects credentials embedded in a server address", () => {
    expect(() => normalizeServerUrl("https://admin:secret@example.com")).toThrow("用户名或密码");
  });

  it("round trips a saved profile", () => {
    const storage = memoryStorage();
    saveServerProfile({ name: "  Production  ", baseUrl: "https://dbx.example.com/" }, storage);

    expect(storage.values.has(SERVER_PROFILE_STORAGE_KEY)).toBe(true);
    expect(loadServerProfile(storage)).toEqual({
      name: "Production",
      baseUrl: "https://dbx.example.com",
    });
  });
});
