import { describe, expect, it } from "vitest";
import { databaseCapability, isMobileSqlDatabase, mobileDatabaseCapabilities } from "./databaseCapabilities";

describe("mobile database capabilities", () => {
  it("只暴露安卓原生层实际内置的六种数据库", () => {
    expect(mobileDatabaseCapabilities.map((item) => item.value)).toEqual([
      "postgres",
      "mysql",
      "sqlserver",
      "mongodb",
      "redis",
      "etcd",
    ]);
  });

  it("只允许关系型原生驱动进入 SQL 与元数据浏览", () => {
    expect(databaseCapability("mongodb").browse).toBe("mongo");
    expect(databaseCapability("redis").browse).toBe("redis");
    expect(databaseCapability("etcd").browse).toBe("etcd");
    expect(isMobileSqlDatabase("postgres")).toBe(true);
    expect(isMobileSqlDatabase("mysql")).toBe(true);
    expect(isMobileSqlDatabase("sqlserver")).toBe(true);
  });

  it("为新建连接提供常用本机默认配置", () => {
    expect(databaseCapability("postgres")).toMatchObject({
      defaultHost: "127.0.0.1",
      port: 5432,
      defaultUsername: "postgres",
      defaultDatabase: "postgres",
    });
    expect(databaseCapability("redis")).toMatchObject({
      defaultHost: "127.0.0.1",
      port: 6379,
      defaultUsername: "",
      defaultDatabase: "0",
    });
    expect(databaseCapability("sqlserver")).toMatchObject({
      defaultHost: "127.0.0.1",
      port: 1433,
      defaultUsername: "sa",
      defaultDatabase: "master",
    });
    expect(databaseCapability("etcd")).toMatchObject({
      defaultHost: "127.0.0.1",
      port: 2379,
      defaultUsername: "",
      defaultDatabase: null,
    });
  });

  it("对未知或旧版连接类型保持关闭", () => {
    expect(databaseCapability("future-database").value).toBe("postgres");
    expect(databaseCapability("future-database").browse).toBe("unsupported");
    expect(isMobileSqlDatabase("future-database")).toBe(false);
    expect(isMobileSqlDatabase("jdbc")).toBe(false);
  });
});
