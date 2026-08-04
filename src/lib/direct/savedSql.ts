import type { SavedSqlFile, SavedSqlFolder, SavedSqlLibrary } from "../mobileTypes";
import { id, readJson, writeJson } from "./localStore";

const SAVED_SQL_KEY = "mobile-db-manager.direct.saved-sql.v1";

export function loadDirectSavedSqlLibrary(): SavedSqlLibrary {
  return readJson<SavedSqlLibrary>(SAVED_SQL_KEY, { folders: [], files: [] });
}

function saveLibrary(value: SavedSqlLibrary) {
  writeJson(SAVED_SQL_KEY, value);
}

export function saveDirectSavedSql(body: Record<string, unknown>): SavedSqlFile {
  const current = loadDirectSavedSqlLibrary();
  const now = new Date().toISOString();
  const existing = current.files.find((item) => item.id === body.id);
  const file: SavedSqlFile = {
    id: existing?.id ?? id(),
    connectionId: String(body.connectionId ?? existing?.connectionId ?? ""),
    folderId: body.folderId === undefined ? (existing?.folderId ?? null) : body.folderId ? String(body.folderId) : null,
    name: String(body.name ?? existing?.name ?? "query.sql").replace(/\.sql$/i, "") + ".sql",
    database: String(body.database ?? existing?.database ?? ""),
    schema: body.schema === undefined ? (existing?.schema ?? null) : body.schema ? String(body.schema) : null,
    sql: String(body.sql ?? existing?.sql ?? ""),
    sqlLoaded: true,
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  };
  current.files = [file, ...current.files.filter((item) => item.id !== file.id)];
  saveLibrary(current);
  return file;
}

export function loadDirectSavedSql(savedSqlId: string): SavedSqlFile | null {
  return loadDirectSavedSqlLibrary().files.find((item) => item.id === savedSqlId) ?? null;
}

export function deleteDirectSavedSql(savedSqlId: string): void {
  const current = loadDirectSavedSqlLibrary();
  current.files = current.files.filter((item) => item.id !== savedSqlId);
  saveLibrary(current);
}

export function saveDirectSavedSqlFolder(payload: Record<string, unknown>): SavedSqlFolder {
  const current = loadDirectSavedSqlLibrary();
  const now = new Date().toISOString();
  const existing = current.folders.find((item) => item.id === payload.id);
  const folder: SavedSqlFolder = {
    id: existing?.id ?? id(),
    connectionId: String(payload.connectionId ?? existing?.connectionId ?? ""),
    parentFolderId:
      payload.parentFolderId === undefined
        ? (existing?.parentFolderId ?? null)
        : payload.parentFolderId
          ? String(payload.parentFolderId)
          : null,
    name: String(payload.name ?? existing?.name ?? "新建文件夹"),
    updatedAt: now,
  };
  current.folders = [folder, ...current.folders.filter((item) => item.id !== folder.id)];
  saveLibrary(current);
  return folder;
}

export function deleteDirectSavedSqlFolder(folderId: string): void {
  const current = loadDirectSavedSqlLibrary();
  const children = new Map<string, string[]>();
  for (const folder of current.folders) {
    if (!folder.parentFolderId) continue;
    const siblings = children.get(folder.parentFolderId) ?? [];
    siblings.push(folder.id);
    children.set(folder.parentFolderId, siblings);
  }
  const removed = new Set([folderId]);
  const pending = [folderId];
  while (pending.length) {
    for (const childId of children.get(pending.shift()!) ?? []) {
      if (!removed.has(childId)) {
        removed.add(childId);
        pending.push(childId);
      }
    }
  }
  current.folders = current.folders.filter((item) => !removed.has(item.id));
  current.files = current.files.filter((item) => !item.folderId || !removed.has(item.folderId));
  saveLibrary(current);
}

