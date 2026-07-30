export type MobileBrowseMode = "relational" | "mongodb" | "redis" | "unsupported";

export interface MobileDatabaseCapability {
  value: string;
  label: string;
  port: number;
  browse: MobileBrowseMode;
  sql: boolean;
  local?: boolean;
}

export const mobileDatabaseCapabilities: MobileDatabaseCapability[] = [
  { value: "postgres", label: "PostgreSQL", port: 5432, browse: "relational", sql: true },
  { value: "mysql", label: "MySQL", port: 3306, browse: "relational", sql: true },
  { value: "sqlserver", label: "SQL Server", port: 1433, browse: "relational", sql: true },
  { value: "oracle", label: "Oracle", port: 1521, browse: "relational", sql: true },
  { value: "sqlite", label: "SQLite", port: 1, browse: "relational", sql: true, local: true },
  { value: "duckdb", label: "DuckDB", port: 1, browse: "relational", sql: true, local: true },
  { value: "clickhouse", label: "ClickHouse", port: 8123, browse: "relational", sql: true },
  { value: "rqlite", label: "rqlite", port: 4001, browse: "relational", sql: true },
  { value: "turso", label: "Turso", port: 443, browse: "relational", sql: true },
  { value: "cloudflare-d1", label: "Cloudflare D1", port: 443, browse: "relational", sql: true },
  { value: "doris", label: "Apache Doris", port: 9030, browse: "relational", sql: true },
  { value: "starrocks", label: "StarRocks", port: 9030, browse: "relational", sql: true },
  { value: "manticoresearch", label: "Manticore Search", port: 9306, browse: "relational", sql: true },
  { value: "databend", label: "Databend", port: 8000, browse: "relational", sql: true },
  { value: "redshift", label: "Amazon Redshift", port: 5439, browse: "relational", sql: true },
  { value: "dameng", label: "达梦", port: 5236, browse: "relational", sql: true },
  { value: "gaussdb", label: "GaussDB", port: 8000, browse: "relational", sql: true },
  { value: "kingbase", label: "人大金仓", port: 54321, browse: "relational", sql: true },
  { value: "highgo", label: "瀚高", port: 5866, browse: "relational", sql: true },
  { value: "uxdb", label: "优炫", port: 5432, browse: "relational", sql: true },
  { value: "vastbase", label: "海量", port: 5432, browse: "relational", sql: true },
  { value: "goldendb", label: "GoldenDB", port: 3306, browse: "relational", sql: true },
  { value: "kwdb", label: "KWDB", port: 26257, browse: "relational", sql: true },
  { value: "yashandb", label: "YashanDB", port: 1688, browse: "relational", sql: true },
  { value: "opengauss", label: "openGauss", port: 5432, browse: "relational", sql: true },
  { value: "oceanbase-oracle", label: "OceanBase Oracle", port: 2881, browse: "relational", sql: true },
  { value: "databricks", label: "Databricks", port: 443, browse: "relational", sql: true },
  { value: "saphana", label: "SAP HANA", port: 30015, browse: "relational", sql: true },
  { value: "teradata", label: "Teradata", port: 1025, browse: "relational", sql: true },
  { value: "vertica", label: "Vertica", port: 5433, browse: "relational", sql: true },
  { value: "firebird", label: "Firebird", port: 3050, browse: "relational", sql: true },
  { value: "exasol", label: "Exasol", port: 8563, browse: "relational", sql: true },
  { value: "questdb", label: "QuestDB", port: 8812, browse: "relational", sql: true },
  { value: "gbase", label: "GBase", port: 5258, browse: "relational", sql: true },
  { value: "access", label: "Microsoft Access", port: 1, browse: "relational", sql: true, local: true },
  { value: "h2", label: "H2", port: 9092, browse: "relational", sql: true },
  { value: "snowflake", label: "Snowflake", port: 443, browse: "relational", sql: true },
  { value: "trino", label: "Trino", port: 8080, browse: "relational", sql: true },
  { value: "prestosql", label: "PrestoSQL", port: 8080, browse: "relational", sql: true },
  { value: "hive", label: "Apache Hive", port: 10000, browse: "relational", sql: true },
  { value: "spark", label: "Apache Spark", port: 10000, browse: "relational", sql: true },
  { value: "db2", label: "IBM Db2", port: 50000, browse: "relational", sql: true },
  { value: "informix", label: "Informix", port: 9088, browse: "relational", sql: true },
  { value: "bigquery", label: "BigQuery", port: 443, browse: "relational", sql: true },
  { value: "kylin", label: "Apache Kylin", port: 7070, browse: "relational", sql: true },
  { value: "sundb", label: "SunDB", port: 22581, browse: "relational", sql: true },
  { value: "oscar", label: "神通", port: 2003, browse: "relational", sql: true },
  { value: "tdengine", label: "TDengine", port: 6041, browse: "relational", sql: true },
  { value: "xugu", label: "虚谷", port: 5138, browse: "relational", sql: true },
  { value: "iotdb", label: "IoTDB", port: 6667, browse: "relational", sql: true },
  { value: "iris", label: "InterSystems IRIS", port: 1972, browse: "relational", sql: true },
  { value: "jdbc", label: "JDBC", port: 1, browse: "relational", sql: true },
  { value: "mongodb", label: "MongoDB", port: 27017, browse: "mongodb", sql: false },
  { value: "redis", label: "Redis", port: 6379, browse: "redis", sql: false },
  { value: "elasticsearch", label: "Elasticsearch", port: 9200, browse: "unsupported", sql: false },
  { value: "hbase", label: "HBase", port: 16000, browse: "unsupported", sql: false },
  { value: "qdrant", label: "Qdrant", port: 6333, browse: "unsupported", sql: false },
  { value: "milvus", label: "Milvus", port: 19530, browse: "unsupported", sql: false },
  { value: "weaviate", label: "Weaviate", port: 8080, browse: "unsupported", sql: false },
  { value: "chromadb", label: "ChromaDB", port: 8000, browse: "unsupported", sql: false },
  { value: "neo4j", label: "Neo4j", port: 7687, browse: "unsupported", sql: false },
  { value: "cassandra", label: "Cassandra", port: 9042, browse: "unsupported", sql: false },
  { value: "etcd", label: "etcd", port: 2379, browse: "unsupported", sql: false },
  { value: "zookeeper", label: "ZooKeeper", port: 2181, browse: "unsupported", sql: false },
  { value: "nacos", label: "Nacos", port: 8848, browse: "unsupported", sql: false },
  { value: "influxdb", label: "InfluxDB", port: 8086, browse: "unsupported", sql: false },
  { value: "mq", label: "消息队列", port: 9092, browse: "unsupported", sql: false },
];

const capabilityByType = new Map(mobileDatabaseCapabilities.map((item) => [item.value, item]));

export function databaseCapability(dbType: string): MobileDatabaseCapability {
  return (
    capabilityByType.get(dbType) ?? {
      value: dbType,
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
