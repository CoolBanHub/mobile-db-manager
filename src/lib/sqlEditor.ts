import type { ColumnInfo, TableInfo } from "./mobileTypes";

export interface SqlSuggestion {
  label: string;
  kind: "keyword" | "table" | "column";
  detail: string;
}

export function mergeTableMetadata(existing: readonly TableInfo[], incoming: readonly TableInfo[]): TableInfo[] {
  // 表名可能跨 schema 重复，使用“schema + NUL + 表名”组成无歧义的去重键。
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
  "INSERT INTO",
  "UPDATE",
  "DELETE FROM",
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
  "CREATE TABLE",
  "ALTER TABLE",
  "DROP TABLE",
  "TRUNCATE TABLE",
  "UNION ALL",
  "WITH",
  "EXISTS",
  "IN",
  "IS NULL",
  "IS NOT NULL",
  "BETWEEN",
  "LIKE",
  "VALUES",
  "SET",
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

const DIALECT_KEYWORDS: Record<string, readonly string[]> = {
  postgres: ["RETURNING", "ILIKE", "ON CONFLICT", "DO UPDATE", "JSONB_BUILD_OBJECT", "GENERATE_SERIES", "VACUUM", "ANALYZE"],
  mysql: ["SHOW DATABASES", "SHOW TABLES", "DESCRIBE", "USE", "REPLACE INTO", "ON DUPLICATE KEY UPDATE", "STRAIGHT_JOIN", "EXPLAIN"],
  sqlserver: ["TOP", "EXEC", "GO", "MERGE", "OUTPUT", "CROSS APPLY", "OUTER APPLY", "WITH (NOLOCK)"],
  redis: ["GET", "SET", "DEL", "EXISTS", "EXPIRE", "TTL", "PTTL", "SCAN", "KEYS", "TYPE", "DBSIZE", "MGET", "MSET", "INCR", "DECR", "HGET", "HSET", "HGETALL", "HDEL", "LPUSH", "RPUSH", "LRANGE", "SADD", "SMEMBERS", "ZADD", "ZRANGE", "XADD", "XRANGE", "PUBLISH"],
  mongodb: ["find", "findOne", "aggregate", "countDocuments", "distinct", "insertOne", "insertMany", "updateOne", "updateMany", "replaceOne", "deleteOne", "deleteMany", "sort", "limit", "skip", "project", "$match", "$group", "$sort", "$project", "$lookup", "$unwind", "$set", "$unset"],
};

export function editorKeywords(dbType: string): readonly string[] {
  if (dbType === "redis" || dbType === "mongodb") return DIALECT_KEYWORDS[dbType];
  return [...SQL_KEYWORDS, ...(DIALECT_KEYWORDS[dbType] ?? [])];
}

function identifierQuote(dbType: string): ["`" | '"' | "[", "`" | '"' | "]"] {
  if (dbType === "mysql") return ["`", "`"];
  if (dbType === "sqlserver" || dbType === "access") return ["[", "]"];
  return ['"', '"'];
}

export function quoteSqlIdentifier(identifier: string, dbType: string): string {
  const [open, close] = identifierQuote(dbType);
  // SQL 标识符通过重复闭合符转义，不能套用字符串字面量的反斜杠规则。
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
  // 这是移动端轻量排版器，只整理空白和常见子句，不尝试重写字符串或解析完整 AST。
  const normalized = sql
    .replace(/\r\n?/g, "\n")
    .replace(/[ \t]+/g, " ")
    .replace(/\s*;\s*$/, ";")
    .trim();
  if (!normalized) return "";

  const breakBefore = ["SELECT", "FROM", "WHERE", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "FULL JOIN", "JOIN", "ON", "GROUP BY", "HAVING", "ORDER BY", "LIMIT", "OFFSET"];
  let formatted = normalized;
  for (const keyword of breakBefore) {
    formatted = formatted.replace(new RegExp(`\\s+${keyword.replace(" ", "\\s+")}\\s+`, "gi"), `\n${keyword} `);
  }
  formatted = formatted.replace(/\s+(AND|OR)\s+/gi, (_match, keyword: string) => `\n  ${keyword.toUpperCase()} `);
  return formatted.replace(/^\n/, "");
}

export function currentSqlToken(sql: string, caret: number): string {
  return sql.slice(0, caret).match(/[A-Za-z_$][A-Za-z0-9_$]*$/)?.[0] ?? "";
}

export function sqlSuggestions(sql: string, caret: number, tables: readonly TableInfo[], columns: readonly ColumnInfo[], dbType = "postgres"): SqlSuggestion[] {
  const token = currentSqlToken(sql, caret).toLocaleLowerCase();
  if (!token) return [];
  const prefix = sql.slice(0, Math.max(0, caret - token.length));
  const statementStart = !prefix.trim() || /(?:^|;)\s*$/.test(prefix);
  const suggestions: SqlSuggestion[] = [
    ...editorKeywords(dbType).map((label) => ({
      label,
      kind: "keyword" as const,
      detail: dbType === "redis" ? "REDIS" : dbType === "mongodb" ? "MONGODB" : dbType.toUpperCase(),
    })),
    ...tables.map((table) => ({ label: table.name, kind: "table" as const, detail: table.table_type })),
    ...columns.map((column) => ({ label: column.name, kind: "column" as const, detail: column.data_type })),
  ];
  return suggestions
    .filter((suggestion) => suggestion.label.toLocaleLowerCase().startsWith(token))
    .sort((left, right) => {
      // 语句开头优先关键字；表达式内部优先列名，减少移动键盘上的选择次数。
      const priority = statementStart ? { keyword: 0, table: 1, column: 2 } : { column: 0, table: 1, keyword: 2 };
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
