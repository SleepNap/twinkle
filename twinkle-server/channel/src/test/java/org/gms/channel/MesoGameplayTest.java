package org.gms.channel;

import org.gms.logic.game.DefaultItemSystem;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class MesoGameplayTest {
    @Test public void dropAndConcurrentPickupConserveMoneyAndEncodeNoItemExpiration() throws Exception {
        var sessions = new PlayerSessionRegistry(); var map = new MapleMap();
        var owner = joined(sessions, 1, map); var first = joined(sessions, 2, map); var second = joined(sessions, 3, map);
        owner.character.setMeso(1000);
        var data = GameDataProvider.fixed(Map.of(), Map.of());
        try (var drops = new GroundDropService(new DefaultItemSystem(new DefaultVersionGate(), data), data, sessions, Clock.systemUTC());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(drops.dropMesos(owner.character, -1)).isFalse();
            assertThat(drops.dropMesos(owner.character, 50001)).isFalse();
            assertThat(drops.dropMesos(owner.character, 500)).isTrue();
            assertThat(owner.character.getMeso()).isEqualTo(500);
            var wire = new ByteArrayInPacket(first.sent.getFirst().getBytes());
            assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.DROP_ITEM_FROM_MAPOBJECT.getValue());
            assertThat(wire.readByte()).isEqualTo((byte) 2); int oid = wire.readInt();
            assertThat(wire.readByte()).isOne(); assertThat(wire.readInt()).isEqualTo(500);
            wire.skip(13); assertThat(wire.available()).isOne(); assertThat(wire.readByte()).isZero();
            first.character.setMeso(Integer.MAX_VALUE);
            assertThat(drops.pickup(first.character, oid)).isFalse();
            first.character.setMeso(0);
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return drops.pickup(first.character, oid); });
            var b = executor.submit(() -> { start.await(); return drops.pickup(second.character, oid); });
            start.countDown(); assertThat(a.get(5, TimeUnit.SECONDS) ^ b.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(first.character.getMeso() + second.character.getMeso()).isEqualTo(500);
            assertThat(drops.pickup(owner.character, oid)).isFalse();
        }
    }

    @Test public void wrongMapStaleConnectionAndVersionCannotConsumeMoney() {
        var sessions = new PlayerSessionRegistry(); var map = new MapleMap();
        var owner = joined(sessions, 1, map); var outsider = joined(sessions, 2, new MapleMap());
        var versions = new DefaultVersionGate(); var data = GameDataProvider.fixed(Map.of(), Map.of());
        owner.character.setMeso(1000);
        try (var drops = new GroundDropService(new DefaultItemSystem(versions, data), data, sessions, Clock.systemUTC())) {
            assertThat(drops.dropMesos(owner.character, 500)).isTrue();
            var wire = new ByteArrayInPacket(owner.sent.getLast().getBytes()); wire.skip(3); int oid = wire.readInt();
            assertThat(drops.pickup(outsider.character, oid)).isFalse();
            joined(sessions, 1, map);
            assertThat(drops.pickup(owner.character, oid)).isFalse();
            assertThat(drops.dropMesos(owner.character, 100)).isFalse();
            outsider.character.setMeso(1000); versions.onReload();
            assertThat(drops.dropMesos(outsider.character, 500)).isFalse();
            assertThat(outsider.character.getMeso()).isEqualTo(1000);
        }
    }

    private static GameplayTestSession joined(PlayerSessionRegistry sessions, long id, MapleMap map) {
        var session = new GameplayTestSession(id, map); sessions.claim(id, session); return session;
    }
}
