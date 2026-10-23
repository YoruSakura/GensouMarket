package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecycleEscrowTest {
    @Test void partialRemovalKeepsOriginalStackAndMetadataInEscrow() throws Exception {
        var plugin = mock(GensouMarket.class);
        when(plugin.getConfigManager()).thenReturn(mock(ConfigManager.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var manager = new RecycleManager(plugin);
        var player = mock(Player.class);
        var inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        // Cloning a real stack is Bukkit's metadata-preserving operation, including container contents.
        var first = mock(ItemStack.class);
        var firstCopy = mock(ItemStack.class);
        var second = mock(ItemStack.class);
        var secondCopy = mock(ItemStack.class);
        when(first.getType()).thenReturn(Material.SHULKER_BOX);
        when(second.getType()).thenReturn(Material.SHULKER_BOX);
        when(first.getAmount()).thenReturn(2);
        when(second.getAmount()).thenReturn(5);
        when(first.clone()).thenReturn(firstCopy);
        when(second.clone()).thenReturn(secondCopy);
        when(inventory.getContents()).thenReturn(new ItemStack[]{first, second});
        var method = RecycleManager.class.getDeclaredMethod("removeMaterial", Player.class, Material.class, int.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") var removed = (List<ItemStack>) method.invoke(manager, player, Material.SHULKER_BOX, 4);
        assertEquals(List.of(firstCopy, secondCopy), removed);
        verify(firstCopy).setAmount(2);
        verify(secondCopy).setAmount(2);
        verify(inventory).setItem(0, null);
        verify(second).setAmount(3);
        verify(inventory).setItem(1, second);
        verify(first, never()).setItemMeta(any());
        verify(second, never()).setItemMeta(any());
    }
}
