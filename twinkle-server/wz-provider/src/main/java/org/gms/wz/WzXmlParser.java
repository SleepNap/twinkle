package org.gms.wz;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

/**
 * WZ 解包 XML（imgdir 树）解析器。按需 DOM 解析单个文件（架构 6.4：配置直接指定 WZ 目录、
 * 单份数据；地图 XML 大，只解析被请求的，不常驻全部）。
 *
 * <p>imgdir → WzNode 容器；int/short/string/float/vector → 叶子值。
 * canvas/audio 等数据块忽略（本服务端不解析图片）。
 */
public final class WzXmlParser {

    private WzXmlParser() {
    }

    /**
     * 解析单个 img.xml 文件为 WzNode 树。
     *
     * @throws IllegalArgumentException 文件不存在 / 解析失败
     */
    public static WzNode parse(Path file) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // 防 XXE
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(file.toFile());
            Element root = doc.getDocumentElement(); // <imgdir name="xxx.img">
            return parseImgDir(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("WZ XML 解析失败: " + file, e);
        }
    }

    private static WzNode parseImgDir(Element imgdir) {
        WzNode node = new WzNode(imgdir.getAttribute("name"));
        NodeList children = imgdir.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            switch (e.getTagName()) {
                case "imgdir" -> node.putChild(parseImgDir(e));
                case "int", "short", "string", "float", "long" ->
                        node.putValue(e.getAttribute("name"), e.getAttribute("value"));
                case "vector" -> node.putValue(e.getAttribute("name"),
                        e.getAttribute("x") + "," + e.getAttribute("y"));
                default -> {
                    // canvas / audio 等数据块不解析（服务端不用图片）
                }
            }
        }
        return node;
    }
}
