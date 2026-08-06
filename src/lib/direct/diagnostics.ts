import type { MobileDatabaseLock, MobileDatabaseSession } from "../mobileTypes";
import { DirectDatabase, requireNative } from "./native";

export type DirectDiagnosticKind = "sessions" | "locks";
export type DirectSessionAction = "cancel" | "terminate";

export function isActiveDatabaseSession(session: MobileDatabaseSession): boolean {
  const state = session.state.toLocaleLowerCase();
  const command = session.command.toLocaleLowerCase();
  return session.transactionDurationMs > 0
    || (!!session.query.trim()
      && !state.startsWith("idle")
      && state !== "sleeping"
      && state !== "dormant"
      && command !== "sleep");
}

export async function loadDirectDiagnostics(
  connectionId: string,
  database: string,
  kind: "sessions",
): Promise<MobileDatabaseSession[]>;
export async function loadDirectDiagnostics(
  connectionId: string,
  database: string,
  kind: "locks",
): Promise<MobileDatabaseLock[]>;
export async function loadDirectDiagnostics(
  connectionId: string,
  database: string,
  kind: DirectDiagnosticKind,
): Promise<MobileDatabaseSession[] | MobileDatabaseLock[]> {
  requireNative();
  // Only a fixed action name crosses the bridge; diagnostic SQL stays native.
  return (
    await DirectDatabase.diagnostics({
      connectionId,
      database,
      action: kind,
    })
  ).value as MobileDatabaseSession[] | MobileDatabaseLock[];
}

export async function interruptDirectSession(options: {
  connectionId: string;
  database: string;
  sessionId: string;
  action: DirectSessionAction;
  productionConfirmation?: string;
}): Promise<string> {
  requireNative();
  const result = (
    await DirectDatabase.diagnostics({
      ...options,
      confirmedAction: true,
      productionConfirmation: options.productionConfirmation ?? "",
    })
  ).value as { ok: boolean; message: string };
  return result.message;
}
