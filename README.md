# Mobile DB Manager

Mobile DB Manager 是一个可独立运行的 Android 数据库管理客户端。应用通过 Android
原生驱动从手机直接连接数据库，不需要部署 Web 服务，也不需要账号会话或移动端
API 网关。

## 功能范围

- 本机创建、编辑、测试、搜索、收藏、分组和删除数据库连接。
- 使用 Android Keystore AES-GCM 加密保存数据库密码、连接串、代理密码、SSH
  密码和私钥。
- 支持 PostgreSQL、MySQL/MariaDB、SQL Server 的元数据浏览、SQL 查询和表数据
  编辑。
- 支持 Redis Standalone 的 Key 浏览、编辑和 TTL 操作。
- 支持 MongoDB 的数据库、集合、文档浏览和文档编辑。
- 支持 etcd v3 JSON Gateway 的前缀查询、键值详情和单键写入。
- 支持 TLS、SSH 本地端口转发和 HTTP CONNECT 代理。
- 支持 SQL 格式化、具名 JSON 参数、元数据补全、查询取消、查询历史、本机 SQL
  收藏和结果导出分享。

## 数据库支持

| 数据库 | 连接测试 | 浏览/查询 | 写入 |
| --- | --- | --- | --- |
| PostgreSQL | 支持 | 支持 SQL 与表数据 | 支持，需要主键 |
| MySQL / MariaDB | 支持 | 支持 SQL 与表数据 | 支持，需要主键 |
| SQL Server | 支持 | 支持 SQL 与表数据 | 支持，需要主键 |
| Redis Standalone | 支持 | 支持 Key 浏览 | 支持固定动作 |
| MongoDB | 支持 | 支持集合与文档 | 支持文档插入、替换、删除 |
| etcd | 支持 | 支持前缀与详情 | 支持 put/delete |

Redis 当前不支持 Sentinel 和 Cluster。MongoDB URI 已包含完整路由信息，不能同时
启用应用内 SSH 或 HTTP 隧道。etcd 需要启用 v3 JSON Gateway。

## 开发环境

- Node.js 22.13 或更高版本
- pnpm 10.27.0
- JDK 21
- Android SDK 36
- Android 8.0（API 26）或更高版本的设备或模拟器

## 常用命令

安装依赖：

```bash
pnpm install --frozen-lockfile
```

运行前端单元测试：

```bash
pnpm test
```

构建 WebView 资源并同步 Capacitor Android 工程：

```bash
pnpm sync
```

构建 Debug APK：

```bash
pnpm android:build:debug
```

APK 输出位置：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

打开 Android Studio：

```bash
pnpm open
```

运行 Android 本地单元测试：

```bash
cd android
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

发布包输出位置：

```text
android/app/build/outputs/bundle/release/app-release.aab
```

密钥库和密码只能通过环境变量提供，不得提交到仓库。

## 安全建议

- 使用 WireGuard、Tailscale 或企业 VPN 接入数据库内网。
- 不要把生产数据库端口直接暴露到公网。
- 使用独立的最小权限数据库账号。
- 为允许写入的生产连接开启生产保护。
- TLS 优先选择“验证证书和主机名”；“仅加密”只用于受控自签名环境。
- SSH 连接填写服务器 SHA256 主机密钥指纹。

## Android 驱动兼容层

Android 不包含完整 Java SE 的 `java.lang.management` 和 JMX。构建脚本会为部分
驱动生成 Android 专用兼容 Jar：

- PostgreSQL：替换结果缓冲区内存检测。
- MySQL / MariaDB：移除连接池 JMX 注册。
- MongoDB：移除 JMX 注册，并补充 Android 缺失的 SASL 类型契约。

SQL Server 使用 Android ART 兼容的 jTDS 驱动。
