package javax.security.sasl;

/**
 * Java SE SASL 客户端契约的 Android 兼容副本。
 *
 * <p>Android 未提供 {@code java.security.sasl} 模块；MongoDB SCRAM 客户端直接实现
 * 此接口，因此这里只需保留 SCRAM-SHA-1/256 认证所依赖的类型契约。
 */
public interface SaslClient {
    String getMechanismName();

    boolean hasInitialResponse();

    byte[] evaluateChallenge(byte[] challenge) throws SaslException;

    boolean isComplete();

    byte[] unwrap(byte[] incoming, int offset, int length) throws SaslException;

    byte[] wrap(byte[] outgoing, int offset, int length) throws SaslException;

    Object getNegotiatedProperty(String propertyName);

    void dispose() throws SaslException;
}
