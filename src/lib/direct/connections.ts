import type {
  MobileConnectionDraft,
  MobileConnectionEditor,
  MobileConnectionSummary,
} from "../mobileTypes";
import { DirectDatabase, requireNative } from "./native";

export async function listDirectConnections(): Promise<MobileConnectionSummary[]> {
  requireNative();
  return (await DirectDatabase.listConnections()).value;
}

export async function getDirectConnection(id: string): Promise<MobileConnectionEditor> {
  requireNative();
  return (await DirectDatabase.getConnection({ id })).value;
}

export async function saveDirectConnection(connection: MobileConnectionDraft): Promise<MobileConnectionSummary> {
  requireNative();
  return (await DirectDatabase.saveConnection({ connection })).value;
}

export async function deleteDirectConnection(id: string): Promise<void> {
  requireNative();
  await DirectDatabase.deleteConnection({ id });
}

export async function testDirectConnection(connection: MobileConnectionDraft): Promise<string> {
  requireNative();
  return (await DirectDatabase.testConnection({ connection })).value.message;
}

