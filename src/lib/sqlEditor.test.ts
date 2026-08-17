import { describe, expect, it } from "vitest";
import type { ColumnInfo, TableInfo } from "./mobileTypes";
import { applySqlSuggestion, buildColumnCondition, buildTableSelect, formatSql, isSqlRelationCompletion, mergeTableMetadata, quoteSqlIdentifier, sqlSuggestions } from "./sqlEditor";

const tables = [{ name: "users", table_type: "BASE TABLE" }] as TableInfo[];
const columns = [{ name: "user_id", data_type: "uuid" }] as ColumnInfo[];

describe("mobile SQL editor helpers", () => {
  it("quotes identifiers for common database families", () => {
    expect(quoteSqlIdentifier("order`item", "mysql")).toBe("`order``item`");
    expect(quoteSqlIdentifier('order"item', "postgres")).toBe('"order""item"');
    expect(quoteSqlIdentifier("order]item", "sqlserver")).toBe("[order]]item]");
  });

  it("builds dialect-aware read-only table queries", () => {
    expect(buildTableSelect("users", "sales", "postgres")).toBe('SELECT *\nFROM "sales"."users"\nLIMIT 200;');
    expect(buildTableSelect("users", "dbo", "sqlserver")).toBe("SELECT TOP 200 *\nFROM [dbo].[users];");
  });

  it("adds WHERE or AND conditions from a field", () => {
    expect(buildColumnCondition("SELECT * FROM users;", "active", "mysql")).toContain("WHERE `active` = ;");
    expect(buildColumnCondition("SELECT * FROM users WHERE id = 1;", "active", "mysql")).toContain("AND `active` = ;");
  });

  it("formats major clauses and logical conditions", () => {
    expect(formatSql("select * from users where active = 1 and id > 4;")).toBe("select *\nFROM users\nWHERE active = 1\n  AND id > 4;");
  });

  it("suggests metadata before keywords and replaces the current token", () => {
    expect(sqlSuggestions("SELECT user", 11, tables, columns)[0]).toMatchObject({
      label: "user_id",
      kind: "column",
    });
    expect(applySqlSuggestion("SELECT us", 9, "users")).toEqual({ sql: "SELECT users", caret: 12 });
  });

  it("only suggests tables and views while typing after FROM or JOIN", () => {
    const objects = [
      ...tables,
      { name: "user_summary", table_type: "VIEW" },
    ] as TableInfo[];

    expect(isSqlRelationCompletion("SELECT * FROM us", 16)).toBe(true);
    expect(isSqlRelationCompletion("SELECT u", 8)).toBe(false);
    expect(sqlSuggestions("SELECT * FROM us", 16, objects, columns).map((item) => [item.label, item.kind])).toEqual([
      ["user_summary", "table"],
      ["users", "table"],
    ]);
    expect(sqlSuggestions("SELECT * FROM public.us", 23, objects, columns).every((item) => item.kind === "table")).toBe(true);
  });

  it("prioritizes statement keywords and includes DELETE for a leading d", () => {
    const labels = sqlSuggestions("d", 1, tables, columns, "postgres").map((item) => item.label);
    expect(labels[0]).toBe("DELETE FROM");
    expect(labels).toEqual(expect.arrayContaining(["DISTINCT", "DROP TABLE"]));
  });

  it("uses database-specific Redis and MongoDB completion vocabularies", () => {
    expect(sqlSuggestions("h", 1, [], [], "redis").map((item) => item.label)).toEqual(["HDEL", "HGET", "HGETALL", "HSET"]);
    expect(sqlSuggestions("find", 4, [], [], "mongodb").map((item) => item.label)).toEqual(["find", "findOne"]);
    expect(sqlSuggestions("$m", 2, [], [], "mongodb")[0]).toMatchObject({
      label: "$match",
      detail: "MONGODB",
    });
  });

  it("merges later metadata pages and search hits without duplicate completion entries", () => {
    const firstPage = [
      { name: "users", table_type: "BASE TABLE", parent_schema: "public" },
      { name: "orders", table_type: "BASE TABLE", parent_schema: "public" },
    ] as TableInfo[];
    const laterPage = [
      { name: "orders", table_type: "BASE TABLE", parent_schema: "public" },
      { name: "audit_log", table_type: "BASE TABLE", parent_schema: "public" },
    ] as TableInfo[];

    expect(mergeTableMetadata(firstPage, laterPage).map((table) => table.name)).toEqual(["users", "orders", "audit_log"]);
  });
});
