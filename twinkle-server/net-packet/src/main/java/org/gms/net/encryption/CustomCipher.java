package org.gms.net.encryption;

/**
 * v83 第二层自定义变换（字节级兼容，红线 1）。
 *
 * <p>在 AES-OFB 之外，v83 客户端还会对负载做 6 轮字节变换（循环移位 + 异或 + 自累加），
 * 方向性：发送加密、接收解密互为逆过程。轮序固定、每轮按数据长度递减推进。
 *
 * <p>算法源自 v83 客户端协议，思路参考 BeiDou-Server（OdinMS 系），实现为自研组织。
 */
public final class CustomCipher {

    private CustomCipher() {
    }

    /**
     * 发送方向：6 轮正向变换，原地修改。
     */
    public static byte[] encrypt(byte[] data) {
        for (int round = 0; round < 6; round++) {
            byte remember = 0;
            byte length = (byte) (data.length & 0xFF);
            if (round % 2 == 0) {
                for (int i = 0; i < data.length; i++) {
                    byte cur = data[i];
                    cur = rollLeft(cur, 3);
                    cur += length;
                    cur ^= remember;
                    remember = cur;
                    cur = rollRight(cur, length & 0xFF);
                    cur = (byte) (~cur);
                    cur += 0x48;
                    length--;
                    data[i] = cur;
                }
            } else {
                for (int i = data.length - 1; i >= 0; i--) {
                    byte cur = data[i];
                    cur = rollLeft(cur, 4);
                    cur += length;
                    cur ^= remember;
                    remember = cur;
                    cur ^= 0x13;
                    cur = rollRight(cur, 3);
                    length--;
                    data[i] = cur;
                }
            }
        }
        return data;
    }

    /**
     * 接收方向：6 轮反向变换，原地修改。
     */
    public static byte[] decrypt(byte[] data) {
        // 逆序体现在"每轮是加密对应轮的逆操作"，轮次仍按 1→6 递增（轮序号与加密偏移 1）
        for (int round = 1; round <= 6; round++) {
            byte remember = 0;
            byte length = (byte) (data.length & 0xFF);
            if (round % 2 == 0) {
                for (int i = 0; i < data.length; i++) {
                    byte cur = data[i];
                    cur -= 0x48;
                    cur = (byte) (~cur);
                    cur = rollLeft(cur, length & 0xFF);
                    byte next = cur;
                    cur ^= remember;
                    remember = next;
                    cur -= length;
                    cur = rollRight(cur, 3);
                    data[i] = cur;
                    length--;
                }
            } else {
                for (int i = data.length - 1; i >= 0; i--) {
                    byte cur = data[i];
                    cur = rollLeft(cur, 3);
                    cur ^= 0x13;
                    byte next = cur;
                    cur ^= remember;
                    remember = next;
                    cur -= length;
                    cur = rollRight(cur, 4);
                    data[i] = cur;
                    length--;
                }
            }
        }
        return data;
    }

    private static byte rollLeft(byte in, int count) {
        int tmp = in & 0xFF;
        tmp = tmp << (count % 8);
        return (byte) ((tmp & 0xFF) | (tmp >> 8));
    }

    private static byte rollRight(byte in, int count) {
        int tmp = in & 0xFF;
        int shifted = (tmp << 8) >>> (count % 8);
        return (byte) ((shifted & 0xFF) | (shifted >>> 8));
    }
}
