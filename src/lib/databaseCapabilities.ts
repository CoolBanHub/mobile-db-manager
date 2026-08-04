import type { MobileDatabaseType } from "./mobileTypes";

export type MobileBrowseMode = "relational" | "redis" | "mongo" | "etcd" | "unsupported";

export interface MobileDatabaseCapability {
  value: MobileDatabaseType;
  label: string;
  port: number;
  defaultHost: string;
  defaultUsername: string;
  defaultDatabase: string | null;
  browse: MobileBrowseMode;
  sql: boolean;
}

// 连接表单、导航模式和默认值共用这一张能力表，避免各页面分别判断数据库类型。
export const mobileDatabaseCapabilities: MobileDatabaseCapability[] = [
  { value: "postgres", label: "PostgreSQL", port: 5432, defaultHost: "127.0.0.1", defaultUsername: "postgres", defaultDatabase: "postgres", browse: "relational", sql: true },
  { value: "mysql", label: "MySQL", port: 3306, defaultHost: "127.0.0.1", defaultUsername: "root", defaultDatabase: null, browse: "relational", sql: true },
  { value: "sqlserver", label: "SQL Server", port: 1433, defaultHost: "127.0.0.1", defaultUsername: "sa", defaultDatabase: "master", browse: "relational", sql: true },
  { value: "mongodb", label: "MongoDB", port: 27017, defaultHost: "127.0.0.1", defaultUsername: "", defaultDatabase: null, browse: "mongo", sql: false },
  { value: "redis", label: "Redis", port: 6379, defaultHost: "127.0.0.1", defaultUsername: "", defaultDatabase: "0", browse: "redis", sql: false },
  { value: "etcd", label: "etcd", port: 2379, defaultHost: "127.0.0.1", defaultUsername: "", defaultDatabase: null, browse: "etcd", sql: false },
];

const capabilityByType = new Map<string, MobileDatabaseCapability>(
  mobileDatabaseCapabilities.map((item) => [item.value, item]),
);

export function databaseCapability(dbType: string): MobileDatabaseCapability {
  // 旧版本或未来版本产生的未知类型仍可展示，但默认关闭查询能力，采取安全降级。
  return (
    capabilityByType.get(dbType) ?? {
      value: "postgres",
      label: dbType,
      port: 1,
      defaultHost: "127.0.0.1",
      defaultUsername: "",
      defaultDatabase: null,
      browse: "unsupported",
      sql: false,
    }
  );
}

export function isMobileSqlDatabase(dbType: string): boolean {
  return databaseCapability(dbType).sql;
}
