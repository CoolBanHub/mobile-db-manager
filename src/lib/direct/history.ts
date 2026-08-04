import type { MobileHistoryEntry, MobileHistoryPage, QueryResult } from "../mobileTypes";
import { id, readJson, writeJson } from "./localStore";

export const DIRECT_HISTORY_KEY = "mobile-db-manager.direct.history.v1";

function historyEntries(): MobileHistoryEntry[] {
  return readJson<MobileHistoryEntry[]>(DIRECT_HISTORY_KEY, []);
}

export function saveHistory(body: Record<string, unknown>, result: QueryResult | null, error: unknown, started: number) {
  // 翻页查询沿用同一次逻辑执行，只记录第一页，避免历史列表被分页操作淹没。
  if (Number(body.offset ?? 0) !== 0) return;
  const entries = historyEntries();
  const entry: MobileHistoryEntry = {
    id: id(),
    connectionId: String(body.connectionId ?? ""),
    connectionName: String(body.connectionName ?? ""),
    database: String(body.database ?? ""),
    schema: body.schema ? String(body.schema) : null,
    sql: String(body.sql ?? ""),
    executedAt: new Date().toISOString(),
    executionTimeMs: result?.execution_time_ms ?? Date.now() - started,
    success: !error,
    error: error instanceof Error ? error.message : error ? String(error) : null,
  };
  writeJson(DIRECT_HISTORY_KEY, [entry, ...entries].slice(0, 1_000));
}

export function searchDirectHistory(payload: Record<string, unknown>): MobileHistoryPage {
  let entries = historyEntries();
  const query = String(payload.searchText ?? "").toLocaleLowerCase();
  const connectionId = String(payload.connectionId ?? "");
  entries = entries.filter(
    (item) =>
      (!query || `${item.sql} ${item.database} ${item.connectionName}`.toLocaleLowerCase().includes(query)) &&
      (!connectionId || item.connectionId === connectionId),
  );
  if (typeof payload.success === "boolean") entries = entries.filter((item) => item.success === payload.success);
  if (payload.startedAt) entries = entries.filter((item) => item.executedAt >= String(payload.startedAt));
  if (payload.endedAt) entries = entries.filter((item) => item.executedAt <= String(payload.endedAt));
  const total = entries.length;
  const cursor = payload.cursor as { executedAt?: string; id?: string } | undefined;
  if (cursor?.id) {
    const cursorIndex = entries.findIndex(
      (item) => item.id === cursor.id && (!cursor.executedAt || item.executedAt === cursor.executedAt),
    );
    if (cursorIndex >= 0) {
      entries = entries.slice(cursorIndex + 1);
    } else if (cursor.executedAt) {
      entries = entries.filter((item) => item.executedAt < cursor.executedAt!);
    }
  }
  const requestedLimit = Number(payload.limit ?? 20);
  const limit = Number.isFinite(requestedLimit)
    ? Math.min(100, Math.max(1, Math.trunc(requestedLimit)))
    : 20;
  const page = entries.slice(0, limit);
  const last = page.at(-1);
  return {
    entries: page,
    nextCursor: entries.length > page.length && last ? { executedAt: last.executedAt, id: last.id } : null,
    total,
  };
}

export function clearDirectHistory(): void {
  writeJson(DIRECT_HISTORY_KEY, []);
}

export function deleteDirectHistoryEntry(historyId: string): void {
  writeJson(
    DIRECT_HISTORY_KEY,
    historyEntries().filter((item) => item.id !== historyId),
  );
}

