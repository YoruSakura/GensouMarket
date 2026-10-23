package net.scarletphantasy.gensouMarket.storage;

import net.scarletphantasy.gensouMarket.model.ShopItem;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SQLiteConsistencyTest {
    @TempDir Path folder;
    private SQLiteStorage serverA;
    private SQLiteStorage serverB;
    private Connection sql;
    private final UUID bidderA = UUID.randomUUID();
    private final UUID bidderB = UUID.randomUUID();

    @BeforeEach void open() throws Exception {
        serverA = new SQLiteStorage(folder.toFile());
        serverB = new SQLiteStorage(folder.toFile());
        serverA.initialize();
        serverB.initialize();
        sql = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("data.db"));
        execute("INSERT INTO auctions (id,seller_uuid,seller_name,item_data,starting_price,current_price,start_time,end_time) "
                + "VALUES (1,'" + UUID.randomUUID() + "','Seller','',100,100,0,9999999999999)");
    }

    @AfterEach void close() throws Exception {
        if (sql != null) sql.close();
        if (serverA != null) serverA.shutdown();
        if (serverB != null) serverB.shutdown();
    }

    @Test void firstBidAtStartingPriceInvalidatesOtherFirstBidSnapshot() {
        assertTrue(serverA.updateAuctionBidIfMatch(1, 100, null, 100, bidderA, "A"));
        assertFalse(serverB.updateAuctionBidIfMatch(1, 100, null, 110, bidderB, "B"));
        assertTrue(serverB.updateAuctionBidIfMatch(1, 100, bidderA, 110, bidderB, "B"));
    }

    @Test void cancellationCannotRefundAnOutdatedBidder() {
        assertTrue(serverA.updateAuctionBidIfMatch(1, 100, null, 100, bidderA, "A"));
        assertTrue(serverB.updateAuctionBidIfMatch(1, 100, bidderA, 110, bidderB, "B"));
        assertFalse(serverA.markAuctionCancelledIfMatch(1, 100, bidderA));
        assertTrue(serverA.markAuctionCancelledIfMatch(1, 110, bidderB));
        assertFalse(serverB.updateAuctionBidIfMatch(1, 110, bidderB, 120, bidderA, "A"));
        assertFalse(serverB.markAuctionEndedIfActive(1));
    }

    @Test void cancellationAlsoDetectsFirstBidWithoutPriceChange() {
        assertTrue(serverB.updateAuctionBidIfMatch(1, 100, null, 100, bidderB, "B"));
        assertFalse(serverA.markAuctionCancelledIfMatch(1, 100, null));
    }

    @Test void lastUnitCanOnlyBeSoldOnceAcrossConnections() throws Exception {
        serverA.saveShopData(limited(1));
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { gate.await(); return serverA.consumeShopStockIfEnough("limited", 1); });
            var b = executor.submit(() -> { gate.await(); return serverB.consumeShopStockIfEnough("limited", 1); });
            gate.countDown();
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        }
        assertEquals(0, serverA.getShopStock("limited"));
    }

    @Test void staleSingleAndBatchSavesCannotRestoreSoldStockOrUndoSetstock() {
        var stale = limited(1);
        serverA.saveShopData(stale);
        assertTrue(serverA.consumeShopStockIfEnough("limited", 1));
        serverB.saveShopData(stale);
        serverB.saveAllShopData(Map.of(stale.getId(), stale));
        assertEquals(0, serverA.getShopStock("limited"));
        assertTrue(serverA.setShopStock("limited", 12));
        serverB.saveAllShopData(Map.of(stale.getId(), stale));
        assertEquals(12, serverB.getShopStock("limited"));
        assertTrue(serverA.consumeShopStockIfEnough("limited", 4));
        assertTrue(serverA.addShopStockDelta("limited", 4));
        assertEquals(12, serverB.getShopStock("limited"));
        assertFalse(serverA.consumeShopStockIfEnough("limited", -1));
        assertFalse(serverA.setShopStock("limited", -1));
    }

    @Test void oldUnlimitedSentinelCanInitializeLimitedStockOnce() {
        serverA.saveShopData(new ShopItem("limited", Material.DIAMOND, 10));
        serverA.saveShopData(limited(9));
        assertEquals(9, serverA.getShopStock("limited"));
        serverB.saveShopData(limited(50));
        assertEquals(9, serverA.getShopStock("limited"));
    }

    private ShopItem limited(int stock) {
        var item = new ShopItem("limited", Material.DIAMOND, 10);
        item.setStockMode(ShopItem.StockMode.LIMITED);
        item.setAvailableStock(stock);
        return item;
    }

    private void execute(String statement) throws Exception {
        try (var st = sql.createStatement()) { st.executeUpdate(statement); }
    }
}
