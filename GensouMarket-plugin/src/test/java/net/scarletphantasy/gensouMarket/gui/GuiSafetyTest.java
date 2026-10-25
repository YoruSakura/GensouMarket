package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.shop.PriceResult;
import net.scarletphantasy.gensouMarket.shop.ShopManager;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import net.scarletphantasy.gensouMarket.trade.TradeManager;
import net.scarletphantasy.gensouMarket.trade.TradeManager.TradeSession;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GuiSafetyTest {
    @Test void shopConfirmationDisplaysAndSubmitsTheBatchTotal() {
        var plugin = mock(GensouMarket.class);
        var manager = mock(ShopManager.class);
        var config = mock(ConfigManager.class);
        var player = mock(Player.class);
        var item = new ShopItem("limited", Material.DIAMOND, 100);
        var inventory = top(GuiHolder.GuiType.SHOP);
        var holder = (GuiHolder) inventory.getHolder();
        holder.setData("items", List.of(item));
        var displayedItem = mock(ItemStack.class);
        var displayedMaterial = mock(Material.class);
        var event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(displayedItem);
        when(event.isShiftClick()).thenReturn(true);
        when(displayedItem.getType()).thenReturn(displayedMaterial);
        when(displayedMaterial.isAir()).thenReturn(false);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(plugin.getShopManager()).thenReturn(manager);
        when(plugin.getConfigManager()).thenReturn(config);
        when(config.getPrefix()).thenReturn("");
        when(config.getPriceChangeTolerance()).thenReturn(0.05);
        when(manager.computeAvailableStock(item)).thenReturn(64);
        when(manager.computePurchasePriceResult(item, 64)).thenReturn(
                new PriceResult(100, 7975.04, 124.61, 100, 1, 0, 1, 1, false));
        try (var instance = mockStatic(GensouMarket.class)) {
            instance.when(GensouMarket::getInstance).thenReturn(plugin);
            var listener = new GuiListener(plugin);
            listener.onInventoryClick(event);
            verify(player).sendMessage(contains(MessageUtil.formatMoney(7975.04)));
            verify(manager, never()).buyFromShop(any(), anyString(), anyInt(), any(Runnable.class));
            listener.onInventoryClick(event);
            verify(manager).buyFromShop(eq(player), eq("limited"), eq(64), any(Runnable.class));
        }
    }

    @Test void normalMenuRejectsDragsIntoTopInventory() {
        var top = top(GuiHolder.GuiType.MARKET_BROWSE);
        var event = mock(InventoryDragEvent.class);
        when(event.getInventory()).thenReturn(top);
        when(event.getRawSlots()).thenReturn(Set.of(0, 54));
        new GuiListener(null).onInventoryDrag(event);
        verify(event).setCancelled(true);
    }

    @Test void bottomOnlyDragRemainsAllowed() {
        var event = mock(InventoryDragEvent.class);
        var inventory = top(GuiHolder.GuiType.SHOP);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getRawSlots()).thenReturn(Set.of(54, 55));
        new GuiListener(null).onInventoryDrag(event);
        verify(event, never()).setCancelled(true);
    }

    @Test void crossViewCollectionAndCreativeCloningAreBlockedFromBottomInventory() {
        for (InventoryAction action : new InventoryAction[]{InventoryAction.COLLECT_TO_CURSOR, InventoryAction.CLONE_STACK, InventoryAction.UNKNOWN}) {
            var plugin = mock(GensouMarket.class);
            var manager = mock(TradeManager.class);
            var player = mock(Player.class);
            var top = top(GuiHolder.GuiType.TRADE);
            var a = UUID.randomUUID();
            var session = new TradeSession(a, UUID.randomUUID(), "A", "B");
            session.setPlayerAInventory(top);
            when(player.getUniqueId()).thenReturn(a);
            when(plugin.getTradeManager()).thenReturn(manager);
            when(manager.getSession(a)).thenReturn(session);
            var event = mock(InventoryClickEvent.class);
            when(event.getInventory()).thenReturn(top);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlot()).thenReturn(54);
            when(event.getAction()).thenReturn(action);
            new GuiListener(plugin).onInventoryClick(event);
            verify(event).setCancelled(true);
        }
    }

    @Test void orphanTradeWindowCannotAcceptItems() {
        var plugin = mock(GensouMarket.class);
        var manager = mock(TradeManager.class);
        when(plugin.getTradeManager()).thenReturn(manager);
        var event = mock(InventoryDragEvent.class);
        var inventory = top(GuiHolder.GuiType.TRADE);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(mock(Player.class));
        when(event.getRawSlots()).thenReturn(Set.of(0));
        new GuiListener(plugin).onInventoryDrag(event);
        verify(event).setCancelled(true);
    }

    @Test void delayedSyncCannotWriteIntoReplacementSession() {
        var plugin = mock(GensouMarket.class);
        var manager = mock(TradeManager.class);
        var player = mock(Player.class);
        var id = UUID.randomUUID();
        var inventory = top(GuiHolder.GuiType.TRADE);
        var old = new TradeSession(id, UUID.randomUUID(), "A", "B");
        old.setPlayerAInventory(inventory);
        old.setConfirmed(id, true);
        when(plugin.getTradeManager()).thenReturn(manager);
        when(manager.getSession(id)).thenReturn(old);
        when(player.getUniqueId()).thenReturn(id);
        var scheduler = mock(BukkitScheduler.class);
        var callback = new AtomicReference<Runnable>();
        when(scheduler.runTask(any(GensouMarket.class), any(Runnable.class))).thenAnswer(call -> {
            callback.set(call.getArgument(1)); return null;
        });
        try (var bukkit = mockStatic(Bukkit.class); var gui = mockStatic(TradeGui.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            gui.when(() -> TradeGui.refreshStatusBar(any(), any(), any())).thenAnswer(call -> null);
            var event = mock(InventoryDragEvent.class);
            when(event.getInventory()).thenReturn(inventory);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getRawSlots()).thenReturn(Set.of(0));
            new GuiListener(plugin).onInventoryDrag(event);
            assertTrue(old.hasPendingSync());
            assertFalse(old.isConfirmed(id));
            var replacement = new TradeSession(id, UUID.randomUUID(), "A", "C");
            when(manager.getSession(id)).thenReturn(replacement);
            callback.get().run();
            verify(inventory, never()).getItem(anyInt());
            assertNull(replacement.getMyItems(id)[0]);
        }
    }

    private Inventory top(GuiHolder.GuiType type) {
        var inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(new GuiHolder(type));
        when(inventory.getSize()).thenReturn(54);
        return inventory;
    }
}
