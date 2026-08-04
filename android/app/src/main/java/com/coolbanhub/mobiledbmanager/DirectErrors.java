package com.coolbanhub.mobiledbmanager;

final class DirectErrors {
    private DirectErrors() {}

    static String friendlyMessage(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            if (cause instanceof javax.net.ssl.SSLHandshakeException
                    || message.contains("trust anchors")
                    || message.contains("PKIX path")) {
                return "SSL 证书验证失败：服务器使用了 Android 不信任的证书。"
                        + "本地或自签名环境请在 SSL 页选择“仅加密”，或关闭 SSL；"
                        + "生产环境请配置受信任证书。";
            }
            cause = cause.getCause();
        }
        if (error instanceof NoClassDefFoundError) {
            return "数据库驱动与当前 Android 运行时不兼容：" + error.getMessage();
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String rootMessage = root.getMessage();
        if (root != error && rootMessage != null && !rootMessage.isEmpty() && !message.contains(rootMessage)) {
            return message + "；根因：" + rootMessage;
        }
        return message;
    }
}
