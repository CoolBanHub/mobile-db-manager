package javax.security.sasl;

import java.io.IOException;

/** MongoDB 内置 SCRAM 客户端使用的 Android 兼容异常。 */
public class SaslException extends IOException {
    public SaslException() {
        super();
    }

    public SaslException(String detail) {
        super(detail);
    }

    public SaslException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
