import { describe, expect, it } from "vitest";
import type { MobileDatabaseSession } from "../mobileTypes";
import { isActiveDatabaseSession } from "./diagnostics";

function session(overrides: Partial<MobileDatabaseSession> = {}): MobileDatabaseSession {
  return {
    sessionId: "42",
    user: "app",
    database: "main",
    client: "mobile",
    state: "active",
    command: "Query",
    query: "SELECT 1",
    queryStartedAt: "",
    transactionStartedAt: "",
    durationMs: 1000,
    transactionDurationMs: 0,
    waitType: "",
    waitEvent: "",
    ...overrides,
  };
}

describe("database session diagnostics", () => {
  it("recognizes running statements", () => {
    expect(isActiveDatabaseSession(session())).toBe(true);
  });

  it("does not classify sleeping or idle sessions as running", () => {
    expect(isActiveDatabaseSession(session({ command: "Sleep" }))).toBe(false);
    expect(isActiveDatabaseSession(session({ state: "idle" }))).toBe(false);
    expect(isActiveDatabaseSession(session({ state: "sleeping" }))).toBe(false);
  });

  it("keeps idle-in-transaction sessions visible for incident diagnosis", () => {
    expect(isActiveDatabaseSession(session({ state: "idle in transaction", transactionDurationMs: 45_000 }))).toBe(true);
  });
});
