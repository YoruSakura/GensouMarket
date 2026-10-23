package net.scarletphantasy.gensouMarket.market;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.model.Auction;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.model.MarketListing;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CancellationOwnerTest {
    @Test void adminCancellingListingReturnsItemToOnlineSeller() {
        var plugin = plugin();
        var storage = plugin.getStorage();
        var listing = new MarketListing();
        var ownerId = UUID.randomUUID();
        var owner = mock(Player.class);
        var bag = mock(PlayerInventory.class);
        var item = mock(ItemStack.class);
        listing.setSellerUuid(ownerId);
        listing.setItemStack(item);
        when(storage.getListing(1)).thenReturn(listing);
        when(storage.markListingExpiredIfActive(1)).thenReturn(true);
        when(owner.isOnline()).thenReturn(true);
        when(owner.getInventory()).thenReturn(bag);
        try (var bukkit = mockStatic(Bukkit.class); var instance = mockStatic(GensouMarket.class)) {
            instance.when(GensouMarket::getInstance).thenReturn(plugin);
            var scheduler = immediateScheduler();
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(owner);
            new MarketManager(plugin).cancelListing(admin(), 1);
        }
        verify(bag).addItem(item);
    }

    @Test void adminCancellingAuctionMailsOfflineSeller() {
        var plugin = plugin();
        var storage = plugin.getStorage();
        var auction = new Auction();
        var ownerId = UUID.randomUUID();
        auction.setSellerUuid(ownerId);
        auction.setItemData("original-serialized-item");
        auction.setCurrentPrice(100);
        when(storage.getAuction(1)).thenReturn(auction);
        when(storage.markAuctionCancelledIfMatch(1, 100, null)).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class); var instance = mockStatic(GensouMarket.class)) {
            instance.when(GensouMarket::getInstance).thenReturn(plugin);
            var scheduler = immediateScheduler();
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            new AuctionManager(plugin).cancelAuction(admin(), 1);
        }
        var mail = ArgumentCaptor.forClass(MailEntry.class);
        verify(storage).saveMail(mail.capture());
        assertEquals(ownerId, mail.getValue().getPlayerUuid());
        assertEquals("original-serialized-item", mail.getValue().getItemData());
    }

    private Player admin() {
        var admin = mock(Player.class);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());
        when(admin.isOnline()).thenReturn(true);
        when(admin.hasPermission("gensoumarket.admin")).thenReturn(true);
        return admin;
    }

    private GensouMarket plugin() {
        var plugin = mock(GensouMarket.class);
        when(plugin.getStorage()).thenReturn(mock(StorageProvider.class));
        var config = mock(ConfigManager.class);
        when(config.getPrefix()).thenReturn("");
        when(plugin.getConfigManager()).thenReturn(config);
        return plugin;
    }

    private BukkitScheduler immediateScheduler() {
        var scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTask(any(GensouMarket.class), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run(); return null;
        });
        when(scheduler.runTaskAsynchronously(any(GensouMarket.class), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run(); return null;
        });
        return scheduler;
    }
}
