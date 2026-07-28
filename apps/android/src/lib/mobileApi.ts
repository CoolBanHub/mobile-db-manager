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
  const response = await apiFetch(baseUrl, buildApiPath(path, params), token, {
    headers: { Accept: "application/json" },
  });
  if (!response.ok) throw new ApiError(`服务器返回 ${response.status}`, response.status);
  return response.json() as Promise<T>;
}
