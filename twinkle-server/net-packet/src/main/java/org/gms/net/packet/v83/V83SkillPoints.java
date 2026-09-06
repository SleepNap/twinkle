package org.gms.net.packet.v83;

import org.gms.net.packet.OutPacket;

/** v83 普通职业与龙神分栏 SP 的公共编码。 */
public final class V83SkillPoints {
    private V83SkillPoints() { }
    public static void write(OutPacket packet, int job, String encoded) {
        String[] fields = encoded == null || encoded.isBlank() ? new String[]{"0"} : encoded.split(",");
        if (job < 2200 || job > 2218) {
            packet.writeShort(points(fields[0], Short.MAX_VALUE));
            return;
        }
        int[] points = new int[Math.min(fields.length, 10)];
        int active = 0;
        for (int index = 0; index < points.length; index++) {
            points[index] = points(fields[index], 255);
            if (points[index] > 0) active++;
        }
        packet.writeByte(active);
        for (int index = 0; index < points.length; index++) {
            if (points[index] > 0) { packet.writeByte(index + 1); packet.writeByte(points[index]); }
        }
    }

    private static int points(String field, int maximum) {
        try { return Math.max(0, Math.min(maximum, Integer.parseInt(field.trim()))); }
        catch (NumberFormatException invalid) { return 0; }
    }
}
