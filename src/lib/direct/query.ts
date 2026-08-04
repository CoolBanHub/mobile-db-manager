import type { QueryResult } from "../mobileTypes";
import { listDirectConnections } from "./connections";
import { saveHistory } from "./history";
import { DirectApiError, DirectDatabase, requireNative } from "./native";

export async function executeDirectQuery(
  body: Record<string, unknown>,
  readOnly = true,
  recordHistory = true,
): Promise<QueryResult> {
  requireNative();
  const started = Date.now();
  try {
    // WebView 只传连接 ID 和本次查询参数；账号、密码始终由原生保险箱读取。
    const result = (
      await DirectDatabase.query({
        connectionId: body.connectionId,
        database: body.database,
        schema: body.schema,
        sql: body.sql,
        executionId: body.executionId,
        offset: Number(body.offset ?? 0),
        pageSize: Number(body.pageSize ?? 50),
        readOnly,
        confirmedWrite: body.confirmedWrite,
        productionConfirmation: body.productionConfirmation,
      })
    ).value;
    if (recordHistory) saveHistory(body, result, null, started);
    return result;
  } catch (error) {
    if (recordHistory) saveHistory(body, null, error, started);
    throw error;
  }
}

export async function cancelDirectQuery(executionId: string): Promise<void> {
  requireNative();
  await DirectDatabase.cancel({ executionId });
}

export async function explainDirectQuery(body: Record<string, unknown>): Promise<string> {
  const connection = (await listDirectConnections()).find((item) => item.id === body.connectionId);
  if (!connection) throw new DirectApiError("连接不存在");
  if (connection.dbType === "sqlserver") {
    throw new DirectApiError("SQL Server 执行计划需要专用 SHOWPLAN 会话，当前安卓版本暂未开放");
  }
  if (connection.dbType !== "postgres" && connection.dbType !== "mysql") {
    throw new DirectApiError("当前数据库类型不支持 SQL 执行计划");
  }
  const result = await executeDirectQuery(
    { ...body, sql: `EXPLAIN ${String(body.sql ?? "")}`, offset: 0, pageSize: 200 },
    true,
    false,
  );
  return result.rows.map((row) => row.join(" | ")).join("\n");
}

