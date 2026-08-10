import { Capacitor, registerPlugin } from "@capacitor/core";
import type {
  MobileConnectionDraft,
  MobileConnectionEditor,
  MobileConnectionSummary,
  MobileSshProfileDraft,
  MobileSshProfileSummary,
  MobileSshKeyDraft,
  MobileSshKeySummary,
  QueryResult,
} from "../mobileTypes";

interface NativeResult<T> {
  value: T;
}

export interface DirectDatabasePlugin {
  listSshProfiles(): Promise<NativeResult<MobileSshProfileSummary[]>>;
  getSshProfile(options: { id: string }): Promise<NativeResult<MobileSshProfileSummary>>;
  saveSshProfile(options: { profile: MobileSshProfileDraft }): Promise<NativeResult<MobileSshProfileSummary>>;
  deleteSshProfile(options: { id: string }): Promise<NativeResult<{ ok: boolean }>>;
  listSshKeys(): Promise<NativeResult<MobileSshKeySummary[]>>;
  saveSshKey(options: { key: MobileSshKeyDraft }): Promise<NativeResult<MobileSshKeySummary>>;
  deleteSshKey(options: { id: string }): Promise<NativeResult<{ ok: boolean }>>;
  importSshKeyFile(options: { id?: string; name: string; passphrase: string }): Promise<NativeResult<MobileSshKeySummary>>;
  listConnections(): Promise<NativeResult<MobileConnectionSummary[]>>;
  getConnection(options: { id: string }): Promise<NativeResult<MobileConnectionEditor>>;
  saveConnection(options: { connection: MobileConnectionDraft }): Promise<NativeResult<MobileConnectionSummary>>;
  deleteConnection(options: { id: string }): Promise<NativeResult<{ ok: boolean }>>;
  testConnection(options: { connection: MobileConnectionDraft }): Promise<NativeResult<{ message: string }>>;
  metadata(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  diagnostics(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  tableTransaction(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  query(options: Record<string, unknown>): Promise<NativeResult<QueryResult>>;
  redis(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  mongo(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  etcd(options: Record<string, unknown>): Promise<NativeResult<unknown>>;
  cancel(options: { executionId: string }): Promise<NativeResult<{ cancelled: boolean }>>;
}

export const DirectDatabase = registerPlugin<DirectDatabasePlugin>("DirectDatabase");

export class DirectApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DirectApiError";
  }
}

/**
 * 原生直连能力只允许在 Android 容器中调用。
 * 浏览器开发环境没有凭据保险箱和原生驱动，提前失败可避免误把敏感参数交给 Web 实现。
 */
export function requireNative() {
  if (!Capacitor.isNativePlatform()) {
    throw new DirectApiError("数据库直连只能在 Android App 中运行");
  }
}
