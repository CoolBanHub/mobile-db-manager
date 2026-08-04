import type { MobileMongoDocumentPage } from "../mobileTypes";
import { DirectDatabase, requireNative } from "./native";

async function directMongo<T>(options: Record<string, unknown>): Promise<T> {
  requireNative();
  return (await DirectDatabase.mongo(options)).value as T;
}

export function loadDirectMongoDatabases(connectionId: string): Promise<string[]> {
  return directMongo<string[]>({ connectionId, action: "databases" });
}

export function loadDirectMongoCollections(
  connectionId: string,
  database: string,
): Promise<string[]> {
  return directMongo<string[]>({ connectionId, database, action: "collections" });
}

export function loadDirectMongoDocuments(
  connectionId: string,
  database: string,
  collection: string,
  filter: string,
  offset: number,
  limit = 25,
): Promise<MobileMongoDocumentPage> {
  return directMongo<MobileMongoDocumentPage>({
    connectionId,
    database,
    collection,
    filter,
    offset,
    limit,
    action: "documents",
  });
}

export function mutateDirectMongo(
  connectionId: string,
  database: string,
  collection: string,
  action: "insert" | "replace" | "delete",
  payload: Record<string, unknown>,
  productionConfirmation?: string,
): Promise<Record<string, unknown>> {
  // payload 仅包含当前操作需要的文档，连接凭据不会进入 JavaScript 运行时。
  return directMongo<Record<string, unknown>>({
    connectionId,
    database,
    collection,
    action,
    ...payload,
    confirmedWrite: true,
    productionConfirmation: productionConfirmation ?? "",
  });
}

