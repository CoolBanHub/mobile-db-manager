import type { ColumnInfo, TableInfo } from "./mobileTypes";

export interface SqlSuggestion {
  label: string;
  kind: "keyword" | "table" | "column";
  detail: string;
}

export function mergeTableMetadata(
  existing: readonly TableInfo[],
  incoming: readonly TableInfo[],
): TableInfo[] {
  const merged = [...existing];
  const known = new Set(existing.map((table) => `${table.parent_schema ?? ""}\u0000${table.name}`));
  for (const table of incoming) {
    const key = `${table.parent_schema ?? ""}\u0000${table.name}`;
    if (known.has(key)) continue;
    known.add(key);
    merged.push(table);
  }
  return merged;
}

const SQL_KEYWORDS = [
  "SELECT",
  "FROM",
  "WHERE",
  "AND",
  "OR",
  "AS",
  "JOIN",
  "LEFT JOIN",
  "INNER JOIN",
  "ON",
  "GROUP BY",
  "ORDER BY",
  "HAVING",
  "LIMIT",
  "OFFSET",
  "DISTINCT",
  "COUNT",
  "SUM",
  "AVG",
  "MIN",
  "MAX",
  "CASE",
  "WHEN",
  "THEN",
  "ELSE",
  "END",
] as const;

function identifierQuote(dbType: string): ["`" | '"' | "[", "`" | '"' | "]"] {
  if (dbType === "mysql") return ["`", "`"];
  if (dbType === "sqlserver" || dbType === "access") return ["[", "]"];
  return ['"', '"'];
}

export function quoteSqlIdentifier(identifier: string, dbType: string): string {
  const [open, close] = identifierQuote(dbType);
  const escaped = identifier.replaceAll(close, close + close);
  return `${open}${escaped}${close}`;
}

export function qualifiedTableName(table: string, schema: string | null, dbType: string): string {
  const parts = [schema, table].filter((part): part is string => Boolean(part));
  return parts.map((part) => quoteSqlIdentifier(part, dbType)).join(".");
}

export function buildTableSelect(table: string, schema: string | null, dbType: string): string {
  const qualified = qualifiedTableName(table, schema, dbType);
  if (dbType === "sqlserver" || dbType === "access") return `SELECT TOP 200 *\nFROM ${qualified};`;
  return `SELECT *\nFROM ${qualified}\nLIMIT 200;`;
}

export function buildColumnCondition(sql: string, column: string, dbType: string): string {
  const identifier = quoteSqlIdentifier(column, dbType);
  const trimmed = sql.trimEnd().replace(/;$/, "");
  if (!trimmed) return `WHERE ${identifier} = `;
  const conjunction = /\bwhere\b/i.test(trimmed) ? "AND" : "WHERE";
  return `${trimmed}\n${conjunction} ${identifier} = ;`;
}

export function formatSql(sql: string): string {
  const normalized = sql
    .replace(/\r\n?/g, "\n")
    .replace(/[ \t]+/g, " ")
    .replace(/\s*;\s*$/, ";")
    .trim();
  if (!normalized) return "";

  const breakBefore = [
    "SELECT",
    "FROM",
    "WHERE",
    "LEFT JOIN",
    "RIGHT JOIN",
    "INNER JOIN",
    "FULL JOIN",
    "JOIN",
    "ON",
    "GROUP BY",
    "HAVING",
    "ORDER BY",
    "LIMIT",
    "OFFSET",
  ];
  let formatted = normalized;
  for (const keyword of breakBefore) {
    formatted = formatted.replace(new RegExp(`\\s+${keyword.replace(" ", "\\s+")}\\s+`, "gi"), `\n${keyword} `);
  }
  formatted = formatted.replace(/\s+(AND|OR)\s+/gi, (_match, keyword: string) => `\n  ${keyword.toUpperCase()} `);
  return formatted.replace(/^\n/, "");
}

export function currentSqlToken(sql: string, caret: number): string {
  return sql.slice(0, caret).match(/[A-Za-z_][A-Za-z0-9_$]*$/)?.[0] ?? "";
}

export function sqlSuggestions(
  sql: string,
  caret: number,
  tables: readonly TableInfo[],
  columns: readonly ColumnInfo[],
): SqlSuggestion[] {
  const token = currentSqlToken(sql, caret).toLocaleLowerCase();
  if (!token) return [];
  const suggestions: SqlSuggestion[] = [
    ...SQL_KEYWORDS.map((label) => ({ label, kind: "keyword" as const, detail: "SQL" })),
    ...tables.map((table) => ({ label: table.name, kind: "table" as const, detail: table.table_type })),
    ...columns.map((column) => ({ label: column.name, kind: "column" as const, detail: column.data_type })),
  ];
  return suggestions
    .filter((suggestion) => suggestion.label.toLocaleLowerCase().startsWith(token))
    .sort((left, right) => {
      const priority = { column: 0, table: 1, keyword: 2 };
      return priority[left.kind] - priority[right.kind] || left.label.localeCompare(right.label);
    })
    .slice(0, 8);
}

export function applySqlSuggestion(sql: string, caret: number, suggestion: string): { sql: string; caret: number } {
  const token = currentSqlToken(sql, caret);
  const start = caret - token.length;
  const nextSql = `${sql.slice(0, start)}${suggestion}${sql.slice(caret)}`;
  return { sql: nextSql, caret: start + suggestion.length };
}
