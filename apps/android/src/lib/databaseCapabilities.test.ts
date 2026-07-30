import { describe, expect, it } from "vitest";
import { databaseCapability, isMobileSqlDatabase, mobileDatabaseCapabilities } from "./databaseCapabilities";

describe("mobile database capabilities", () => {
  it("contains every desktop connection type without duplicates", () => {
    expect(new Set(mobileDatabaseCapabilities.map((item) => item.value)).size).toBe(67);
    expect(mobileDatabaseCapabilities).toHaveLength(67);
  });

  it("routes only supported metadata models into relational browsing", () => {
    expect(databaseCapability("mongodb").browse).toBe("unsupported");
    expect(databaseCapability("redis").browse).toBe("unsupported");
    for (const type of ["elasticsearch", "etcd", "zookeeper", "hbase", "nacos", "mq", "qdrant"]) {
      expect(databaseCapability(type).browse).toBe("unsupported");
      expect(isMobileSqlDatabase(type)).toBe(false);
    }
    expect(isMobileSqlDatabase("postgres")).toBe(true);
    expect(isMobileSqlDatabase("jdbc")).toBe(true);
  });

  it("fails closed for a connection type introduced by a newer server", () => {
    expect(databaseCapability("future-database").browse).toBe("unsupported");
    expect(isMobileSqlDatabase("future-database")).toBe(false);
  });
});
