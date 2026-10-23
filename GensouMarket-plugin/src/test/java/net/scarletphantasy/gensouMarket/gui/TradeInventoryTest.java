package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.trade.TradeManager.TradeSession;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeInventoryTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test void changingOfferImmediatelyInvalidatesBothConfirmations() {
        var session = new TradeSession(a, b, "A", "B");
        session.setConfirmed(a, true);
        session.setConfirmed(b, true);
        session.markPendingSync(a);
        assertFalse(session.isConfirmed(a));
        assertFalse(session.isConfirmed(b));
        session.setConfirmed(a, true);
        session.setConfirmed(b, true);
        assertFalse(session.areBothConfirmed());
        session.finishSync(a);
        assertTrue(session.areBothConfirmed());
    }

    @Test void closingBeforeNextTickReturnsActualOffer() {
        var session = new TradeSession(a, b, "A", "B");
        var top = mock(Inventory.class);
        var player = mock(Player.class);
        var bag = mock(PlayerInventory.class);
        var item = item();
        session.setPlayerAInventory(top);
        when(top.getItem(TradeGui.MY_ITEM_SLOTS[0])).thenReturn(item);
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(bag);
        session.markPendingSync(a);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(a)).thenReturn(player);
            session.returnItems();
        }
        verify(bag).addItem(item);
        for (int slot : TradeGui.MY_ITEM_SLOTS) verify(top).setItem(slot, null);
    }

    @Test void removedItemInOldSnapshotIsNotDuplicatedOnClose() {
        var session = new TradeSession(a, b, "A", "B");
        var top = mock(Inventory.class);
        var player = mock(Player.class);
        var bag = mock(PlayerInventory.class);
        session.setPlayerAInventory(top);
        session.updateItems(a, new ItemStack[]{item()});
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(bag);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(a)).thenReturn(player);
            session.returnItems();
        }
        verifyNoInteractions(bag);
        assertNull(session.getMyItems(a)[0]);
    }

    @Test void completedTradeUsesActualBothInventoriesAndExcludesDisplayClones() {
        var session = new TradeSession(a, b, "A", "B");
        var topA = mock(Inventory.class);
        var topB = mock(Inventory.class);
        var playerA = mock(Player.class);
        var playerB = mock(Player.class);
        var bagA = mock(PlayerInventory.class);
        var bagB = mock(PlayerInventory.class);
        var itemA = item();
        var itemB = item();
        when(topA.getItem(0)).thenReturn(itemA);
        when(topB.getItem(0)).thenReturn(itemB);
        session.setPlayerAInventory(topA);
        session.setPlayerBInventory(topB);
        when(playerA.isOnline()).thenReturn(true);
        when(playerB.isOnline()).thenReturn(true);
        when(playerA.getInventory()).thenReturn(bagA);
        when(playerB.getInventory()).thenReturn(bagB);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(a)).thenReturn(playerA);
            bukkit.when(() -> Bukkit.getPlayer(b)).thenReturn(playerB);
            session.executeTrade();
        }
        verify(bagA).addItem(itemB);
        verify(bagB).addItem(itemA);
        verify(topA, never()).getItem(5);
        assertFalse(session.ownsInventory(b, topA));
    }

    private ItemStack item() {
        var item = mock(ItemStack.class);
        var material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(item.getType()).thenReturn(material);
        when(item.clone()).thenReturn(item);
        return item;
    }
}
