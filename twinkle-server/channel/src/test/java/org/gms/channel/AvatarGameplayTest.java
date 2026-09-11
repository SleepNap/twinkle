package org.gms.channel;

import org.gms.logic.game.DefaultProgressionSystem;
import org.gms.logic.game.DefaultMovementSystem;
import org.gms.logic.game.DefaultAvatarSystem;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AvatarGameplayTest {
    @Test public void expressionsAndChairsValidateOwnershipAndBroadcastOnlyToSameMap() {
        var sessions = new PlayerSessionRegistry(); var map = new MapleMap();
        var player = joined(sessions, 1, map); var peer = joined(sessions, 2, map);
        var elsewhere = joined(sessions, 3, new MapleMap());
        var handler = avatars(sessions, new DefaultVersionGate());
        handler.express(player, integer(0)); handler.express(player, integer(8));
        assertThat(peer.sent).isEmpty();
        handler.express(player, integer(3));
        var face = new ByteArrayInPacket(peer.sent.removeFirst().getBytes());
        assertThat(face.readUnsignedShort()).isEqualTo(SendOpcode.FACIAL_EXPRESSION.getValue());
        assertThat(face.readInt()).isOne(); assertThat(face.readInt()).isEqualTo(3); assertThat(face.available()).isZero();
        handler.sit(player, integer(3010000)); assertThat(peer.sent).isEmpty();
        Item chair = new Item(3010000); player.character.getInventory(InventoryType.SETUP).addItem(chair);
        chair.setExpiration(0); handler.sit(player, integer(3010000)); assertThat(peer.sent).isEmpty();
        chair.setExpiration(-1); handler.sit(player, integer(3010000));
        assertThat(player.character.getChairItemId()).isEqualTo(3010000);
        var show = new ByteArrayInPacket(peer.sent.getFirst().getBytes());
        assertThat(show.readUnsignedShort()).isEqualTo(SendOpcode.SHOW_CHAIR.getValue());
        assertThat(show.readInt()).isOne(); assertThat(show.readInt()).isEqualTo(3010000); assertThat(show.available()).isZero();
        var stand = new ByteArrayOutPacket(); stand.writeShort(-1);
        handler.stand(player, new ByteArrayInPacket(stand.getBytes()));
        assertThat(player.character.getChairItemId()).isZero();
        assertThat(player.sent.getLast().getBytes()).containsExactly((byte) 0xCD, (byte) 0, (byte) 0);
        assertThat(elsewhere.sent).isEmpty();
    }

    @Test public void staleAndTransitionRequestsAreRejectedAndRemovedChairClearsOnRefresh() {
        var sessions = new PlayerSessionRegistry(); var player = joined(sessions, 1, new MapleMap());
        var versions = new DefaultVersionGate(); var handler = avatars(sessions, versions);
        player.character.getInventory(InventoryType.SETUP).addItem(new Item(3010000));
        player.setAttr("mapTransition", true); handler.sit(player, integer(3010000));
        assertThat(player.character.getChairItemId()).isZero();
        player.setAttr("mapTransition", null); handler.sit(player, integer(3010000));
        player.character.getInventory(InventoryType.SETUP).removeItem((short) 1); handler.refresh(player);
        assertThat(player.character.getChairItemId()).isZero();
        player.character.getInventory(InventoryType.SETUP).addItem(new Item(3010000));
        versions.onReload(); handler.sit(player, integer(3010000));
        assertThat(player.character.getChairItemId()).isZero();
    }

    @Test public void movementCommitsSignedPositionAndStanceOnlyAfterCompleteValidation() {
        var sessions = new PlayerSessionRegistry(); var map = new MapleMap();
        var player = joined(sessions, 1, map); var peer = joined(sessions, 2, map);
        var elsewhere = joined(sessions, 3, new MapleMap());
        var versions = new DefaultVersionGate(); var handler = new MovePlayerHandler(new DefaultMovementSystem(versions), sessions);
        byte[] request = movement(-123, -456);
        for (int length = 0; length < request.length; length++) {
            handler.handle(player, new ByteArrayInPacket(Arrays.copyOf(request, length)));
        }
        assertThat(player.character.getX()).isZero(); assertThat(peer.sent).isEmpty();
        handler.handle(player, new ByteArrayInPacket(request));
        assertThat(player.character.getX()).isEqualTo(-123); assertThat(player.character.getY()).isEqualTo(-456);
        assertThat(player.character.getStance()).isEqualTo(6); assertThat(player.character.getFoothold()).isEqualTo(9);
        var wire = new ByteArrayInPacket(peer.sent.getFirst().getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.MOVE_PLAYER.getValue());
        assertThat(wire.readInt()).isOne(); assertThat(wire.readInt()).isZero();
        assertThat(wire.readBytes(wire.available())).isEqualTo(Arrays.copyOfRange(request, 9, request.length));
        assertThat(elsewhere.sent).isEmpty(); assertThat(player.sent).isEmpty(); peer.sent.clear();
        player.setAttr("mapTransition", true); handler.handle(player, new ByteArrayInPacket(movement(1, 2)));
        player.setAttr("mapTransition", null); versions.onReload();
        handler.handle(player, new ByteArrayInPacket(movement(3, 4)));
        assertThat(player.character.getX()).isEqualTo(-123); assertThat(peer.sent).isEmpty();
    }

    @Test public void supersededConnectionCannotMoveOrBroadcastThroughReplacement() {
        var sessions = new PlayerSessionRegistry(); var map = new MapleMap();
        var old = joined(sessions, 1, map); var peer = joined(sessions, 2, map);
        var replacement = joined(sessions, 1, map);
        var handler = new MovePlayerHandler(new DefaultMovementSystem(new DefaultVersionGate()), sessions);
        handler.handle(old, new ByteArrayInPacket(movement(9, 10)));
        assertThat(old.character.getX()).isZero(); assertThat(replacement.character.getX()).isZero(); assertThat(peer.sent).isEmpty();
    }

    private static AvatarHandler avatars(PlayerSessionRegistry sessions, DefaultVersionGate versions) {
        var progression = new DefaultProgressionSystem(versions);
        return new AvatarHandler(sessions, new DefaultAvatarSystem(progression::accepts),
                GameDataProvider.fixed(Map.of(3010000, new ItemData(3010000)), Map.of()), Clock.systemUTC());
    }
    private static ByteArrayInPacket integer(int value) {
        var packet = new ByteArrayOutPacket(); packet.writeInt(value); return new ByteArrayInPacket(packet.getBytes());
    }
    private static byte[] movement(int x, int y) {
        var packet = new ByteArrayOutPacket(); packet.skip(9).writeByte(1);
        packet.writeByte(0).writeShort(x).writeShort(y).writeInt(0).writeShort(9).writeByte(6).writeShort(30);
        return packet.getBytes();
    }
    private static GameplayTestSession joined(PlayerSessionRegistry sessions, long id, MapleMap map) {
        var session = new GameplayTestSession(id, map); sessions.claim(id, session); return session;
    }
}
