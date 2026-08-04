import type {
  MobileRedisKeyDetail,
  MobileRedisOverview,
  MobileRedisScanPage,
} from "../mobileTypes";
import { DirectDatabase, requireNative } from "./native";

async function directRedis<T>(options: Record<string, unknown>): Promise<T> {
  requireNative();
  return (await DirectDatabase.redis(options)).value as T;
}

// Redis 只暴露动作白名单，WebView 不能下发任意 Redis 命令。
export function loadDirectRedisOverview(connectionId: string, database: number): Promise<MobileRedisOverview> {
  return directRedis<MobileRedisOverview>({
    connectionId,
    database: String(database),
    action: "overview",
  });
}

export function scanDirectRedisKeys(
  connectionId: string,
  database: number,
  cursor: string,
  pattern: string,
): Promise<MobileRedisScanPage> {
  return directRedis<MobileRedisScanPage>({
    connectionId,
    database: String(database),
    action: "scan",
    cursor,
    pattern,
    count: 100,
  });
}

export function loadDirectRedisKey(
  connectionId: string,
  database: number,
  key: string,
): Promise<MobileRedisKeyDetail> {
  return directRedis<MobileRedisKeyDetail>({
    connectionId,
    database: String(database),
    action: "detail",
    key,
  });
}

export function mutateDirectRedis(
  connectionId: string,
  database: number,
  action: string,
  payload: Record<string, unknown>,
  productionConfirmation?: string,
): Promise<{ result: unknown }> {
  // confirmedWrite 只是用户界面的显式确认；只读和生产环境校验仍在原生层强制执行。
  return directRedis<{ result: unknown }>({
    connectionId,
    database: String(database),
    action,
    ...payload,
    confirmedWrite: true,
    productionConfirmation: productionConfirmation ?? "",
  });
}

