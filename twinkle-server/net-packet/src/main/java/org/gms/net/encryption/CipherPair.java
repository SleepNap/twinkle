package org.gms.net.encryption;

/**
 * 会话加密器配对：发送 / 接收两个方向各持一个 {@link AesCipher}。
 *
 * <p>v83 握手后服务端为每条连接持有收发两份加密状态：发送 IV 由
 * {@link InitializationVector#generateSend()} 生成、接收 IV 由
 * {@link InitializationVector#generateReceive()} 生成，握手包内一并下发给客户端。
 */
public final class CipherPair {

    private final AesCipher sendCipher;
    private final AesCipher receiveCipher;

    public CipherPair(short mapleVersion) {
        this.sendCipher = new AesCipher(InitializationVector.generateSend(), mapleVersion);
        this.receiveCipher = new AesCipher(InitializationVector.generateReceive(), mapleVersion);
    }

    public AesCipher send() {
        return sendCipher;
    }

    public AesCipher receive() {
        return receiveCipher;
    }
}
