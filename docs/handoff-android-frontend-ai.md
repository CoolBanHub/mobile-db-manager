# DBX Android 前端 AI 交接文档

更新时间：2026-08-03  
工作区：`/Users/kuban/workspace/dbx-main`  
主要应用：`apps/android`  
状态：当前工作树包含尚未提交的功能改动，请先阅读 `git status` 和 `git diff`，不要重置或覆盖现有修改。

## 1. 项目目标与当前状态

DBX Android 是一个基于 Vue 3、TypeScript、Vite、Capacitor 8 和 Android Java 的移动数据库客户端。WebView 负责 UI，数据库连接、凭据和网络访问全部留在 Android 原生进程中。

当前支持六种数据源：

| 类型 | 连接测试 | 数据浏览 | 写入 |
| --- | --- | --- | --- |
| PostgreSQL | JDBC | 关系型元数据、SQL、表数据 | 支持，受只读和生产保护约束 |
| MySQL / MariaDB | MariaDB JDBC | 关系型元数据、SQL、表数据 | 支持，受只读和生产保护约束 |
| SQL Server | jTDS | 关系型元数据、SQL、表数据 | 支持，受只读和生产保护约束 |
| Redis | 原生 RESP | Key、类型、值、TTL、内存 | 支持 String/Hash/List/Set/ZSet 常用操作 |
| MongoDB | MongoDB Java Sync Driver | 数据库、集合、文档 | 支持插入、替换、删除 |
| etcd | v3 JSON Gateway | 状态、前缀、键值、Revision、Lease | 支持创建、覆盖、删除 |

这轮累计完成的主要功能：

- 修复 Android 表格编辑后数据未变化的问题，补齐表数据修改、新增、删除及刷新逻辑。
- 完善 Redis Standalone 浏览器和原生 RESP 客户端。
- 完善 MongoDB 文档浏览器，并兼容 MongoDB 8.x 的认证/SASL。
- SQL Server 从不兼容 Android ART 的 Microsoft JDBC 驱动切换为 jTDS，并增加 JDBC 4 Schema 元数据回退。
- 新增 etcd v3 连接和键值浏览器。
- 新建连接增加主机、端口、用户和数据库默认值。
- 连接支持本机分组、收藏、环境、自定义标签和标签搜索。
- 增加 Android 返回键处理、全屏连接编辑器和底部导航状态管理。

## 2. 目录与职责

| 路径 | 职责 |
| --- | --- |
| `apps/android/src/DirectApp.vue` | 应用主路由状态、底部导航、浏览器选择和 Android 返回键分发 |
| `apps/android/src/components/ConnectionManager.vue` | 连接列表、编辑器、默认值、标签、SSL/SSH/HTTP 配置 |
| `apps/android/src/components/MetadataBrowser.vue` | 关系型数据库导航 |
| `apps/android/src/components/TableDataBrowser.vue` | 表数据分页、筛选、排序、单元格编辑、增删行 |
| `apps/android/src/components/QueryWorkbench.vue` | SQL 查询、写入确认和结果展示 |
| `apps/android/src/components/RedisDataBrowser.vue` | Redis 专用浏览器 |
| `apps/android/src/components/MongoDataBrowser.vue` | MongoDB 专用浏览器 |
| `apps/android/src/components/EtcdDataBrowser.vue` | etcd 专用浏览器 |
| `apps/android/src/lib/directDatabase.ts` | 前端唯一的原生数据库桥接封装；组件不要直接调用 Capacitor 插件 |
| `apps/android/src/lib/mobileTypes.ts` | 跨层 TypeScript 契约 |
| `apps/android/src/lib/databaseCapabilities.ts` | 数据库类型、默认配置和浏览模式 |
| `apps/android/src/lib/connectionPreferences.ts` | 分组、收藏、环境和标签的 localStorage 存储 |
| `apps/android/android/app/src/main/java/com/houtsider/dbx/DirectDatabasePlugin.java` | Capacitor 原生插件入口、权限检查和数据形状转换 |
| `DirectRedisConnection.java` | RESP 客户端、AUTH、SELECT、TLS |
| `DirectMongoConnection.java` | MongoDB URI/主机连接和文档操作 |
| `DirectEtcdConnection.java` | etcd v3 JSON Gateway、Auth token、TLS 和 Base64 编解码 |
| `DirectTransport.java` | SSH 本地转发、HTTP CONNECT 和路由生命周期 |
| `DirectConnectionStore.java` / `SecureVaultStore.java` | 私有连接配置和 Android Keystore 加密 |

## 3. 原生桥接总约定

这里没有 HTTP 后端 endpoint。前端通过 Capacitor 插件 `DirectDatabase` 调用原生 Java 方法。

- 所有成功响应的原始形状均为 `{ value: T }`。
- `apps/android/src/lib/directDatabase.ts` 已统一解包，Vue 组件只接收 `T`。
- 所有调用均为异步 Promise。
- 原生错误通过 Promise reject 返回可展示的中文 message，没有 HTTP code 或独立业务 code。
- SSL 证书错误会被统一转换为以 `SSL 证书验证失败` 开头的提示；连接编辑器会自动跳转 SSL 页。
- UI 建议：连接/认证错误在当前页面内联展示；不要跳登录、全局重试或静默吞掉写入错误。
- Web 浏览器环境不能直连，`requireNative()` 会抛出“数据库直连只能在 Android App 中运行”。

## 4. 连接契约

### 4.1 `MobileConnectionDraft`

必填：

- `name: string`
- `dbType: "postgres" | "mysql" | "sqlserver" | "mongodb" | "redis" | "etcd"`
- `host: string`，MongoDB 使用 `connectionString` 时可为空
- `port: number`，范围 `1..65535`

可选或有默认值：

- `username/password/database/connectionString`
- `ssl: boolean`，默认 `false`
- `sslMode: "required" | "verify-ca" | "verify-full"`，默认 `verify-full`
- `readOnly/isProduction`，默认 `false`
- `connectTimeoutSecs=10`、`queryTimeoutSecs=60`、`keepaliveIntervalSecs=30`
- HTTP CONNECT：`proxyEnabled/proxyHost/proxyPort/proxyUsername/proxyPassword`
- SSH：`sshEnabled/sshHost/sshPort/sshUsername/sshAuthMethod/sshPassword/sshPrivateKey/...`

编辑已有连接时，密码、私钥和连接串返回为空；对应的 `hasPassword`、`hasSshPrivateKey`、`hasConnectionString` 表示原值存在。前端提交空值即保留原密文，不要把空字符串解释为清空凭据。

默认连接值：

| 类型 | 主机 | 端口 | 用户 | 数据库 |
| --- | --- | ---: | --- | --- |
| PostgreSQL | `127.0.0.1` | 5432 | `postgres` | `postgres` |
| MySQL | `127.0.0.1` | 3306 | `root` | 空 |
| SQL Server | `127.0.0.1` | 1433 | `sa` | `master` |
| MongoDB | `127.0.0.1` | 27017 | 空 | 空 |
| Redis | `127.0.0.1` | 6379 | 空 | `0` |
| etcd | `127.0.0.1` | 2379 | 空 | 不适用 |

注意：Android 手机上的 `127.0.0.1` 指手机自身，不是开发电脑。跨设备连接必须填写局域网/VPN/公网地址或配置隧道。

### 4.2 连接偏好

`ConnectionPreference` 仅存 localStorage，不进入 Android 加密连接配置：

```ts
interface ConnectionPreference {
  group: string;
  favorite: boolean;
  environment: "development" | "staging" | "production";
  tags: string[];
}
```

标签接受英文逗号、中文逗号、顿号和换行分隔，去重后最多 12 个，每个最多 24 个 Unicode 字符。旧存储没有 `tags` 时回退 `[]`。

## 5. Capacitor 方法契约

### 5.1 连接管理

- `listConnections()` → `MobileConnectionSummary[]`
- `getConnection({ id })` → `MobileConnectionEditor`
- `saveConnection({ connection })` → `MobileConnectionSummary`
- `deleteConnection({ id })` → `{ ok: true }`
- `testConnection({ connection })` → `{ message: string }`

`save/delete` 成功后前端必须重新拉取连接列表。没有版本号或增量订阅。

### 5.2 关系型元数据

`metadata({ connectionId, kind, database="", schema="", table="", filter="", limit=100, offset=0 })`

`kind`：`databases | schemas | tables | columns | indexes | foreign-keys | objects`。

- `tables` 使用 offset 分页，默认 100，空集返回 `[]`。
- `columns/indexes/foreign-keys/objects` 返回 `mobileTypes.ts` 中对应结构。
- SQL Server 的 `getSchemas(catalog, pattern)` 不受 jTDS 支持，原生层已回退到 `getSchemas()`；不要在前端重复兼容。

### 5.3 SQL 查询

`query({ connectionId, database, schema, sql, executionId, offset=0, pageSize=50, readOnly=true, confirmedWrite=false, productionConfirmation="" })`

返回：

```ts
interface QueryResult {
  columns: string[];
  rows: unknown[][];
  affected_rows: number;
  execution_time_ms: number;
  truncated: boolean;
  has_more: boolean;
}
```

- 原生读取最多 501 行，分页由 `offset/pageSize` 控制。
- `readOnly=true` 时会拒绝写语句。
- 高级写入必须 `confirmedWrite=true`。
- 连接本身 `readOnly=true` 时始终禁止写入。
- 生产连接写入时 `productionConfirmation` 必须完全等于连接名称。
- `cancel({ executionId })` 可取消仍在运行的 JDBC Statement。
- 写入成功后表格页面必须重新加载当前页；不要仅本地乐观更新数据库结果。

### 5.4 Redis

`redis({ connectionId, database, action, ... })`

读取 action：

- `overview` → `{ keyCount: number, keyspace: string }`
- `scan`：`cursor="0"`、`pattern="*"`、`count=100`，count 原生限制 `10..200`；返回 `{ cursor: string, keys: string[] }`
- `detail`：必填 `key`；返回 `{ key, type, ttlMs, memoryBytes, length?, value }`

写入 action：`delete | set-string | hset | hdel | lset | rpush | sadd | srem | zadd | zrem | expire | persist`。写入必须携带 `confirmedWrite=true` 和生产确认字段。

Redis 只支持 Standalone，不支持 Sentinel/Cluster。集合预览有数量限制，单个 Bulk 值上限 4 MB。

### 5.5 MongoDB

`mongo({ connectionId, action, ... })`

- `databases` → `string[]`
- `collections`：必填 `database` → `string[]`
- `documents`：必填 `database/collection`，`filter="{}"`、`offset=0`、`limit=25` → `{ documents: string[], offset, limit, hasMore }`
- `insert`：必填扩展 JSON 字符串 `document`
- `replace/delete`：使用完整原始扩展 JSON 文档定位，替换另需 `document`

文档使用 MongoDB canonical Extended JSON，Long/ObjectId/Date 等类型不能被普通 JSON stringify 替换。MongoDB URI 与应用内 SSH/HTTP 隧道互斥；普通主机模式默认在 `admin` 认证库认证。

### 5.6 etcd

`etcd({ connectionId, action, ... })`

- `overview` → `{ version: string, dbSize: string, keyCount: string }`
- `list`：`prefix=""`、`limit=200`，原生限制 `1..500` → `{ entries: MobileEtcdEntry[], count: string, more: boolean }`
- `detail`：必填 `key` → `MobileEtcdEntry`
- `put`：必填 `key`，`value` 默认空字符串，`lease` 默认 `"0"`
- `delete`：必填 `key`

```ts
interface MobileEtcdEntry {
  key: string;
  value: string;
  createRevision: string;
  modRevision: string;
  version: string;
  lease: string;
}
```

关键行为：

- 依赖 etcd v3 JSON Gateway，不直接连接纯 gRPC endpoint。
- 用户名和密码必须同时填写；原生层调用 `/v3/auth/authenticate` 并在后续请求带 token。
- etcd int64 字段全部保留为字符串，前端禁止转 JavaScript number，以免超过安全整数范围。
- 空前缀查询整个 keyspace；当前 UI 最多展示 200 条，`more=true` 时提示用户缩小前缀，没有 cursor 翻页。
- 非 UTF-8 键值返回 `base64:` 前缀。当前 UI 禁止编辑二进制值，避免文本覆盖破坏数据。
- 修改已有键时必须透传原 `lease`，否则可能解除租约。
- HTTP 响应上限 4 MB。

## 6. 写入保护与刷新时序

所有 Redis、MongoDB、etcd 和关系型写入都遵守：

1. `connection.readOnly=true`：按钮禁用，原生层再次拒绝。
2. 普通连接：前端明确操作后发送 `confirmedWrite=true`。
3. 生产连接：用户输入完整连接名称，发送 `productionConfirmation`。
4. 写入成功：重新读取详情和概览/列表；不要并发合并同一个键或文档的请求。
5. 写入失败：保留编辑内容并内联显示错误，不要自动重试非幂等写入。

## 7. 字段冲突提醒

- `{ value: T }` 是 Capacitor 响应信封；Redis/etcd 的 `value` 是数据本身，两者不是同一层。
- Redis `database` 是逻辑库编号字符串；关系型 `database` 是库名；etcd 不使用该字段。
- etcd `version` 是键版本或服务版本字符串；不要与 App 版本号混用。
- etcd `count/revision/lease/dbSize/version` 使用字符串承载 int64。
- MongoDB `documents` 是 Extended JSON 字符串数组，不是普通对象数组。

## 8. Android 特殊兼容点

- SQL Server 必须保留 `net.sourceforge.jtds:jtds:1.3.1`。Microsoft `mssql-jdbc` 在 Android ART 上 TLS/TDS 握手失败或挂起。
- SQL Server Schema 必须继续使用 `getSchemasCompatible()`。
- MongoDB 5.5.1 驱动包含 Android 缺失的 JMX/SASL 类，工程内有替代实现，勿随意删除。
- PostgreSQL/MariaDB/Mongo 驱动通过 Gradle 任务重新打包，避免 Android 缺失类或不兼容实现。
- 所有数据库路由都要通过 `DirectTransport`，保证 SSH/HTTP 隧道生命周期正确关闭。
- 敏感字段不可返回 WebView；编辑页通过 `has*` 字段显示“留空保留”。

## 9. 已验证范围

- 前端：`pnpm test`，6 个测试文件、28 个测试通过。
- TypeScript/Vue：`pnpm build` 通过。
- Android Java：`:app:testDebugUnitTest` 通过。
- Android APK：`:app:assembleDebug` 通过。
- SQL Server：Android 模拟器连接 SQL Server 2022，关闭 SSL 与要求 SSL 均查询成功；Schema、表、字段加载成功。
- etcd：Android 模拟器连接 etcd 3.6.7；无认证和 Auth 两种模式均完成状态、写入、前缀查询、读取、删除。
- 最新 Debug APK：`apps/android/android/app/build/outputs/apk/debug/app-debug.apk`
- 最新 SHA-256：`fbeceb77a1888414106d51bc661ad430c3deafe8f4c0219f19fa7ae4f405d2ce`

常用命令：

```bash
cd apps/android
pnpm test
pnpm sync

cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## 10. 已知限制与建议下一步

- 工作区当前未提交且包含多轮功能修改；接手前先创建独立分支/提交，不要 `git reset --hard`。
- `deploy/public-databases` 当前固定栈仍是 MySQL、PostgreSQL、Redis、MongoDB、SQL Server；etcd 仅用临时容器完成测试，尚未加入长期 compose 配置。
- etcd 暂无 watch、事务、Lease 管理、成员管理、mTLS 客户端证书和真正分页。
- Redis 暂无 Cluster/Sentinel。
- MongoDB 暂无字段级表单编辑，使用 Extended JSON 文本编辑。
- SQL Server 暂无 SHOWPLAN。
- 标签目前是连接偏好，仅保存在 WebView localStorage；清除 App 数据会丢失，尚未进入导入导出。
- 错误契约只有 message，没有稳定 business code；如要系统化错误 UI，应先在原生层引入结构化 code，再改前端。
- 建议下一步优先补 Vue 组件交互测试、连接配置导入导出，以及 etcd 分页/Watch。

## 11. PRD、额外约束和附件

- PRD：无。
- 用户额外跨端约束：无。
- 附件：无。
