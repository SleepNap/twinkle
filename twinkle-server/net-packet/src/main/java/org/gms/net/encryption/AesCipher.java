package org.gms.net.encryption;


import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.log4j.Log4j2;

/**
 * v83 会话加密核心（AES-OFB 变种，字节级兼容，红线 1）。
 *
 * <p>v83 客户端的传输加密不是标准 OFB 模式，而是基于单块 AES 的流式异或：
 * <ol>
 *   <li>每个会话持 4 字节 IV，由握手包下发。</li>
 *   <li>加密时将 IV 按 {@code 16 = 4×4} 复展开，对每个 16 字节块做一次 AES
 *       单块变换生成密钥流，与明文异或。</li>
 *   <li>数据加密完毕后推进 IV（{@link #advanceIv}），供下一个包使用。</li>
 * </ol>
 *
 * <p>包头发送侧另算（{@link #packetHeader}），接收侧据此校验并还原长度。
 * AES 密钥与 {@link #FUNNY_TABLE} 是 v83 协议固定常量，字节级兼容必须原值。
 *
 * <p>算法与常量参考 BeiDou-Server（OdinMS 系）理解，实现为自研组织。
 */
@Log4j2
public final class AesCipher {


    /** v83 固定 AES-256 密钥（协议常量）。 */
    private static final SecretKeySpec SESSION_KEY = new SecretKeySpec(new byte[]{
            0x13, 0x00, 0x00, 0x00,
            0x08, 0x00, 0x00, 0x00,
            0x06, 0x00, 0x00, 0x00,
            (byte) 0xB4, 0x00, 0x00, 0x00,
            0x1B, 0x00, 0x00, 0x00,
            0x0F, 0x00, 0x00, 0x00,
            0x33, 0x00, 0x00, 0x00,
            0x52, 0x00, 0x00, 0x00}, "AES");

    /** IV 推进用 256 字节置换表（协议常量，由 v83 客户端算法定义）。 */
    private static final byte[] FUNNY_TABLE = new byte[]{
            (byte) 0xEC, (byte) 0x3F, (byte) 0x77, (byte) 0xA4, (byte) 0x45, (byte) 0xD0, (byte) 0x71, (byte) 0xBF,
            (byte) 0xB7, (byte) 0x98, (byte) 0x20, (byte) 0xFC, (byte) 0x4B, (byte) 0xE9, (byte) 0xB3, (byte) 0xE1,
            (byte) 0x5C, (byte) 0x22, (byte) 0xF7, (byte) 0x0C, (byte) 0x44, (byte) 0x1B, (byte) 0x81, (byte) 0xBD,
            (byte) 0x63, (byte) 0x8D, (byte) 0xD4, (byte) 0xC3, (byte) 0xF2, (byte) 0x10, (byte) 0x19, (byte) 0xE0,
            (byte) 0xFB, (byte) 0xA1, (byte) 0x6E, (byte) 0x66, (byte) 0xEA, (byte) 0xAE, (byte) 0xD6, (byte) 0xCE,
            (byte) 0x06, (byte) 0x18, (byte) 0x4E, (byte) 0xEB, (byte) 0x78, (byte) 0x95, (byte) 0xDB, (byte) 0xBA,
            (byte) 0xB6, (byte) 0x42, (byte) 0x7A, (byte) 0x2A, (byte) 0x83, (byte) 0x0B, (byte) 0x54, (byte) 0x67,
            (byte) 0x6D, (byte) 0xE8, (byte) 0x65, (byte) 0xE7, (byte) 0x2F, (byte) 0x07, (byte) 0xF3, (byte) 0xAA,
            (byte) 0x27, (byte) 0x7B, (byte) 0x85, (byte) 0xB0, (byte) 0x26, (byte) 0xFD, (byte) 0x8B, (byte) 0xA9,
            (byte) 0xFA, (byte) 0xBE, (byte) 0xA8, (byte) 0xD7, (byte) 0xCB, (byte) 0xCC, (byte) 0x92, (byte) 0xDA,
            (byte) 0xF9, (byte) 0x93, (byte) 0x60, (byte) 0x2D, (byte) 0xDD, (byte) 0xD2, (byte) 0xA2, (byte) 0x9B,
            (byte) 0x39, (byte) 0x5F, (byte) 0x82, (byte) 0x21, (byte) 0x4C, (byte) 0x69, (byte) 0xF8, (byte) 0x31,
            (byte) 0x87, (byte) 0xEE, (byte) 0x8E, (byte) 0xAD, (byte) 0x8C, (byte) 0x6A, (byte) 0xBC, (byte) 0xB5,
            (byte) 0x6B, (byte) 0x59, (byte) 0x13, (byte) 0xF1, (byte) 0x04, (byte) 0x00, (byte) 0xF6, (byte) 0x5A,
            (byte) 0x35, (byte) 0x79, (byte) 0x48, (byte) 0x8F, (byte) 0x15, (byte) 0xCD, (byte) 0x97, (byte) 0x57,
            (byte) 0x12, (byte) 0x3E, (byte) 0x37, (byte) 0xFF, (byte) 0x9D, (byte) 0x4F, (byte) 0x51, (byte) 0xF5,
            (byte) 0xA3, (byte) 0x70, (byte) 0xBB, (byte) 0x14, (byte) 0x75, (byte) 0xC2, (byte) 0xB8, (byte) 0x72,
            (byte) 0xC0, (byte) 0xED, (byte) 0x7D, (byte) 0x68, (byte) 0xC9, (byte) 0x2E, (byte) 0x0D, (byte) 0x62,
            (byte) 0x46, (byte) 0x17, (byte) 0x11, (byte) 0x4D, (byte) 0x6C, (byte) 0xC4, (byte) 0x7E, (byte) 0x53,
            (byte) 0xC1, (byte) 0x25, (byte) 0xC7, (byte) 0x9A, (byte) 0x1C, (byte) 0x88, (byte) 0x58, (byte) 0x2C,
            (byte) 0x89, (byte) 0xDC, (byte) 0x02, (byte) 0x64, (byte) 0x40, (byte) 0x01, (byte) 0x5D, (byte) 0x38,
            (byte) 0xA5, (byte) 0xE2, (byte) 0xAF, (byte) 0x55, (byte) 0xD5, (byte) 0xEF, (byte) 0x1A, (byte) 0x7C,
            (byte) 0xA7, (byte) 0x5B, (byte) 0xA6, (byte) 0x6F, (byte) 0x86, (byte) 0x9F, (byte) 0x73, (byte) 0xE6,
            (byte) 0x0A, (byte) 0xDE, (byte) 0x2B, (byte) 0x99, (byte) 0x4A, (byte) 0x47, (byte) 0x9C, (byte) 0xDF,
            (byte) 0x09, (byte) 0x76, (byte) 0x9E, (byte) 0x30, (byte) 0x0E, (byte) 0xE4, (byte) 0xB2, (byte) 0x94,
            (byte) 0xA0, (byte) 0x3B, (byte) 0x34, (byte) 0x1D, (byte) 0x28, (byte) 0x0F, (byte) 0x36, (byte) 0xE3,
            (byte) 0x23, (byte) 0xB4, (byte) 0x03, (byte) 0xD8, (byte) 0x90, (byte) 0xC8, (byte) 0x3C, (byte) 0xFE,
            (byte) 0x5E, (byte) 0x32, (byte) 0x24, (byte) 0x50, (byte) 0x1F, (byte) 0x3A, (byte) 0x43, (byte) 0x8A,
            (byte) 0x96, (byte) 0x41, (byte) 0x74, (byte) 0xAC, (byte) 0x52, (byte) 0x33, (byte) 0xF0, (byte) 0xD9,
            (byte) 0x29, (byte) 0x80, (byte) 0xB1, (byte) 0x16, (byte) 0xD3, (byte) 0xAB, (byte) 0x91, (byte) 0xB9,
            (byte) 0x84, (byte) 0x7F, (byte) 0x61, (byte) 0x1E, (byte) 0xCF, (byte) 0xC5, (byte) 0xD1, (byte) 0x56,
            (byte) 0x3D, (byte) 0xCA, (byte) 0xF4, (byte) 0x05, (byte) 0xC6, (byte) 0xE5, (byte) 0x08, (byte) 0x49};

    private static final byte[] IV_ALPHA = {(byte) 0xF2, 0x53, 0x50, (byte) 0xC6};

    private final Cipher aesBlock;
    private byte[] iv;
    private final byte[] initialIv;

    /**
     * @param iv           会话初始 IV
     * @param mapleVersion 客户端版本（v83 = 83），header 校验与生成需要
     */
    public AesCipher(InitializationVector iv, short mapleVersion) {
        try {
            aesBlock = Cipher.getInstance("AES");
            aesBlock.init(javax.crypto.Cipher.ENCRYPT_MODE, SESSION_KEY);
        } catch (Exception e) {
            // 日志红线 9：log.error("描述", e)
            log.error("AES 加密器初始化失败", e);
            throw new IllegalStateException("AES 加密器初始化失败", e);
        }
        this.iv = iv.getBytes();
        this.initialIv = iv.getBytes();
        this.mapleVersion = (short) (((mapleVersion >> 8) & 0xFF) | ((mapleVersion << 8) & 0xFF00));
    }

    private final short mapleVersion;

    /**
     * 初始 IV（握手时下发给客户端）。crypt 会推进 IV，本方法返回不变的初始值。
     */
    public byte[] getInitialIv() {
        return initialIv.clone();
    }

    /**
     * 加解密数据（同一算法双向可用）。包数据长度通常 &lt; 0x5B0，一次会话推进。
     */
    public synchronized byte[] crypt(byte[] data) {
        int remaining = data.length;
        int chunk = 0x5B0;
        int offset = 0;
        while (remaining > 0) {
            byte[] keystream = expandIv(this.iv);
            if (remaining < chunk) {
                chunk = remaining;
            }
            for (int i = offset; i < offset + chunk; i++) {
                if ((i - offset) % keystream.length == 0) {
                    keystream = aesBlock(keystream);
                }
                data[i] ^= keystream[(i - offset) % keystream.length];
            }
            offset += chunk;
            remaining -= chunk;
            chunk = 0x5B4;
        }
        advanceIv();
        return data;
    }

    /**
     * 生成发送侧 4 字节包头：{@code [iv 高半 | iv 低半 | 异或(长度, iv) 高半 | 异或 低半]}，
     * 每字节 8 位。接收侧 {@link #decodePacketLength} 逆推长度，{@link #isValidHeader} 校验。
     */
    public byte[] packetHeader(int length) {
        int ivWord = (iv[3] & 0xFF) | ((iv[2] << 8) & 0xFF00);
        int key = ivWord ^ mapleVersion;
        int rotatedLength = ((length << 8) & 0xFF00) | ((length >>> 8) & 0xFF);
        int mixed = key ^ rotatedLength;
        return new byte[]{
                (byte) ((key >>> 8) & 0xFF),
                (byte) (key & 0xFF),
                (byte) ((mixed >>> 8) & 0xFF),
                (byte) (mixed & 0xFF)};
    }

    /**
     * 从接收侧 4 字节 header（int 小端读入）还原负载长度。
     */
    public static int decodePacketLength(int header) {
        int length = ((header >>> 16) ^ (header & 0xFFFF));
        return ((length << 8) & 0xFF00) | ((length >>> 8) & 0xFF);
    }

    /**
     * 校验接收侧 header 是否由本会话 IV 生成（防错包 / 篡改）。
     */
    public boolean isValidHeader(int header) {
        return (((header >> 24) & 0xFF) ^ (iv[2] & 0xFF)) == ((mapleVersion >> 8) & 0xFF)
                && (((header >> 16) & 0xFF) ^ (iv[3] & 0xFF)) == (mapleVersion & 0xFF);
    }

    private byte[] aesBlock(byte[] block) {
        try {
            // Cipher.getInstance("AES") 默认带 PKCS5Padding：满块也补一个整块，
            // doFinal 返回 17 字节。v83 算法只用前 16 字节（参考实现 arraycopy 截断），
            // 这里同样截断回块长，保持 keystream 恒为 16 字节。
            byte[] result = aesBlock.doFinal(block);
            return result.length == block.length ? result : java.util.Arrays.copyOf(result, block.length);
        } catch (Exception e) {
            log.error("AES 块变换失败", e);
            throw new IllegalStateException("AES 块变换失败", e);
        }
    }

    private static byte[] expandIv(byte[] source) {
        byte[] expanded = new byte[16];
        for (int i = 0; i < expanded.length; i++) {
            expanded[i] = source[i % source.length];
        }
        return expanded;
    }

    private void advanceIv() {
        this.iv = nextIv(this.iv);
    }

    /**
     * IV 推进：由 {@link #IV_ALPHA} 起始，逐字节经置换表 {@link #FUNNY_TABLE} 做异或/旋转混合，
     * 输出新的 4 字节 IV。算法源自 v83 客户端。
     */
    private static byte[] nextIv(byte[] oldIv) {
        byte[] out = IV_ALPHA.clone();
        for (byte b : oldIv) {
            mix(b, out);
        }
        return out;
    }

    private static byte[] mix(byte input, byte[] state) {
        // 逐 byte 语义（复合赋值每一步截断到 byte），与 v83 客户端算法一致
        byte elina = state[1];
        byte anna = input;
        byte moritz = FUNNY_TABLE[elina & 0xFF];
        moritz -= input;
        state[0] += moritz;
        moritz = state[2];
        moritz ^= FUNNY_TABLE[anna & 0xFF];
        elina -= moritz & 0xFF;
        state[1] = elina;
        elina = state[3];
        moritz = elina;
        elina -= state[0] & 0xFF;
        moritz = FUNNY_TABLE[moritz & 0xFF];
        moritz += input;
        moritz ^= state[2];
        state[2] = moritz;
        elina += FUNNY_TABLE[anna & 0xFF] & 0xFF;
        state[3] = elina;
        int word = (state[0] & 0xFF) | ((state[1] & 0xFF) << 8)
                | ((state[2] & 0xFF) << 16) | ((state[3] & 0xFF) << 24);
        int rotated = (word >>> 29) | (word << 3);
        state[0] = (byte) (rotated & 0xFF);
        state[1] = (byte) ((rotated >> 8) & 0xFF);
        state[2] = (byte) ((rotated >> 16) & 0xFF);
        state[3] = (byte) ((rotated >> 24) & 0xFF);
        return state;
    }
}
