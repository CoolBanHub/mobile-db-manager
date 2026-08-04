package com.coolbanhub.mobiledbmanager;

import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

final class DirectJdbcConnectionFactory {
    private DirectJdbcConnectionFactory() {}

    static Connection open(JSONObject config, String requestedDatabase) throws Exception {
        String type = config.optString("dbType");
        String host = config.optString("host");
        int port = config.optInt("port");
        String database = requestedDatabase == null || requestedDatabase.isEmpty()
                ? DirectJson.optionalDatabase(config) : requestedDatabase;
        if (database.isEmpty() && type.equals("postgres")) database = "postgres";
        if (database.isEmpty() && type.equals("sqlserver")) database = "master";
        String connectionString = config.optString("connectionString").trim();
        if (!connectionString.isEmpty()
                && (config.optBoolean("sshEnabled", false) || config.optBoolean("proxyEnabled", false))) {
            throw new IllegalArgumentException("使用 SSH/HTTP 隧道时请通过常规主机和端口连接，不要使用自定义 JDBC 连接串");
        }
        DirectTransport.Route route = DirectTransport.open(config, host, port);
        String url;
        if (!connectionString.isEmpty()) {
            url = connectionString;
        } else if (type.equals("postgres")) {
            Class.forName("org.postgresql.Driver");
            url = "jdbc:postgresql://" + route.host + ":" + route.port + "/" + database;
        } else if (type.equals("mysql")) {
            Class.forName("org.mariadb.jdbc.Driver");
            url = "jdbc:mariadb://" + route.host + ":" + route.port + "/" + database;
        } else if (type.equals("sqlserver")) {
            Class.forName("net.sourceforge.jtds.jdbc.Driver");
            url = "jdbc:jtds:sqlserver://" + route.host + ":" + route.port + "/" + database;
        } else {
            route.close();
            throw new IllegalArgumentException("当前 Android 直连版本尚未内置 " + type + " 驱动");
        }

        Properties properties = new Properties();
        properties.setProperty("user", config.optString("username"));
        properties.setProperty("password", config.optString("password"));
        int connectTimeoutSecs = Math.max(1, config.optInt("connectTimeoutSecs", 10));
        if (type.equals("mysql")) {
            properties.setProperty("connectTimeout", String.valueOf(connectTimeoutSecs * 1_000));
        } else if (type.equals("sqlserver")) {
            properties.setProperty("loginTimeout", String.valueOf(connectTimeoutSecs));
        } else {
            properties.setProperty("connectTimeout", String.valueOf(connectTimeoutSecs));
        }
        applySecurityProperties(
                properties,
                type,
                config.optBoolean("ssl", false),
                config.optString("sslMode", "verify-full"));
        DriverManager.setLoginTimeout(Math.max(1, config.optInt("connectTimeoutSecs", 10)));
        try {
            return DirectTransport.attach(DriverManager.getConnection(url, properties), route);
        } catch (Throwable error) {
            route.close();
            if (error instanceof Exception) throw (Exception) error;
            throw (Error) error;
        }
    }

    static void applySecurityProperties(Properties properties, String type, boolean ssl, String sslMode) {
        if (type.equals("sqlserver")) {
            properties.setProperty(
                    "ssl",
                    !ssl ? "off" : sslMode.equals("required") ? "require" : "authenticate");
            return;
        }
        if (ssl) {
            if (type.equals("postgres")) properties.setProperty("sslmode", sslMode);
            if (type.equals("mysql")) {
                properties.setProperty(
                        "sslMode",
                        sslMode.equals("required") ? "trust"
                                : sslMode.equals("verify-ca") ? "verify-ca" : "verify-full");
            }
            return;
        }

        if (type.equals("postgres")) properties.setProperty("sslmode", "disable");
        if (type.equals("mysql")) {
            properties.setProperty("sslMode", "disable");
            // MySQL 8 默认使用 caching_sha2_password。关闭 TLS 后，MariaDB
            // Connector/J 需要显式允许获取 RSA 公钥，否则会把自签名证书拒绝为认证错误。
            properties.setProperty("allowPublicKeyRetrieval", "true");
        }
    }
}
