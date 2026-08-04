import { DirectDatabase, requireNative } from "./native";

export type DirectMetadataKind =
  | "databases"
  | "schemas"
  | "tables"
  | "columns"
  | "indexes"
  | "foreign-keys"
  | "objects";

export interface DirectMetadataRequest {
  connectionId: string;
  database?: string;
  schema?: string;
  table?: string;
  filter?: string;
  limit?: number;
  offset?: number;
}

export async function loadDirectMetadata<T>(
  kind: DirectMetadataKind,
  request: DirectMetadataRequest,
): Promise<T> {
  requireNative();
  return (
    await DirectDatabase.metadata({
      kind,
      connectionId: request.connectionId,
      database: request.database ?? "",
      schema: request.schema ?? "",
      table: request.table ?? "",
      filter: request.filter ?? "",
      limit: request.limit ?? 100,
      offset: request.offset ?? 0,
    })
  ).value as T;
}

