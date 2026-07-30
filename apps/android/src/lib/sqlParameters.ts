export type SqlParameterValue = string | number | boolean | null;

function sqlLiteral(value: SqlParameterValue): string {
  if (value === null) return "NULL";
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("SQL 参数不能是 NaN 或 Infinity");
    return String(value);
  }
  if (typeof value === "boolean") return value ? "TRUE" : "FALSE";
  return `'${value.replaceAll("'", "''")}'`;
}

export function parseSqlParameterJson(source: string): Record<string, SqlParameterValue> {
  if (!source.trim()) return {};
  const parsed = JSON.parse(source) as unknown;
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
    throw new Error("SQL 参数必须是 JSON 对象");
  }
  const result: Record<string, SqlParameterValue> = {};
  for (const [name, value] of Object.entries(parsed)) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) throw new Error(`无效参数名：${name}`);
    if (value !== null && !["string", "number", "boolean"].includes(typeof value)) {
      throw new Error(`参数 ${name} 只支持字符串、数字、布尔值或 null`);
    }
    result[name] = value as SqlParameterValue;
  }
  return result;
}

export function resolveSqlParameters(sql: string, parameters: Record<string, SqlParameterValue>): string {
  let result = "";
  let index = 0;
  let quote: "'" | '"' | "`" | null = null;
  let lineComment = false;
  let blockComment = false;

  while (index < sql.length) {
    const char = sql[index];
    const next = sql[index + 1];
    if (lineComment) {
      result += char;
      index++;
      if (char === "\n") lineComment = false;
      continue;
    }
    if (blockComment) {
      result += char;
      index++;
      if (char === "*" && next === "/") {
        result += next;
        index++;
        blockComment = false;
      }
      continue;
    }
    if (quote) {
      result += char;
      index++;
      if (char === quote) {
        if (sql[index] === quote) {
          result += sql[index++];
        } else {
          quote = null;
        }
      } else if (char === "\\" && index < sql.length) {
        result += sql[index++];
      }
      continue;
    }
    if (char === "-" && next === "-") {
      result += "--";
      index += 2;
      lineComment = true;
      continue;
    }
    if (char === "/" && next === "*") {
      result += "/*";
      index += 2;
      blockComment = true;
      continue;
    }
    if (char === "'" || char === '"' || char === "`") {
      quote = char;
      result += char;
      index++;
      continue;
    }

    const rest = sql.slice(index);
    const match =
      rest.match(/^:\s*([A-Za-z_][A-Za-z0-9_]*)/) ??
      rest.match(/^\$\{([A-Za-z_][A-Za-z0-9_]*)\}/) ??
      rest.match(/^\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}/);
    if (match && !(char === ":" && sql[index - 1] === ":")) {
      const name = match[1];
      if (!(name in parameters)) throw new Error(`缺少 SQL 参数：${name}`);
      result += sqlLiteral(parameters[name]);
      index += match[0].length;
      continue;
    }
    result += char;
    index++;
  }
  return result;
}
