package org.gms.net.encryption;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 加密层字节级验证（红线 1：字节级兼容）。
 *
 * <p>AES-OFB crypt 会推进 IV，round-trip 需用「同初始 IV」的两个加密器同步推进：
 * 发送方加密结果喂给接收方，双向同步即可还原明文。
 */
class CipherTest {

    private static byte[] sample(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    @Test
    void aesCryptIsRoundTripWithSyncedIv() {
        InitializationVector iv = InitializationVector.generateSend();
        AesCipher sender = new AesCipher(iv, (short) 83);
        AesCipher receiver = new AesCipher(iv, (short) 83);

        byte[] plain = sample(1024);
        byte[] encrypted = sender.crypt(plain.clone());
        byte[] decrypted = receiver.crypt(encrypted);

        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void aesCryptSurvivesLargePayloadAcrossChunks() {
        // 超过单块 0x5B0，触发多块迭代
        InitializationVector iv = InitializationVector.generateReceive();
        AesCipher sender = new AesCipher(iv, (short) 83);
        AesCipher receiver = new AesCipher(iv, (short) 83);

        byte[] plain = sample(5000);
        assertThat(receiver.crypt(sender.crypt(plain.clone()))).isEqualTo(plain);
    }

    @Test
    void packetHeaderRoundTripLength() {
        InitializationVector iv = InitializationVector.generateSend();
        AesCipher cipher = new AesCipher(iv, (short) 83);

        int length = 137;
        byte[] header = cipher.packetHeader(length);
        // v83 header 4 字节按大端拼成 int（与 isValidHeader / decodePacketLength 对齐）
        int headerAsInt = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);

        assertThat(AesCipher.decodePacketLength(headerAsInt)).isEqualTo(length);
        assertThat(cipher.isValidHeader(headerAsInt)).isTrue();
    }

    @Test
    void invalidHeaderIsRejected() {
        InitializationVector iv = InitializationVector.generateSend();
        AesCipher cipher = new AesCipher(iv, (short) 83);

        // 篡改版本位 → 校验失败
        int bogus = (0x00 << 24) | (0x00 << 16) | 0x1234;
        assertThat(cipher.isValidHeader(bogus)).isFalse();
    }

    @Test
    void customCipherIsInverse() {
        byte[] plain = sample(300);
        byte[] encrypted = CustomCipher.encrypt(plain.clone());
        byte[] decrypted = CustomCipher.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void customCipherIsDeterministic() {
        byte[] a = sample(64);
        byte[] b = sample(64);
        byte[] encA = CustomCipher.encrypt(a);
        byte[] encB = CustomCipher.encrypt(b);
        assertThat(Arrays.equals(encA, encB)).isTrue();
    }
}
