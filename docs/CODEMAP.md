# 代码地图

这个文档用于快速定位 Mobile DB Manager 的功能代码。目标是让新人和 AI 在开始修改前，先知道应该看哪个目录、哪个文件，以及不要把逻辑继续堆到大组件或大入口里。

## 先读顺序

1. `AGENTS.md`：项目级开发要求、安全边界、发布规则。
2. `docs/CODEMAP.md`：功能到文件的定位地图。
3. `README.md` / `README.en.md`：用户视角功能、命令和发布说明。
4. 目标功能对应的组件、前端 API 和 Android 原生类。

## 快速定位

| 要改的功能 | 前端页面 | 前端 API / 工具 | Android 原生 |
| --- | --- | --- | --- |
| 连接列表、连接表单、收藏、分组、搜索 | `src/features/connections/ConnectionManager.vue` | `src/lib/direct/connections.ts`、`src/lib/connectionPreferences.ts` | `DirectConnectionStore.java`、`SecureVaultStore.java`、`DirectConnectionValidator.java` |
| 设置页、可复用 SSH 配置 | `src/features/settings/SettingsPage.vue` | `src/lib/direct/sshProfiles.ts` | `DirectSshProfileStore.java`、`SecureVaultStore.java` |
| PostgreSQL / MySQL / SQL Server 元数据浏览 | `src/features/browse/relational/MetadataBrowser.vue` | `src/lib/direct/metadata.ts`、`src/lib/databaseCapabilities.ts` | `DirectJdbcMetadata.java`、`DirectJdbcConnectionFactory.java` |
| 关系型表数据浏览和安全事务增删改 | `src/features/browse/relational/TableDataBrowser.vue` | `src/lib/direct/tableData.ts` | `DirectJdbcTableTransaction.java`、`DirectJdbcQueryRunner.java`、`DirectJdbcConnectionFactory.java` |
| SQL 工作台、查询 Tab、执行、取消、Explain | `src/features/query/QueryWorkbench.vue` | `src/lib/direct/query.ts`、`src/lib/sqlEditor.ts`、`src/lib/sqlParameters.ts` | `DirectJdbcQueryRunner.java`、`DirectSqlSafety.java` |
| 会话、正在执行的 SQL、长事务、锁等待和阻塞链 | `src/features/browse/relational/SessionDiagnostics.vue` | `src/lib/direct/diagnostics.ts` | `DirectJdbcDiagnostics.java`、`DirectDatabasePlugin.java` |
| 查询结果导出和分享文件名 | `src/features/query/QueryWorkbench.vue` | `src/lib/queryExport.ts` | Android 系统分享由 Capacitor 插件处理 |
| 查询历史和 SQL 收藏 | `src/features/query/HistoryLibrary.vue` | `src/lib/direct/history.ts`、`src/lib/direct/savedSql.ts` | 无，当前保存在 WebView localStorage |
| Redis Key 浏览、详情、TTL、固定写入动作 | `src/features/browse/redis/RedisDataBrowser.vue` | `src/lib/direct/redis.ts` | `DirectRedisActions.java`、`DirectRedisConnection.java` |
| MongoDB 数据库、集合、文档浏览和编辑 | `src/features/browse/mongo/MongoDataBrowser.vue` | `src/lib/direct/mongo.ts` | `DirectMongoActions.java`、`DirectMongoConnection.java` |
| etcd 前缀浏览、键详情、put/delete | `src/features/browse/etcd/EtcdDataBrowser.vue` | `src/lib/direct/etcd.ts` | `DirectEtcdActions.java`、`DirectEtcdConnection.java` |
| SSH 隧道、HTTP CONNECT 代理、路由清理 | 连接表单相关 UI | 连接配置字段、`src/lib/direct/sshProfiles.ts` | `DirectTransport.java`、`DirectSshProfileStore.java` |
| Capacitor 插件入口、参数读取、统一错误返回 | 调用方组件 | `src/lib/direct/native.ts` | `DirectDatabasePlugin.java`、`DirectErrors.java` |
| 移动键盘适配 | `src/app/DirectApp.vue` 和输入组件 | `src/lib/mobileKeyboard.ts` | `MobileKeyboardPlugin.java` |
| GitHub Release 自动更新和 APK 下载 | `src/features/update/UpdateBanner.vue`、`src/app/DirectApp.vue` | `src/lib/appUpdate.ts` | `AppUpdatePlugin.java`、`AppReleaseUpdateService.java`、`AppUpdateVersion.java` |

## 当前大组件

这些文件仍然偏大。小修可以直接改；如果要新增明显的新面板、新模式或新交互，优先拆出子组件或 composable。

| 文件 | 当前职责 | 后续推荐拆分方向 |
| --- | --- | --- |
| `src/features/query/QueryWorkbench.vue` | 查询编辑器、连接上下文、执行、结果、保存 SQL、导出 | `QueryEditor.vue`、`QueryTabs.vue`、`QueryResultPanel.vue`、`useQueryExecution.ts` |
| `src/features/browse/relational/TableDataBrowser.vue` | 表数据加载、筛选、排序、单元格编辑、新增/删除行 | `TableToolbar.vue`、`TableDataGrid.vue`、`RowEditPanel.vue`、`useTableData.ts` |
| `src/features/browse/relational/MetadataBrowser.vue` | 库/schema/table 树、元数据详情、打开查询 | `SchemaTree.vue`、`TableMetadataPanel.vue` |
| `src/features/browse/redis/RedisDataBrowser.vue` | Redis 库选择、Key 树、详情、各类型写入 | `RedisKeyTree.vue`、`RedisKeyInspector.vue`、`RedisMutationPanel.vue` |
| `src/features/browse/mongo/MongoDataBrowser.vue` | Mongo 数据库/集合树、文档列表、文档编辑 | `MongoCollectionTree.vue`、`MongoDocumentEditor.vue` |
| `src/features/connections/ConnectionManager.vue` | 连接列表、筛选、表单、隧道/SSL/SSH 配置 | `ConnectionList.vue`、`ConnectionForm.vue`、`TunnelSettings.vue` |

## 新增数据库类型

新增数据库类型时按这个顺序改：

1. `src/lib/mobileTypes.ts`：补连接类型、结果类型或浏览器数据结构。
2. `src/lib/databaseCapabilities.ts`：声明这个数据库是否支持浏览、查询、写入。
3. `src/lib/direct/<db>.ts`：新增前端 API 文件，不要继续扩展 `src/lib/directDatabase.ts`。
4. `src/features/browse/<db>/<Db>DataBrowser.vue`：新增页面组件。
5. `android/app/src/main/java/com/coolbanhub/mobiledbmanager/Direct<Db>Actions.java`：新增面向界面的固定动作映射和写入保护。
6. `android/app/src/main/java/com/coolbanhub/mobiledbmanager/Direct<Db>Connection.java`：新增底层连接客户端。
7. `DirectDatabasePlugin.java`：只新增最薄的一层入口分发，不写具体业务逻辑。
8. `README.md`、`README.en.md`、`AGENTS.md`、`docs/CODEMAP.md`：同步文档。
9. 补测试并运行验证命令。

## Bug 定位

| 现象 | 优先看 |
| --- | --- |
| 连接保存后密码丢失、编辑连接不回填密钥状态 | `DirectConnectionStore.java`、`SecureVaultStore.java`、`src/lib/direct/connections.ts` |
| 测试连接失败但错误信息不友好 | 对应 `Direct<Db>Connection.java`、`DirectErrors.java` |
| SQL 被错误判定为写入或只读 | `DirectSqlSafety.java` 和 `android/app/src/test/.../DirectDatabasePluginTest.java` |
| 会话列表为空、锁诊断权限不足或取消/终止失败 | `SessionDiagnostics.vue`、`src/lib/direct/diagnostics.ts`、`DirectJdbcDiagnostics.java` |
| 查询能执行但结果类型显示异常 | `DirectJdbcQueryRunner.java`、`src/lib/mobileTypes.ts`、`QueryWorkbench.vue` |
| 表数据事务无法提交、已回滚或提示缺主键 | `src/lib/direct/tableData.ts`、`TableDataBrowser.vue`、`DirectJdbcTableTransaction.java` |
| Redis Key 列表重复、详情为空或大 Key 卡顿 | `RedisDataBrowser.vue`、`DirectRedisActions.java`、`DirectRedisConnection.java` |
| MongoDB 某个集合无法读取 | `MongoDataBrowser.vue`、`DirectMongoActions.java`、`DirectMongoConnection.java` |
| etcd 前缀查询结果不对或二进制值被破坏 | `EtcdDataBrowser.vue`、`DirectEtcdActions.java`、`DirectEtcdConnection.java` |
| SSH/HTTP 代理连接后没有释放 | `DirectTransport.java` |
| 已保存 SSH 配置无法选择或凭据状态异常 | `SettingsPage.vue`、`sshProfiles.ts`、`DirectSshProfileStore.java` |
| GitHub Release 没有 APK 或应用内更新找不到新版 | `.github/workflows/android-release.yml`、`AppReleaseUpdateService.java`、`src/lib/appUpdate.ts` |

## 约定

- 文件名要表达目的。新增代码优先放到具体数据库、具体功能的文件里。
- 不要新增“common”、“utils”、“helpers”这类泛化目录，除非已有两个以上明确调用方并且职责能说清。
- `src/lib/directDatabase.ts` 只作为兼容出口，不继续写实现。
- `DirectDatabasePlugin.java` 只作为 Capacitor 薄入口，不继续写具体数据库逻辑。
- 新增用户可见能力时，中文 README 和英文 README 都要更新。
