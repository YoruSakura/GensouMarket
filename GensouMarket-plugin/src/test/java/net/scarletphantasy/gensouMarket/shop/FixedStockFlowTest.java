package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FixedStockFlowTest {
    @Test void failedPaymentRestoresReservedStockAndDoesNotGiveItem() {
        var fixture = new Fixture();
        when(fixture.storage.getShopStock("limited")).thenReturn(1, 0, 1);
        when(fixture.storage.consumeShopStockIfEnough("limited", 1)).thenReturn(true);
        when(fixture.storage.addShopStockDelta("limited", 1)).thenReturn(true);
        when(fixture.vault.withdraw(fixture.player, 10)).thenReturn(false);
        fixture.buy();
        verify(fixture.storage).addShopStockDelta("limited", 1);
        verify(fixture.player, never()).getInventory();
        assertEquals(1, fixture.item.getAvailableStock());
        assertEquals(1, fixture.completed.get());
    }

    @Test void losingStockRaceDoesNotChargePlayer() {
        var fixture = new Fixture();
        when(fixture.storage.getShopStock("limited")).thenReturn(1, 0);
        fixture.buy();
        verify(fixture.vault, never()).withdraw(any(), anyDouble());
        verify(fixture.player, never()).getInventory();
        assertEquals(1, fixture.completed.get());
    }

    @Test void failedSetstockKeepsLocalValue() {
        var fixture = new Fixture();
        assertFalse(fixture.manager.setStock("limited", 50));
        assertEquals(1, fixture.item.getAvailableStock());
        when(fixture.storage.setShopStock("limited", 50)).thenReturn(true);
        assertTrue(fixture.manager.setStock("limited", 50));
        assertEquals(50, fixture.item.getAvailableStock());
    }

    private static class Fixture {
        final GensouMarket plugin = mock(GensouMarket.class);
        final StorageProvider storage = mock(StorageProvider.class);
        final VaultHook vault = mock(VaultHook.class);
        final Player player = mock(Player.class);
        final ShopItem item = new ShopItem("limited", Material.DIAMOND, 10);
        final AtomicInteger completed = new AtomicInteger();
        final ShopManager manager;

        Fixture() {
            var config = mock(ConfigManager.class);
            when(config.isShopEnabled()).thenReturn(true);
            when(config.getPrefix()).thenReturn("");
            when(plugin.getConfigManager()).thenReturn(config);
            when(plugin.getStorage()).thenReturn(storage);
            when(plugin.getVaultHook()).thenReturn(vault);
            when(player.hasPermission(anyString())).thenReturn(true);
            when(player.isOnline()).thenReturn(true);
            when(vault.has(player, 10)).thenReturn(true);
            item.setStockMode(ShopItem.StockMode.LIMITED);
            item.setAvailableStock(1);
            manager = spy(new ShopManager(plugin));
            doReturn(10.0).when(manager).computeCurrentPrice(item);
            manager.getShopItems().put("limited", item);
        }

        void buy() {
            var scheduler = mock(BukkitScheduler.class);
            when(scheduler.runTask(any(GensouMarket.class), any(Runnable.class))).thenAnswer(call -> {
                call.getArgument(1, Runnable.class).run(); return null;
            });
            when(scheduler.runTaskAsynchronously(any(GensouMarket.class), any(Runnable.class))).thenAnswer(call -> {
                call.getArgument(1, Runnable.class).run(); return null;
            });
            try (var bukkit = mockStatic(Bukkit.class); var instance = mockStatic(GensouMarket.class)) {
                instance.when(GensouMarket::getInstance).thenReturn(plugin);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                manager.buyFromShop(player, "limited", 1, completed::incrementAndGet);
            }
        }
    }
}
