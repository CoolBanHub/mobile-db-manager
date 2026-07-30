# DBX 安卓端

DBX 安卓端是一款可独立运行的移动数据库客户端。应用通过内置原生驱动从手机
直接连接数据库，不需要部署 DBX Web，也不需要账号、移动 API 或 Bearer 会话。

[English](README.md) · [安卓开发指南](apps/android/README.md) ·
[下载发行版](https://github.com/t8y2/dbx/releases/latest)

> 安卓端只是 DBX 仓库中的一个产品。桌面端、Docker/Web、CLI 和 MCP 仍然保留，
> 但这些产品的功能不代表安卓端也已经支持。

## 安卓端现有功能

### 数据库直连

- 在本机创建、编辑、测试、搜索、分组、收藏和删除连接。
- 为连接标记开发、预发或生产环境。
- 直连 PostgreSQL、MySQL/MariaDB 和 SQL Server，浏览关系型元数据并执行 SQL。
- 创建和测试 Redis Standalone 连接。
- 使用主机端口或 URI 创建和测试 MongoDB 连接。
- 在支持的连接上使用 TLS、SSH 本地端口转发和 HTTP CONNECT 代理。

安卓端暂未提供 Redis Key 浏览器和 MongoDB 文档浏览器，也不支持 Redis
Sentinel 与 Cluster。MongoDB URI 已包含完整路由信息，不能同时启用应用内 SSH
或 HTTP 隧道。

### 元数据与表数据

对于 PostgreSQL、MySQL/MariaDB 和 SQL Server，安卓端可以浏览：

- 数据库与 Schema；
- 表与视图；
- 字段与主键；
- 索引与外键；
- JDBC 元数据驱动可见的存储过程。

表数据浏览器支持分页、字段排序、条件筛选、完整单元格查看和列宽调整，也可以
生成 `SELECT` SQL。当连接允许写入且表存在主键时，可以新增行、删除行和修改
单元格。

### SQL 工作台

- 基于元数据的表名、字段名补全。
- SQL 格式化与具名 JSON 参数。
- 安全只读模式和需要显式确认的高级模式。
- 对生产连接执行写入前输入完整连接名称确认。
- 结果分页、查询取消与 PostgreSQL/MySQL `EXPLAIN`。
- 打开不超过 1 MiB 的本机 `.sql` 文件。
- 结果查看、列宽自动适配、简单图表、单元格详情和复制操作。
- 将当前结果页导出为 CSV、JSON、Markdown 或 XLSX，并打开 Android 系统分享。

安卓端目前不包含桌面/Web 端的 AI SQL 助手、手动事务会话、服务器后台全量导出
和多语句脚本执行。

### 本机历史与 SQL 收藏

- 在设备上保存最多 1,000 条查询历史。
- 按连接、执行状态和日期搜索筛选历史。
- 使用本机目录层级保存 SQL。
- 重命名、移动、重新打开和删除 SQL 或目录。

查询历史与 SQL 收藏只保存在安卓端本机，不通过 DBX Web 同步。

## 安全模型

完整连接配置保存在应用私有存储中。数据库密码、连接串、代理凭据、SSH 密码和
私钥使用 Android Keystore 管理的 AES-GCM 密钥加密。

WebView 只使用连接 ID 调用原生数据库插件。读取连接用于编辑时，不会返回已经
保存的密码、私钥或完整连接串。

生产环境建议：

- 使用 WireGuard、Tailscale 或企业 VPN 接入数据库内网；
- 不要把生产数据库端口直接暴露到公网；
- 使用独立的最小权限数据库账号；
- 为允许写入的连接开启生产环境保护；
- TLS 优先验证证书和主机名；
- 固定 SSH 服务器的 SHA-256 主机密钥指纹。

## 数据库支持

| 数据库 | 连接测试 | 元数据与 SQL | 数据修改 |
| --- | --- | --- | --- |
| PostgreSQL | 支持 | 支持 | 支持，需要主键 |
| MySQL / MariaDB | 支持 | 支持 | 支持，需要主键 |
| SQL Server | 支持 | 支持 | 支持，需要主键 |
| Redis Standalone | 支持 | 暂未支持 | 不支持 |
| MongoDB | 支持 | 暂未支持 | 不支持 |

## 安装

安卓安装包会在可用时发布到
[DBX Releases](https://github.com/t8y2/dbx/releases/latest)。系统要求为
Android 8.0（API 26）或更高版本。

如果通过应用商店以外的方式安装 APK，请确认文件来自官方 `t8y2/dbx` 发行页面，
再允许安装未知来源应用。

## 从源码构建

开发环境：

- Node.js 22.13 或更高版本；
- pnpm 10.27.0；
- JDK 21；
- Android SDK 36；
- Android 8.0（API 26）或更高版本的设备或模拟器。

安装依赖并构建 Debug APK：

```bash
pnpm install --frozen-lockfile
pnpm android:build:debug
```

APK 输出到：

```text
apps/android/android/app/build/outputs/apk/debug/app-debug.apk
```

常用命令：

```bash
pnpm android:sync               # 构建 WebView 资源并执行 Capacitor 同步
pnpm android:open               # 使用 Android Studio 打开工程
pnpm --filter @dbx-app/android test
pnpm android:device:test        # 需要已连接的设备或模拟器
```

如果系统默认 Java 版本较旧，需要显式使用 JDK 21。签名、发布构建和 Android
兼容驱动打包的详细说明参见[安卓开发指南](apps/android/README.md)。

## DBX 的其他产品

本仓库还包括：

- 适用于 macOS、Windows 和 Linux 的 Tauri 桌面应用；
- 可自托管的 Docker/Web 应用；
- [`@dbx-app/cli`](packages/cli/README.md)；
- [`@dbx-app/mcp-server`](packages/mcp-server/README.md)；
- 可选数据库 Agent 与 JDBC 插件。

这些产品支持的数据库和功能范围比独立安卓客户端更广。

## 开源协议与社区

DBX 使用 [Apache License 2.0](LICENSE)。

- [GitHub Issues](https://github.com/t8y2/dbx/issues)
- [QQ 群：1087880322](https://qm.qq.com/cgi-bin/qm/qr?k=&group_code=1087880322)
- [Discord](https://discord.gg/W7NyVDRt6a)
