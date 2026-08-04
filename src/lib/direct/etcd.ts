import type { MobileEtcdEntry, MobileEtcdOverview, MobileEtcdPage } from "../mobileTypes";
import { DirectDatabase, requireNative } from "./native";

async function directEtcd<T>(options: Record<string, unknown>): Promise<T> {
  requireNative();
  return (await DirectDatabase.etcd(options)).value as T;
}

export function loadDirectEtcdOverview(connectionId: string): Promise<MobileEtcdOverview> {
  return directEtcd<MobileEtcdOverview>({ connectionId, action: "overview" });
}

export function loadDirectEtcdEntries(
  connectionId: string,
  prefix: string,
  limit = 200,
): Promise<MobileEtcdPage> {
  return directEtcd<MobileEtcdPage>({ connectionId, action: "list", prefix, limit });
}

export function loadDirectEtcdEntry(connectionId: string, key: string): Promise<MobileEtcdEntry> {
  return directEtcd<MobileEtcdEntry>({ connectionId, action: "detail", key });
}

export function mutateDirectEtcd(
  connectionId: string,
  action: "put" | "delete",
  key: string,
  value: string,
  productionConfirmation?: string,
  lease = "0",
): Promise<Record<string, unknown>> {
  // lease 使用字符串传递，避免 JavaScript number 丢失 etcd 的 64 位租约精度。
  return directEtcd<Record<string, unknown>>({
    connectionId,
    action,
    key,
    value,
    lease,
    confirmedWrite: true,
    productionConfirmation: productionConfirmation ?? "",
  });
}

