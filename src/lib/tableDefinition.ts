import type { MobileDatabaseType } from "./mobileTypes";

export interface TableColumnDraft {
  id: number;
  name: string;
  dataType: string;
  length: string;
  scale: string;
  nullable: boolean;
  primaryKey: boolean;
  autoIncrement: boolean;
  unique: boolean;
  defaultValue: string;
  comment: string;
}

export interface TableIndexDraft {
  id: number;
  name: string;
  columns: string;
  unique: boolean;
}

export interface TableForeignKeyDraft {
  id: number;
  name: string;
  column: string;
  referenceSchema: string;
  referenceTable: string;
  referenceColumn: string;
  onUpdate: string;
  onDelete: string;
}

export interface TableCheckDraft {
  id: number;
  name: string;
  expression: string;
}

export interface TableTriggerDraft {
  id: number;
  name: string;
  sql: string;
}

export interface CreateTableDraft {
  name: string;
  schema: string;
  comment: string;
  ifNotExists: boolean;
  temporary: boolean;
  engine: string;
  charset: string;
  collation: string;
  columns: TableColumnDraft[];
  indexes: TableIndexDraft[];
  foreignKeys: TableForeignKeyDraft[];
  checks: TableCheckDraft[];
  triggers: TableTriggerDraft[];
}

// 建表编辑器只提供经过设备端验证的关系型数据库方言。类型列表保持有限，
// 避免把任意类型文本直接拼进最终 SQL；默认值、CHECK 和触发器则明确保留为高级 SQL 输入。
export const dataTypesByDatabase: Record<"postgres" | "mysql" | "sqlserver", string[]> = {
  postgres: ["BIGINT", "INTEGER", "SMALLINT", "NUMERIC", "VARCHAR", "CHAR", "TEXT", "BOOLEAN", "DATE", "TIMESTAMP", "TIMESTAMPTZ", "UUID", "JSON", "JSONB", "BYTEA"],
  mysql: ["BIGINT", "INT", "SMALLINT", "DECIMAL", "VARCHAR", "CHAR", "TEXT", "LONGTEXT", "TINYINT", "BOOLEAN", "DATE", "DATETIME", "TIMESTAMP", "JSON", "BLOB"],
  sqlserver: ["BIGINT", "INT", "SMALLINT", "DECIMAL", "VARCHAR", "NVARCHAR", "CHAR", "NCHAR", "TEXT", "BIT", "DATE", "DATETIME2", "DATETIMEOFFSET", "UNIQUEIDENTIFIER", "VARBINARY(MAX)"],
};

export function supportedTableDialect(dbType: string): dbType is "postgres" | "mysql" | "sqlserver" {
  return dbType === "postgres" || dbType === "mysql" || dbType === "sqlserver";
}

export function defaultTableColumn(id = 1): TableColumnDraft {
  return {
    id,
    name: id === 1 ? "id" : "",
    dataType: "BIGINT",
    length: "",
    scale: "",
    nullable: false,
    primaryKey: id === 1,
    autoIncrement: id === 1,
    unique: false,
    defaultValue: "",
    comment: "",
  };
}

export function defaultCreateTableDraft(schema: string): CreateTableDraft {
  return {
    name: "",
    schema,
    comment: "",
    ifNotExists: false,
    temporary: false,
    engine: "InnoDB",
    charset: "utf8mb4",
    collation: "",
    columns: [defaultTableColumn()],
    indexes: [],
    foreignKeys: [],
    checks: [],
    triggers: [],
  };
}

function quoteIdentifier(value: string, dbType: MobileDatabaseType | string) {
  if (dbType === "mysql") return `\`${value.replaceAll("`", "``")}\``;
  if (dbType === "sqlserver") return `[${value.replaceAll("]", "]]")}]`;
  return `"${value.replaceAll('"', '""')}"`;
}

function qualified(schema: string, name: string, dbType: MobileDatabaseType | string) {
  return schema.trim() ? `${quoteIdentifier(schema.trim(), dbType)}.${quoteIdentifier(name.trim(), dbType)}` : quoteIdentifier(name.trim(), dbType);
}

function literal(value: string) {
  return `'${value.replaceAll("'", "''")}'`;
}

function columnType(column: TableColumnDraft, dbType: MobileDatabaseType | string) {
  let type = column.dataType.trim().toUpperCase();
  // PostgreSQL 的 SERIAL 家族本身已经包含序列默认值，不能再追加普通类型长度。
  if (column.autoIncrement && dbType === "postgres") {
    if (type === "BIGINT") return "BIGSERIAL";
    if (type === "SMALLINT") return "SMALLSERIAL";
    if (["INTEGER", "INT"].includes(type)) return "SERIAL";
  }
  if (column.length.trim()) {
    type += column.scale.trim() ? `(${column.length.trim()}, ${column.scale.trim()})` : `(${column.length.trim()})`;
  }
  return type;
}

function normalized(value: string) {
  return value.trim().toLocaleLowerCase();
}

function validPositiveInteger(value: string) {
  return /^[1-9]\d*$/.test(value);
}

function validNonNegativeInteger(value: string) {
  return /^\d+$/.test(value);
}

export function validateCreateTableDraft(draft: CreateTableDraft, dbType?: MobileDatabaseType | string): string[] {
  const errors: string[] = [];
  if (!draft.name.trim()) errors.push("请填写表名");
  if (!draft.columns.length) errors.push("至少添加一个字段");
  const names = new Set<string>();
  for (const [index, column] of draft.columns.entries()) {
    const name = column.name.trim();
    if (!name) errors.push(`第 ${index + 1} 个字段缺少名称`);
    if (!column.dataType.trim()) errors.push(`字段 ${name || index + 1} 缺少类型`);
    const normalizedName = normalized(name);
    if (name && names.has(normalizedName)) errors.push(`字段名称重复：${name}`);
    if (name) names.add(normalizedName);
    if (column.scale.trim() && !column.length.trim()) errors.push(`字段 ${name || index + 1} 设置小数位时必须填写长度`);
    if (column.length.trim() && !validPositiveInteger(column.length.trim())) errors.push(`字段 ${name || index + 1} 的长度必须是正整数`);
    if (column.scale.trim() && !validNonNegativeInteger(column.scale.trim())) errors.push(`字段 ${name || index + 1} 的小数位必须是非负整数`);
    if (validPositiveInteger(column.length.trim()) && validNonNegativeInteger(column.scale.trim())
      && Number(column.scale) > Number(column.length)) errors.push(`字段 ${name || index + 1} 的小数位不能大于长度`);
    if (dbType && supportedTableDialect(dbType) && !dataTypesByDatabase[dbType].includes(column.dataType.trim().toUpperCase())) {
      errors.push(`字段 ${name || index + 1} 使用了不支持的类型：${column.dataType}`);
    }
    const autoIncrementTypes: Record<"postgres" | "mysql" | "sqlserver", Set<string>> = {
      postgres: new Set(["BIGINT", "INTEGER", "INT", "SMALLINT"]),
      mysql: new Set(["BIGINT", "INT", "SMALLINT", "TINYINT"]),
      sqlserver: new Set(["BIGINT", "INT", "SMALLINT"]),
    };
    if (column.autoIncrement && dbType && supportedTableDialect(dbType) && !autoIncrementTypes[dbType].has(column.dataType.trim().toUpperCase())) {
      errors.push(`字段 ${name || index + 1} 的类型不支持自增`);
    }
  }
  const objectNames = new Set<string>();
  for (const index of draft.indexes) {
    if (!index.name.trim() || !index.columns.trim()) errors.push("索引必须填写名称和字段");
    const indexName = normalized(index.name);
    if (indexName && objectNames.has(indexName)) errors.push(`索引或约束名称重复：${index.name}`);
    if (indexName) objectNames.add(indexName);
    for (const column of index.columns.split(/[,，、]+/).map(normalized).filter(Boolean)) {
      if (!names.has(column)) errors.push(`索引 ${index.name || "未命名"} 引用了不存在的字段：${column}`);
    }
  }
  for (const foreignKey of draft.foreignKeys) {
    if (![foreignKey.name, foreignKey.column, foreignKey.referenceTable, foreignKey.referenceColumn].every((value) => value.trim())) {
      errors.push("外键必须填写名称、本表字段、引用表和引用字段");
    }
    const keyName = normalized(foreignKey.name);
    if (keyName && objectNames.has(keyName)) errors.push(`索引或约束名称重复：${foreignKey.name}`);
    if (keyName) objectNames.add(keyName);
    if (foreignKey.column.trim() && !names.has(normalized(foreignKey.column))) {
      errors.push(`外键 ${foreignKey.name || "未命名"} 引用了不存在的本表字段：${foreignKey.column}`);
    }
  }
  for (const check of draft.checks) {
    if (!check.name.trim() || !check.expression.trim()) errors.push("检查约束必须填写名称和表达式");
    const checkName = normalized(check.name);
    if (checkName && objectNames.has(checkName)) errors.push(`索引或约束名称重复：${check.name}`);
    if (checkName) objectNames.add(checkName);
  }
  for (const trigger of draft.triggers) {
    if (!trigger.name.trim() || !trigger.sql.trim()) errors.push("触发器必须填写名称和 SQL");
  }
  if (dbType === "sqlserver" && draft.temporary) errors.push("SQL Server 临时表只在单个连接会话中有效，当前直连模式不支持");
  if (dbType === "mysql") {
    for (const [label, value] of [["存储引擎", draft.engine], ["字符集", draft.charset], ["排序规则", draft.collation]] as const) {
      if (value.trim() && !/^[A-Za-z0-9_]+$/.test(value.trim())) errors.push(`${label}只能包含字母、数字和下划线`);
    }
  }
  return [...new Set(errors)];
}

export function buildCreateTableSql(draft: CreateTableDraft, dbType: MobileDatabaseType | string): string {
  const errors = validateCreateTableDraft(draft, dbType);
  if (errors.length) return `-- ${errors.join("\n-- ")}`;

  const target = qualified(draft.schema, draft.name, dbType);
  const primaryColumns = draft.columns.filter((column) => column.primaryKey);
  const definitions = draft.columns.map((column) => {
    const parts = [quoteIdentifier(column.name.trim(), dbType), columnType(column, dbType)];
    if (column.autoIncrement && dbType === "mysql") parts.push("AUTO_INCREMENT");
    if (column.autoIncrement && dbType === "sqlserver") parts.push("IDENTITY(1,1)");
    if (!column.nullable || column.primaryKey) parts.push("NOT NULL");
    if (column.unique && !column.primaryKey) parts.push("UNIQUE");
    if (column.defaultValue.trim()) parts.push(`DEFAULT ${column.defaultValue.trim()}`);
    if (column.comment.trim() && dbType === "mysql") parts.push(`COMMENT ${literal(column.comment.trim())}`);
    return `  ${parts.join(" ")}`;
  });

  if (primaryColumns.length) {
    definitions.push(`  CONSTRAINT ${quoteIdentifier(`pk_${draft.name}`, dbType)} PRIMARY KEY (${primaryColumns.map((column) => quoteIdentifier(column.name.trim(), dbType)).join(", ")})`);
  }
  for (const foreignKey of draft.foreignKeys) {
    const referenceTarget = qualified(foreignKey.referenceSchema, foreignKey.referenceTable, dbType);
    const actions = [foreignKey.onUpdate && `ON UPDATE ${foreignKey.onUpdate}`, foreignKey.onDelete && `ON DELETE ${foreignKey.onDelete}`].filter(Boolean).join(" ");
    definitions.push(`  CONSTRAINT ${quoteIdentifier(foreignKey.name.trim(), dbType)} FOREIGN KEY (${quoteIdentifier(foreignKey.column.trim(), dbType)}) REFERENCES ${referenceTarget} (${quoteIdentifier(foreignKey.referenceColumn.trim(), dbType)})${actions ? ` ${actions}` : ""}`);
  }
  for (const check of draft.checks) {
    definitions.push(`  CONSTRAINT ${quoteIdentifier(check.name.trim(), dbType)} CHECK (${check.expression.trim()})`);
  }

  const createPrefix = dbType === "sqlserver" && draft.ifNotExists
    ? `IF OBJECT_ID(N${literal(`${draft.schema ? `${draft.schema}.` : ""}${draft.name}`)}, N'U') IS NULL\n`
    : "";
  const temporary = draft.temporary ? (dbType === "sqlserver" ? "" : "TEMPORARY ") : "";
  const ifNotExists = draft.ifNotExists && dbType !== "sqlserver" ? "IF NOT EXISTS " : "";
  let create = `${createPrefix}CREATE ${temporary}TABLE ${ifNotExists}${target} (\n${definitions.join(",\n")}\n)`;
  if (dbType === "mysql") {
    if (draft.engine.trim()) create += ` ENGINE=${draft.engine.trim()}`;
    if (draft.charset.trim()) create += ` DEFAULT CHARSET=${draft.charset.trim()}`;
    if (draft.collation.trim()) create += ` COLLATE=${draft.collation.trim()}`;
    if (draft.comment.trim()) create += ` COMMENT=${literal(draft.comment.trim())}`;
  }
  create += ";";

  const statements = [create];
  for (const index of draft.indexes) {
    const columns = index.columns.split(/[,，、]+/).map((value) => value.trim()).filter(Boolean);
    statements.push(`CREATE ${index.unique ? "UNIQUE " : ""}INDEX ${quoteIdentifier(index.name.trim(), dbType)} ON ${target} (${columns.map((column) => quoteIdentifier(column, dbType)).join(", ")});`);
  }
  if (draft.comment.trim() && dbType === "postgres") {
    statements.push(`COMMENT ON TABLE ${target} IS ${literal(draft.comment.trim())};`);
  }
  if (dbType === "postgres") {
    for (const column of draft.columns.filter((item) => item.comment.trim())) {
      statements.push(`COMMENT ON COLUMN ${target}.${quoteIdentifier(column.name.trim(), dbType)} IS ${literal(column.comment.trim())};`);
    }
  }
  // 触发器和 CHECK 表达式无法跨数据库可靠结构化，保持用户输入原样，并始终先送到查询工作台预览；
  // 最终执行仍经过原生只读、写入确认和生产连接名称确认三层安全门。
  for (const trigger of draft.triggers) statements.push(trigger.sql.trim().replace(/;?$/, ";"));
  return statements.join("\n\n");
}
