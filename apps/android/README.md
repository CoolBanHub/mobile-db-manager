# DBX 安卓端

DBX 安卓端是一款可独立运行的移动数据库客户端。应用内置数据库驱动，手机直接
连接数据库，不需要部署任何配套服务。

## 安卓端功能

- 创建、编辑、测试和删除本机数据库连接。
- 使用 Android Keystore AES-GCM 加密保存数据库密码、连接串、代理密码、
  SSH 密码和私钥。
- 支持连接搜索、分组、收藏、自定义标签以及开发、预发、生产环境标记。
- 支持数据库、Schema、表、视图、字段、索引、外键和存储过程浏览。
- 支持只读查询、高级写入、生产连接二次确认、查询超时和查询取消。
- 支持结果分页、表数据筛选与排序、单元格修改、新增行和删除行。
- 支持 SQL 格式化、参数替换、元数据补全、PostgreSQL/MySQL 执行计划和本机
  SQL 文件。
- 支持 CSV、JSON、Markdown、XLSX 导出并调用 Android 系统分享。
- 支持本机查询历史和本机 SQL 收藏目录。
- 支持数据库 TLS、SSH 本地端口转发和 HTTP CONNECT 代理。

## 数据库支持

| 数据库 | 连接管理 | 连接测试 | 元数据与 SQL |
| --- | --- | --- | --- |
| PostgreSQL | 支持 | 支持 | 支持 |
| MySQL / MariaDB | 支持 | 支持 | 支持 |
| SQL Server | 支持 | 支持 | 支持 |
| MongoDB | 支持主机端口或 URI | 支持 `ping` | 支持集合与文档浏览、编辑 |
| Redis | 支持 Standalone | 支持 AUTH、SELECT、PING | 支持 Key 浏览、编辑与 TTL |
| etcd | 支持 v3 JSON Gateway | 支持状态与 Auth | 支持前缀查询、键值编辑 |

MongoDB URI 已包含完整路由信息，不能同时启用应用内 SSH 或 HTTP 隧道。Redis
当前只支持 Standalone，不支持 Sentinel 和 Cluster。etcd 需要启用 v3 JSON Gateway。

## 本机数据与安全

完整连接配置保存在应用私有目录，敏感字段由 Android Keystore 密钥加密。
界面层只能通过连接 ID 调用原生插件，读取连接详情时不会返回已经保存的密码、
SSH 私钥或完整连接串。

生产环境建议：

- 使用 WireGuard、Tailscale 或企业 VPN 接入数据库内网。
- 配置数据库 IP 白名单，不要直接向公网开放数据库端口。
- 使用独立的最小权限账号。
- 开启生产连接保护，写入前必须输入完整连接名称。
- TLS 优先选择“验证证书和主机名”；“仅加密”只用于受控的自签名环境。
- SSH 连接填写服务器 SHA256 主机密钥指纹。

## 开发环境

- Node.js 22.13 或更高版本
- pnpm 10.27.0
- JDK 21
- Android SDK 36
- Android 8.0（API 26）或更高版本的设备或模拟器

## 构建安卓应用

在仓库根目录安装依赖：

```bash
pnpm install --frozen-lockfile
```

构建前端资源并同步到 Android 工程：

```bash
pnpm android:sync
```

构建 Debug APK：

```bash
pnpm android:build:debug
```

输出文件：

```text
apps/android/android/app/build/outputs/apk/debug/app-debug.apk
```

打开 Android Studio：

```bash
pnpm android:open
```

运行安卓端单元测试：

```bash
cd apps/android/android
./gradlew :app:testDebugUnitTest
```

连接设备后运行仪器测试：

```bash
pnpm android:device:test
```

## 发布签名

发布前设置以下环境变量：

- `DBX_ANDROID_KEYSTORE`
- `DBX_ANDROID_STORE_PASSWORD`
- `DBX_ANDROID_KEY_ALIAS`
- `DBX_ANDROID_KEY_PASSWORD`
- `DBX_ANDROID_VERSION_CODE`
- `DBX_ANDROID_VERSION_NAME`

然后执行：

```bash
pnpm android:release
```

发布包输出到：

```text
apps/android/android/app/build/outputs/bundle/release/app-release.aab
```

密钥库和密码只能通过环境变量提供，不得提交到仓库。

## Android 驱动兼容层

Android 不包含完整 Java SE 的 `java.lang.management` 和 JMX。构建脚本会为
以下驱动生成 Android 专用兼容 Jar：

- PostgreSQL：替换结果缓冲区内存检测。
- MySQL / MariaDB：移除连接池 JMX 注册。
- MongoDB：移除 JMX 注册，并补充 Android 缺失的 SASL 类型契约。

SQL Server 使用 Android ART 兼容的 jTDS 驱动，不再打包 Microsoft 桌面 JDBC 驱动。

兼容实现使用 `Runtime.getRuntime().maxMemory()` 或无操作监控实现，不改变
DBX 安卓端使用的普通连接、查询和连接池行为。不要在 `implementation` 中
重新加入未经处理的原始驱动 Jar，否则会恢复 Android 运行时类解析错误。
