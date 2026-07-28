export interface MobileLoginResponse {
  ok: boolean;
  token: string | null;
  expiresAt: number | null;
}

export interface MobileConnectionSummary {
  id: string;
  name: string;
  note: string;
  dbType: string;
  host: string;
  port: number;
  database: string | null;
  color: string | null;
  ssl: boolean;
  readOnly: boolean;
  isProduction: boolean;
}

export interface DatabaseInfo {
  name: string;
}

export interface TableInfo {
  name: string;
  table_type: string;
  comment: string | null;
  parent_schema: string | null;
  parent_name: string | null;
}

export interface ColumnInfo {
  name: string;
  data_type: string;
  is_nullable: boolean;
  column_default: string | null;
  is_primary_key: boolean;
  extra: string | null;
  comment: string | null;
}

export interface QueryResult {
  columns: string[];
  rows: unknown[][];
  affected_rows: number;
  execution_time_ms: number;
  truncated: boolean;
}

export interface MobileQueryDraft {
  nonce: number;
  connectionId: string;
  database: string;
  schema: string | null;
  sql: string;
}

export interface MobileHistoryEntry {
  id: string;
  connectionId: string;
  connectionName: string;
  database: string;
  schema: string | null;
  sql: string;
  executedAt: string;
  executionTimeMs: number;
  success: boolean;
  error: string | null;
}

export interface SavedSqlFolder {
  id: string;
  connectionId: string;
  parentFolderId: string | null;
  name: string;
}

export interface SavedSqlFile {
  id: string;
  connectionId: string;
  folderId: string | null;
  name: string;
  database: string;
  schema: string | null;
  sql: string;
  sqlLoaded: boolean;
  updatedAt: string;
}

export interface SavedSqlLibrary {
  folders: SavedSqlFolder[];
  files: SavedSqlFile[];
}

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export const API_REQUEST_TIMEOUT_MS = 8_000;

export class ApiTimeoutError extends Error {
  constructor() {
    super("请求超时，请检查网络后重试");
    this.name = "ApiTimeoutError";
  }
}

export async function withApiTimeout<T>(
  operation: (signal: AbortSignal) => Promise<T>,
  timeoutMs = API_REQUEST_TIMEOUT_MS,
): Promise<T> {
  const controller = new AbortController();
  const timeout = globalThis.setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await operation(controller.signal);
  } catch (error) {
    if (controller.signal.aborted) throw new ApiTimeoutError();
    throw error;
  } finally {
    globalThis.clearTimeout(timeout);
  }
}

export function buildApiHeaders(token: string | null, initial?: HeadersInit): Headers {
  const headers = new Headers(initial);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return headers;
}

export function apiFetch(baseUrl: string, path: string, token: string | null, init: RequestInit = {}): Promise<Response> {
  return fetch(`${baseUrl}${path}`, {
    ...init,
    headers: buildApiHeaders(token, init.headers),
  });
}

export function buildApiPath(path: string, params: Record<string, string | number | undefined>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) query.set(key, String(value));
  }
  const encoded = query.toString();
  return encoded ? `${path}?${encoded}` : path;
}

export async function apiGetJson<T>(
  baseUrl: string,
  path: string,
  token: string | null,
  params: Record<string, string | number | undefined>,
): Promise<T> {
  return withApiTimeout(async (signal) => {
    const response = await apiFetch(baseUrl, buildApiPath(path, params), token, {
      headers: { Accept: "application/json" },
      signal,
    });
    if (!response.ok) throw new ApiError(await apiErrorMessage(response), response.status);
    return response.json() as Promise<T>;
  });
}

async function apiErrorMessage(response: Response): Promise<string> {
  const fallback = `服务器返回 ${response.status}`;
  const text = await response.text().catch(() => "");
  if (!text) return fallback;
  try {
    const payload = JSON.parse(text) as { message?: string; error?: string };
    return payload.message ?? payload.error ?? fallback;
  } catch {
    return text;
  }
}

export async function apiPostJson<T>(
  baseUrl: string,
  path: string,
  token: string | null,
  body: unknown,
  options: { signal?: AbortSignal; timeoutMs?: number } = {},
): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  options.signal?.addEventListener("abort", abort, { once: true });
  const timeout = window.setTimeout(abort, options.timeoutMs ?? 35_000);
  try {
    const response = await apiFetch(baseUrl, path, token, {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new ApiError(await apiErrorMessage(response), response.status);
    }
    return response.json() as Promise<T>;
  } finally {
    window.clearTimeout(timeout);
    options.signal?.removeEventListener("abort", abort);
  }
}

export async function apiDeleteJson<T>(
  baseUrl: string,
  path: string,
  token: string | null,
): Promise<T> {
  return withApiTimeout(async (signal) => {
    const response = await apiFetch(baseUrl, path, token, {
      method: "DELETE",
      headers: { Accept: "application/json" },
      signal,
    });
    if (!response.ok) throw new ApiError(await apiErrorMessage(response), response.status);
    return response.json() as Promise<T>;
  });
}
