# Codex 项目要求

本仓库是 `mobile-db-manager`，目标是做一个独立运行的 Android 手机端数据库管理工具。后续开发优先遵守本文件，其次参考 `docs/CODEMAP.md`、`README.md` 和 `README.en.md`。

## 产品边界

- 应用通过 Android 原生驱动从手机直接连接 PostgreSQL、MySQL/MariaDB、SQL Server、Redis、MongoDB 和 etcd。
- 不引入后端服务、桌面代理、账号系统或移动端 API 网关。
- 数据库连接、查询、浏览、编辑能力应以手机端可维护和可调试为前提，不为了快速实现把逻辑堆进单个大文件。

## 目录职责

开始修改前先看 `docs/CODEMAP.md`，用它定位功能对应的页面、前端 API 和 Android 原生类。

前端页面按 feature 放在 `src/features/`：

- `src/app/DirectApp.vue`：应用壳、底部导航、浏览/查询页面切换和 Android 返回键。
- `src/features/connections/`：连接列表、连接编辑表单、收藏、分组、搜索。
- `src/features/query/`：SQL 工作台、查询历史、本机 SQL 收藏、查询结果展示。
- `src/features/browse/relational/`：PostgreSQL、MySQL/MariaDB、SQL Server 的元数据和表数据浏览。
- `src/features/browse/redis/`：Redis Key 浏览和固定写入动作。
- `src/features/browse/mongo/`：MongoDB 数据库、集合、文档浏览和编辑。
- `src/features/browse/etcd/`：etcd 前缀、键值详情和 put/delete。
- `src/features/update/`：GitHub Release 更新提示和 APK 下载入口。

前端直连数据库 API 放在 `src/lib/direct/`，按职责拆分：

- `connections.ts`：连接列表、保存、删除和测试。
- `metadata.ts`：库、schema、表、字段、索引等元数据读取。
- `query.ts`：SQL 执行、取消、Explain。
- `tableData.ts`：表格数据浏览和行级增删改。
- `redis.ts`、`mongo.ts`、`etcd.ts`：各自数据库的数据浏览和固定写入动作。
- `history.ts`、`savedSql.ts`：本机历史记录和 SQL 收藏。
- `native.ts`、`localStore.ts`：Capacitor 原生桥接和本机 localStorage 工具。

应用更新 API 放在 `src/lib/appUpdate.ts`，不要塞进数据库直连 API。

Android 原生直连代码放在 `android/app/src/main/java/com/coolbanhub/mobiledbmanager/`：

- `DirectDatabasePlugin.java`：Capacitor 入口，只做参数读取、安全门和分发。
- `DirectJdbcConnectionFactory.java`：PostgreSQL、MySQL/MariaDB、SQL Server 的 JDBC URL、驱动加载、SSL 和隧道接入。
- `DirectJdbcMetadata.java`：JDBC 元数据读取。
- `DirectJdbcQueryRunner.java`：SQL 执行、分页、结果序列化和取消。
- `DirectJdbcTableTransaction.java`：表数据新增、修改和删除的参数化事务提交与整体回滚。
- `DirectJdbcDiagnostics.java`：会话、长事务、锁等待、阻塞链及受保护的会话操作。
- `DirectRedisActions.java`、`DirectMongoActions.java`、`DirectEtcdActions.java`：面向界面的动作映射和写入保护。
- `DirectRedisConnection.java`、`DirectMongoConnection.java`、`DirectEtcdConnection.java`：各数据库的底层连接客户端。
- `DirectTransport.java`：SSH 转发和 HTTP CONNECT 隧道。
- `DirectConnectionStore.java`、`SecureVaultStore.java`：连接配置和敏感信息保存。
- `AppUpdatePlugin.java`、`AppReleaseUpdateService.java`、`AppUpdateVersion.java`：GitHub Release 检查、APK 下载和版本比较。

新增数据库类型时，优先新增对应的前端 API 文件、原生 action 文件和底层连接客户端；不要继续把新逻辑塞回 `DirectDatabasePlugin.java` 或 `src/lib/directDatabase.ts`。

## 安全要求

- 数据库密码、连接串、代理密码、SSH 密码、SSH 私钥和私钥口令必须留在原生侧或安全存储里，不能返回 WebView 明文。
- 敏感信息继续通过 Android Keystore AES-GCM 加密保存。
- 写入操作必须经过只读连接校验、写入确认和生产连接名称确认。
- Redis 只允许固定动作映射，不允许 WebView 下发任意 Redis 命令文本。
- etcd 只开放当前界面需要的列表、详情、put/delete，不暴露任意 Gateway 路径。
- 使用 SSH 隧道时保留主机密钥指纹校验能力；生产环境优先使用受信任 TLS 证书。

## 文档要求

- 默认展示中文文档：`README.md`。
- 英文文档单独维护：`README.en.md`。
- 面向用户的功能、命令、发布方式、目录结构发生变化时，两个 README 都要同步更新。
- GitHub 搜索相关关键词放在 `README.en.md`、仓库 Description 和 Topics 中；不要把默认中文 README 写成中英混排。

## 发布要求

- GitHub Actions workflow 位于 `.github/workflows/android-release.yml`。
- 发布标签使用 `release/v*`，例如 `release/v0.0.1`。
- 不要移动已经发布过的 tag；如需基于新提交重新打包，创建新的 release tag。
- tag 发布会构建已签名 Release APK 并上传到对应 GitHub Release。
- release workflow 必须从 `release/vMAJOR.MINOR.PATCH` 设置 Android `versionName` 和单调递增 `versionCode`，应用内更新依赖这个规则。
- Release APK 必须使用同一个签名证书，否则 Android 无法覆盖安装更新。
- 签名发布只通过环境变量或 GitHub Secrets 提供 keystore 和密码，不提交密钥文件。

## 验证命令

常规前端验证：

```bash
pnpm test
pnpm build
```

Android 单元测试：

```bash
cd android
./gradlew :app:testDebugUnitTest
```

完整 Debug APK 构建：

```bash
pnpm android:build:debug
```

涉及原生连接、发布流程、依赖或 Capacitor 同步时，至少跑 Android 单元测试和 Debug APK 构建。
