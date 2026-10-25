package net.scarletphantasy.gensouMarket.market;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.bridge.ClusterEventPublisher;
import net.scarletphantasy.gensouMarket.bridge.ProxyBridge;
import net.scarletphantasy.gensouMarket.bridge.protocol.IncomingPacket;
import net.scarletphantasy.gensouMarket.bridge.protocol.OutgoingPacket;
import net.scarletphantasy.gensouMarket.bridge.protocol.PacketCodec;
import net.scarletphantasy.gensouMarket.bridge.protocol.PacketType;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.model.Auction;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.messaging.MessageTooLargeException;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用生产拍卖结算、发布器、桥接和编解码器；玩家、调度、经济和存储在边界提供替身。 */
class AuctionItemDeliveryTest {
    @Test void utfOverflowCreatesOneWinnerMailContainingTheUnmodifiedItem() {
        var fixture = new Fixture("item:\n  pages:\n    - " + "测试书".repeat(24000), false);
        fixture.settleAndCheckFallback();
    }

    @Test void messageSizeRejectionAlsoCreatesOneWinnerMail() {
        var fixture = new Fixture("original-serialized-item", true);
        fixture.settleAndCheckFallback();
    }

    @Test void normalRemoteDeliveryAcknowledgementDoesNotCreateMailOrPayAgain() {
        var fixture = new Fixture("original-serialized-item", false);
        fixture.run(() -> {
            fixture.manager.endAuctionAsync(fixture.auction);
            var sent = fixture.sent.stream().filter(packet -> packet.type() == PacketType.REMOTE_GIVE_ITEM)
                    .findFirst().orElseThrow();
            assertEquals(fixture.auction.getItemData(), sent.payload().get("itemData"));
            assertEquals(fixture.auction.getHighestBidderUuid().toString(), sent.payload().get("targetPlayerUuid"));
            fixture.bridge.onPluginMessageReceived("gensoumarket:main", fixture.seller,
                    PacketCodec.encode(new OutgoingPacket(PacketType.REQUEST_ACK, sent.requestId(), "b")));
            fixture.manager.endAuctionAsync(fixture.auction);
            fixture.timeouts.forEach(Runnable::run);
        });
        verify(fixture.storage, never()).saveMail(any(MailEntry.class));
        verify(fixture.vault, times(1)).deposit(fixture.seller, 135);
        verify(fixture.logger, never()).severe(anyString());
    }

    private static class Fixture {
        final GensouMarket plugin = mock(GensouMarket.class);
        final StorageProvider storage = mock(StorageProvider.class);
        final VaultHook vault = mock(VaultHook.class);
        final Logger logger = mock(Logger.class);
        final Player seller = mock(Player.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final List<Runnable> timeouts = new ArrayList<>();
        final List<BukkitTask> timeoutTasks = new ArrayList<>();
        final List<IncomingPacket> sent = new ArrayList<>();
        final Auction auction = new Auction();
        final ProxyBridge bridge;
        final AuctionManager manager;

        Fixture(String itemData, boolean rejectMessage) {
            var config = mock(ConfigManager.class);
            when(config.getPrefix()).thenReturn("");
            when(config.getTransactionTax()).thenReturn(0.1);
            when(plugin.getConfigManager()).thenReturn(config);
            when(plugin.getStorage()).thenReturn(storage);
            when(plugin.getVaultHook()).thenReturn(vault);
            when(plugin.getLogger()).thenReturn(logger);
            when(plugin.isClusterEnabled()).thenReturn(true);
            when(plugin.isEnabled()).thenReturn(true);
            when(seller.isOnline()).thenReturn(true);
            when(vault.deposit(seller, 135)).thenReturn(true);
            auction.setId(7);
            auction.setSellerUuid(UUID.randomUUID());
            auction.setSellerName("Seller");
            auction.setHighestBidderUuid(UUID.randomUUID());
            auction.setHighestBidderName("Buyer");
            auction.setCurrentPrice(150);
            auction.setItemData(itemData);
            auction.setItemStack(mock(ItemStack.class));
            when(storage.getAuction(7)).thenReturn(auction);
            when(storage.markAuctionEndedIfActive(7)).thenReturn(true, false);
            when(storage.saveMail(any(MailEntry.class))).thenReturn(1);
            when(scheduler.runTask(any(GensouMarket.class), any(Runnable.class))).thenAnswer(call -> {
                call.getArgument(1, Runnable.class).run(); return null;
            });
            when(scheduler.runTaskAsynchronously(any(GensouMarket.class), any(Runnable.class))).thenAnswer(call -> {
                call.getArgument(1, Runnable.class).run(); return null;
            });
            when(scheduler.runTaskLater(any(GensouMarket.class), any(Runnable.class), eq(200L))).thenAnswer(call -> {
                timeouts.add(call.getArgument(1));
                var task = mock(BukkitTask.class);
                timeoutTasks.add(task);
                return task;
            });
            doAnswer(call -> {
                var packet = PacketCodec.decode(call.getArgument(2, byte[].class));
                if (rejectMessage && packet.type() == PacketType.REMOTE_GIVE_ITEM) {
                    throw new MessageTooLargeException("Message exceeds transport size limit");
                }
                sent.add(packet);
                return null;
            }).when(seller).sendPluginMessage(eq(plugin), eq("gensoumarket:main"), any(byte[].class));
            bridge = new ProxyBridge(plugin, "gensoumarket:main", "a");
            when(plugin.getClusterEventPublisher()).thenReturn(new ClusterEventPublisher(bridge));
            manager = new AuctionManager(plugin);
        }

        void settleAndCheckFallback() {
            run(() -> {
                assertDoesNotThrow(() -> manager.endAuctionAsync(auction));
                manager.endAuctionAsync(auction);
                timeouts.forEach(Runnable::run);
            });
            var mail = ArgumentCaptor.forClass(MailEntry.class);
            verify(storage, times(1)).saveMail(mail.capture());
            assertEquals(auction.getHighestBidderUuid(), mail.getValue().getPlayerUuid());
            assertEquals(auction.getItemData(), mail.getValue().getItemData());
            assertSame(auction.getItemStack(), mail.getValue().getItemStack());
            assertEquals(0, mail.getValue().getMoney());
            assertTrue(sent.stream().noneMatch(packet -> packet.type() == PacketType.REMOTE_GIVE_ITEM));
            verify(vault, times(1)).deposit(seller, 135);
            assertEquals(1, timeoutTasks.size());
            verify(timeoutTasks.getFirst()).cancel();
            verify(logger, never()).severe(anyString());
        }

        void run(Runnable action) {
            try (var bukkit = mockStatic(Bukkit.class); var instance = mockStatic(GensouMarket.class)) {
                instance.when(GensouMarket::getInstance).thenReturn(plugin);
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(seller));
                bukkit.when(() -> Bukkit.getPlayer(auction.getSellerUuid())).thenReturn(seller);
                action.run();
            }
        }
    }
}
