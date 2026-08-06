package org.gms.wz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WZ imgdir XML → WzNode 树解析：容器/叶子值/类型转换/未知标签忽略。
 */
class WzXmlParserTest {

    @TempDir
    Path tmp;

    private Path writeSample(String xml) throws IOException {
        Path f = tmp.resolve("sample.img.xml");
        Files.writeString(f, xml);
        return f;
    }

    @Test
    @DisplayName("imgdir 嵌套 + int/string/float 叶子解析")
    void parsesImgDirTree() throws IOException {
        Path f = writeSample("""
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="100.img">
                  <imgdir name="info">
                    <int name="town" value="1"/>
                    <float name="mobRate" value="1.5"/>
                    <string name="onUserEnter" value="abc"/>
                  </imgdir>
                </imgdir>
                """);

        WzNode root = WzXmlParser.parse(f);

        assertThat(root.name()).isEqualTo("100.img");
        assertThat(root.child("info")).isPresent();
        assertThat(root.child("info").get().getInt("town")).hasValue(1);
        assertThat(root.child("info").get().getDouble("mobRate")).hasValue(1.5);
        assertThat(root.child("info").get().getString("onUserEnter")).hasValue("abc");
    }

    @Test
    @DisplayName("vector 存为 x,y；canvas 忽略")
    void vectorStoredCanvasIgnored() throws IOException {
        Path f = writeSample("""
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="m.img">
                  <vector name="lt" x="1" y="-2"/>
                  <canvas name="icon" width="32" height="32"/>
                </imgdir>
                """);

        WzNode root = WzXmlParser.parse(f);

        assertThat(root.getString("lt")).hasValue("1,-2");
        assertThat(root.child("icon")).isEmpty();
    }

    @Test
    @DisplayName("缺失/非法叶子返回 empty，不回崩")
    void missingAndBadValues() throws IOException {
        Path f = writeSample("""
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="m.img">
                  <int name="ok" value="7"/>
                </imgdir>
                """);

        WzNode root = WzXmlParser.parse(f);

        assertThat(root.getInt("missing")).isEmpty();
        assertThat(root.getString("missing")).isEmpty();
        assertThat(root.getInt("ok")).hasValue(7);
    }
}
