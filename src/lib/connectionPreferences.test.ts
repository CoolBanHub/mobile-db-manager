import { describe, expect, it } from "vitest";
import {
  getConnectionPreference,
  getConnectionSortMode,
  parseConnectionTags,
  removeConnectionPreference,
  saveConnectionPreference,
  saveConnectionSortMode,
} from "./connectionPreferences";

function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  };
}

describe("connectionPreferences", () => {
  it("defaults production connections to the production environment", () => {
    expect(getConnectionPreference("connection", true, memoryStorage()).environment).toBe("production");
  });

  it("saves and removes grouping, favorite, and environment metadata", () => {
    const storage = memoryStorage();
    saveConnectionPreference(
      "connection",
      { group: "Core", favorite: true, pinned: true, environment: "staging", tags: ["核心", "只读"], lastUsedAt: 123 },
      storage,
    );
    expect(getConnectionPreference("connection", false, storage)).toEqual({
      group: "Core",
      favorite: true,
      pinned: true,
      environment: "staging",
      tags: ["核心", "只读"],
      lastUsedAt: 123,
    });
    removeConnectionPreference("connection", storage);
    expect(getConnectionPreference("connection", false, storage).favorite).toBe(false);
  });

  it("normalizes custom tags and keeps legacy preferences compatible", () => {
    expect(parseConnectionTags(" 核心， 只读,核心、临时环境 ")).toEqual(["核心", "只读", "临时环境"]);

    const storage = memoryStorage();
    storage.setItem("mobile-db-manager.connection-preferences.v1", JSON.stringify({
      legacy: { group: "旧分组", favorite: false, environment: "development" },
    }));
    expect(getConnectionPreference("legacy", false, storage).tags).toEqual([]);
    expect(getConnectionPreference("legacy", false, storage).pinned).toBe(false);
    expect(getConnectionPreference("legacy", false, storage).lastUsedAt).toBe(0);
  });

  it("persists a supported catalog sort mode and falls back to recent", () => {
    const storage = memoryStorage();
    expect(getConnectionSortMode(storage)).toBe("recent");
    saveConnectionSortMode("environment", storage);
    expect(getConnectionSortMode(storage)).toBe("environment");
    storage.setItem("mobile-db-manager.connection-sort-mode.v1", "invalid");
    expect(getConnectionSortMode(storage)).toBe("recent");
  });
});
