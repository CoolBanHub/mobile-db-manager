import type { MobileDatabaseType } from "./mobileTypes";

export type MobileBrowseMode = "relational" | "unsupported";

export interface MobileDatabaseCapability {
  value: MobileDatabaseType;
  label: string;
  port: number;
  browse: MobileBrowseMode;
  sql: boolean;
}

export const mobileDatabaseCapabilities: MobileDatabaseCapability[] = [
  { value: "postgres", label: "PostgreSQL", port: 5432, browse: "relational", sql: true },
  { value: "mysql", label: "MySQL", port: 3306, browse: "relational", sql: true },
  { value: "sqlserver", label: "SQL Server", port: 1433, browse: "relational", sql: true },
  { value: "mongodb", label: "MongoDB", port: 27017, browse: "unsupported", sql: false },
  { value: "redis", label: "Redis", port: 6379, browse: "unsupported", sql: false },
];

const capabilityByType = new Map<string, MobileDatabaseCapability>(
  mobileDatabaseCapabilities.map((item) => [item.value, item]),
);

export function databaseCapability(dbType: string): MobileDatabaseCapability {
  return (
    capabilityByType.get(dbType) ?? {
      value: "postgres",
      label: dbType,
      port: 1,
      browse: "unsupported",
      sql: false,
    }
  );
}

export function isMobileSqlDatabase(dbType: string): boolean {
  return databaseCapability(dbType).sql;
}
