package net.scarletphantasy.gensouMarket.market;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.gui.AuctionGui;
import net.scarletphantasy.gensouMarket.gui.MarketGui;
import net.scarletphantasy.gensouMarket.gui.RecycleGui;
import net.scarletphantasy.gensouMarket.gui.ShopGui;
import net.scarletphantasy.gensouMarket.shop.RecycleManager;
import net.scarletphantasy.gensouMarket.shop.ShopManager;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessPermissionTest {
    @Test void directBusinessAndGuiCallsCannotBypassFeaturePermissions() {
        var plugin = mock(GensouMarket.class);
        var config = mock(ConfigManager.class);
        var storage = mock(StorageProvider.class);
        var player = mock(Player.class);
        when(plugin.getConfigManager()).thenReturn(config);
        when(plugin.getStorage()).thenReturn(storage);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(config.getPrefix()).thenReturn("");
        when(player.hasPermission("gensoumarket.use")).thenReturn(true);
        try (var instance = mockStatic(GensouMarket.class)) {
            instance.when(GensouMarket::getInstance).thenReturn(plugin);
            new MarketManager(plugin).sellItem(player, 100);
            new MarketManager(plugin).buyListing(player, 1);
            new AuctionManager(plugin).createAuction(player, 100, 60);
            new AuctionManager(plugin).placeBid(player, 1, 110);
            new ShopManager(plugin).buyFromShop(player, "item", 1);
            assertFalse(new RecycleManager(plugin).recycleItem(player, null, 1));
            ShopGui.openShop(plugin, player, 0);
            RecycleGui.openRecycle(plugin, player, 0);
            AuctionGui.openAuctions(plugin, player, List.of(), 0);
            MarketGui.openPersonalShop(plugin, player, UUID.randomUUID(), "Owner", List.of(), 0);
        }
        verifyNoInteractions(storage);
        verify(player, never()).getInventory();
        verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
    }
}
