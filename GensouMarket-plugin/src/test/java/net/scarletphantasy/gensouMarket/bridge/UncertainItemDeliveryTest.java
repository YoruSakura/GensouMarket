package net.scarletphantasy.gensouMarket.bridge;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.bridge.protocol.OutgoingPacket;
import net.scarletphantasy.gensouMarket.bridge.protocol.PacketType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UncertainItemDeliveryTest {
    @Test void missingItemAckLogsRequestWithoutRiskingDuplicateFallback() {
        var plugin = mock(GensouMarket.class);
        var logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.isEnabled()).thenReturn(true);
        var player = mock(Player.class);
        var scheduler = mock(BukkitScheduler.class);
        var timeout = new AtomicReference<Runnable>();
        var task = mock(BukkitTask.class);
        when(scheduler.runTaskLater(any(GensouMarket.class), any(Runnable.class), eq(200L))).thenAnswer(call -> {
            timeout.set(call.getArgument(1)); return task;
        });
        var failures = new AtomicInteger();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            var bridge = new ProxyBridge(plugin, "gensoumarket:main", "a");
            assertTrue(bridge.sendWithFailHandler(new OutgoingPacket(PacketType.REMOTE_GIVE_ITEM, "request-id", "a")
                    .put("targetPlayerUuid", "target"), ignored -> failures.incrementAndGet()));
            timeout.get().run();
        }
        assertEquals(0, failures.get());
        verify(logger).severe(contains("requestId=request-id target=target"));
    }
}
