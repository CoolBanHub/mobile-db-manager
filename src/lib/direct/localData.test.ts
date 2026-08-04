import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  DIRECT_HISTORY_KEY,
  searchDirectHistory,
} from "./history";
import {
  deleteDirectSavedSqlFolder,
  loadDirectSavedSqlLibrary,
  saveDirectSavedSql,
  saveDirectSavedSqlFolder,
} from "./savedSql";

beforeEach(() => {
  const values = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
  });
});

describe("本机直连数据存储", () => {
  it("删除目录时递归删除子目录和其中的 SQL", () => {
    const root = saveDirectSavedSqlFolder({ connectionId: "conn-1", name: "根目录" });
    const child = saveDirectSavedSqlFolder({
      connectionId: "conn-1",
      parentFolderId: root.id,
      name: "子目录",
    });
    saveDirectSavedSql({
      connectionId: "conn-1",
      folderId: child.id,
      database: "app",
      name: "查询",
      sql: "SELECT 1",
    });

    deleteDirectSavedSqlFolder(root.id);

    expect(loadDirectSavedSqlLibrary()).toEqual({ folders: [], files: [] });
  });

  it("使用游标分页读取本机查询历史", () => {
    const entries = Array.from({ length: 25 }, (_, index) => ({
      id: `history-${index}`,
      connectionId: "conn-1",
      connectionName: "本机数据库",
      database: "app",
      schema: null,
      sql: `SELECT ${index}`,
      executedAt: new Date(Date.UTC(2026, 0, 25 - index)).toISOString(),
      executionTimeMs: index,
      success: true,
      error: null,
    }));
    localStorage.setItem(DIRECT_HISTORY_KEY, JSON.stringify(entries));

    const first = searchDirectHistory({ limit: 20 });
    const second = searchDirectHistory({ limit: 20, cursor: first.nextCursor });

    expect(first.entries).toHaveLength(20);
    expect(first.nextCursor?.id).toBe("history-19");
    expect(second.entries.map((entry) => entry.id)).toEqual([
      "history-20",
      "history-21",
      "history-22",
      "history-23",
      "history-24",
    ]);
    expect(second.nextCursor).toBeNull();
    expect(second.total).toBe(25);
  });

  it("查询历史支持过滤、边界限制和已删除游标回退", () => {
    const entries = [
      {
        id: "new-success",
        connectionId: "conn-1",
        connectionName: "生产库",
        database: "orders",
        schema: null,
        sql: "SELECT * FROM orders",
        executedAt: "2026-01-03T00:00:00.000Z",
        executionTimeMs: 1,
        success: true,
        error: null,
      },
      {
        id: "old-failure",
        connectionId: "conn-2",
        connectionName: "测试库",
        database: "users",
        schema: null,
        sql: "SELECT * FROM users",
        executedAt: "2026-01-01T00:00:00.000Z",
        executionTimeMs: 2,
        success: false,
        error: "连接失败",
      },
    ];
    localStorage.setItem(DIRECT_HISTORY_KEY, JSON.stringify(entries));

    expect(searchDirectHistory({ searchText: "orders", connectionId: "conn-1", success: true }).entries)
      .toHaveLength(1);
    expect(searchDirectHistory({
      startedAt: "2026-01-02T00:00:00.000Z",
      endedAt: "2026-01-04T00:00:00.000Z",
    }).entries.map((entry) => entry.id)).toEqual(["new-success"]);
    expect(searchDirectHistory({
      cursor: { id: "deleted", executedAt: "2026-01-02T00:00:00.000Z" },
    }).entries.map((entry) => entry.id)).toEqual(["old-failure"]);
    expect(searchDirectHistory({ limit: 0 }).entries).toHaveLength(1);
    expect(searchDirectHistory({ limit: "not-a-number" }).entries).toHaveLength(2);
    expect(searchDirectHistory({ limit: 1_000 }).entries).toHaveLength(2);
  });
});
