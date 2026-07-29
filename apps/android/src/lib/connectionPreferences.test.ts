import { describe, expect, it } from "vitest";
import {
  getConnectionPreference,
  removeConnectionPreference,
  saveConnectionPreference,
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
    expect(getConnectionPreference("server", "connection", true, memoryStorage()).environment).toBe("production");
  });

  it("saves and removes grouping, favorite, and environment metadata", () => {
    const storage = memoryStorage();
    saveConnectionPreference(
      "server",
      "connection",
      { group: "Core", favorite: true, environment: "staging" },
      storage,
    );
    expect(getConnectionPreference("server", "connection", false, storage)).toEqual({
      group: "Core",
      favorite: true,
      environment: "staging",
    });
    removeConnectionPreference("server", "connection", storage);
    expect(getConnectionPreference("server", "connection", false, storage).favorite).toBe(false);
  });
});
