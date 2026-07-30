import { describe, expect, it } from "vitest";
import { databaseCapability, isMobileSqlDatabase, mobileDatabaseCapabilities } from "./databaseCapabilities";

describe("mobile database capabilities", () => {
  it("只暴露安卓原生层实际内置的五种数据库", () => {
    expect(mobileDatabaseCapabilities.map((item) => item.value)).toEqual([
      "postgres",
      "mysql",
      "sqlserver",
      "mongodb",
      "redis",
    ]);
  });

  it("只允许关系型原生驱动进入 SQL 与元数据浏览", () => {
    expect(databaseCapability("mongodb").browse).toBe("unsupported");
    expect(databaseCapability("redis").browse).toBe("unsupported");
    expect(isMobileSqlDatabase("postgres")).toBe(true);
    expect(isMobileSqlDatabase("mysql")).toBe(true);
    expect(isMobileSqlDatabase("sqlserver")).toBe(true);
  });

  it("对未知或旧版连接类型保持关闭", () => {
    expect(databaseCapability("future-database").value).toBe("postgres");
    expect(databaseCapability("future-database").browse).toBe("unsupported");
    expect(isMobileSqlDatabase("future-database")).toBe(false);
    expect(isMobileSqlDatabase("jdbc")).toBe(false);
  });
});
