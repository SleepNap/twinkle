package org.gms.net.encryption;

/**
 * 会话加密器配对：发送 / 接收两个方向各持一个 {@link AesCipher}。
 *
 * <p>v83 握手后服务端为每条连接持有收发两份加密状态：发送 IV 由
 * {@link InitializationVector#generateSend()} 生成、接收 IV 由
 * {@link InitializationVector#generateReceive()} 生成，握手包内一并下发给客户端。
 *
 * <p>两个方向构造版本 key 不同（v83 协议事实）：客户端用 {@code 0xFFFF - version}
 * 校验服务端发出的包头（发送方向），用 {@code version} 校验服务端解自己包的头部
 * （接收方向）。因此发送侧用 {@code 0xFFFF - mapleVersion}、接收侧用
 * {@code mapleVersion}——镜像真实客户端的校验关系（思路参考自 BeiDou-Server
 * ClientCyphers，实现自研）。
 */
public final class CipherPair {

    private final short mapleVersion;
    private final AesCipher sendCipher;
    private final AesCipher receiveCipher;

    public CipherPair(short mapleVersion) {
        this.mapleVersion = mapleVersion;
        this.sendCipher = new AesCipher(InitializationVector.generateSend(), (short) (0xFFFF - mapleVersion));
        this.receiveCipher = new AesCipher(InitializationVector.generateReceive(), mapleVersion);
    }

    public AesCipher send() {
        return sendCipher;
    }

    public AesCipher receive() {
        return receiveCipher;
    }

    /** 本连接加密用的协议版本（握手 hello 明文与加密 header 共用的单源）。 */
    public short mapleVersion() {
        return mapleVersion;
    }
}
