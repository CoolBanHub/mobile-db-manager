import { describe, expect, it } from "vitest";
import { buildCreateTableSql, defaultCreateTableDraft, validateCreateTableDraft } from "./tableDefinition";

describe("tableDefinition", () => {
  it("requires a table name and valid columns", () => {
    const draft = defaultCreateTableDraft("public");
    expect(validateCreateTableDraft(draft)).toContain("请填写表名");
    draft.name = "orders";
    expect(validateCreateTableDraft(draft)).toEqual([]);
  });

  it("builds PostgreSQL identity, constraints, indexes, and comments", () => {
    const draft = defaultCreateTableDraft("public");
    draft.name = "orders";
    draft.comment = "订单";
    draft.columns[0].comment = "主键";
    draft.indexes.push({ id: 2, name: "idx_orders_id", columns: "id", unique: false });
    draft.checks.push({ id: 3, name: "chk_orders_id", expression: "id > 0" });

    const sql = buildCreateTableSql(draft, "postgres");

    expect(sql).toContain('CREATE TABLE "public"."orders"');
    expect(sql).toContain('"id" BIGSERIAL NOT NULL');
    expect(sql).toContain('CONSTRAINT "pk_orders" PRIMARY KEY ("id")');
    expect(sql).toContain('CONSTRAINT "chk_orders_id" CHECK (id > 0)');
    expect(sql).toContain('CREATE INDEX "idx_orders_id" ON "public"."orders" ("id");');
    expect(sql).toContain("COMMENT ON TABLE \"public\".\"orders\" IS '订单';");
    expect(sql).toContain("COMMENT ON COLUMN \"public\".\"orders\".\"id\" IS '主键';");
  });

  it("uses SMALLSERIAL for PostgreSQL small integer identities", () => {
    const draft = defaultCreateTableDraft("public");
    draft.name = "small_ids";
    draft.columns[0].dataType = "SMALLINT";

    expect(buildCreateTableSql(draft, "postgres")).toContain('"id" SMALLSERIAL NOT NULL');
  });

  it("builds MySQL table options and inline comments", () => {
    const draft = defaultCreateTableDraft("");
    draft.name = "users";
    draft.comment = "用户";
    draft.columns[0].comment = "编号";

    const sql = buildCreateTableSql(draft, "mysql");

    expect(sql).toContain("CREATE TABLE `users`");
    expect(sql).toContain("`id` BIGINT AUTO_INCREMENT NOT NULL COMMENT '编号'");
    expect(sql).toContain("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';");
  });

  it("builds SQL Server identity and escaped identifiers", () => {
    const draft = defaultCreateTableDraft("dbo");
    draft.name = "order]item";
    draft.ifNotExists = true;

    const sql = buildCreateTableSql(draft, "sqlserver");

    expect(sql).toContain("IF OBJECT_ID(N'dbo.order]item', N'U') IS NULL");
    expect(sql).toContain("CREATE TABLE [dbo].[order]]item]");
    expect(sql).toContain("[id] BIGINT IDENTITY(1,1) NOT NULL");
  });

  it("rejects invalid field references, numeric sizes, and MySQL options", () => {
    const draft = defaultCreateTableDraft("");
    draft.name = "orders";
    draft.columns[0].length = "10; DROP TABLE users";
    draft.indexes.push({ id: 2, name: "idx_orders_user", columns: "missing", unique: false });
    draft.foreignKeys.push({
      id: 3,
      name: "fk_orders_user",
      column: "missing",
      referenceSchema: "",
      referenceTable: "users",
      referenceColumn: "id",
      onUpdate: "",
      onDelete: "CASCADE",
    });
    draft.engine = "InnoDB; DROP TABLE users";

    const errors = validateCreateTableDraft(draft, "mysql");
    expect(errors).toContain("字段 id 的长度必须是正整数");
    expect(errors).toContain("索引 idx_orders_user 引用了不存在的字段：missing");
    expect(errors).toContain("外键 fk_orders_user 引用了不存在的本表字段：missing");
    expect(errors).toContain("存储引擎只能包含字母、数字和下划线");
  });

  it("rejects SQL Server temporary tables because each query uses a separate connection", () => {
    const draft = defaultCreateTableDraft("dbo");
    draft.name = "scratch";
    draft.temporary = true;

    expect(validateCreateTableDraft(draft, "sqlserver")).toContain("SQL Server 临时表只在单个连接会话中有效，当前直连模式不支持");
  });
});
