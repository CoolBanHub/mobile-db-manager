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
    expect(getConnectionPreference("connection", true, memoryStorage()).environment).toBe("production");
  });

  it("saves and removes grouping, favorite, and environment metadata", () => {
    const storage = memoryStorage();
    saveConnectionPreference(
      "connection",
      { group: "Core", favorite: true, environment: "staging" },
      storage,
    );
    expect(getConnectionPreference("connection", false, storage)).toEqual({
      group: "Core",
      favorite: true,
      environment: "staging",
    });
    removeConnectionPreference("connection", storage);
    expect(getConnectionPreference("connection", false, storage).favorite).toBe(false);
  });
});
