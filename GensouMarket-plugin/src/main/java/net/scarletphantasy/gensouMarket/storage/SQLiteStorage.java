package net.scarletphantasy.gensouMarket.storage;

import net.scarletphantasy.gensouMarket.model.*;
import net.scarletphantasy.gensouMarket.util.ItemSerializer;
import org.bukkit.Material;

import java.io.File;
import java.sql.*;
import java.util.*;

public class SQLiteStorage implements StorageProvider {

    private final File dataFolder;
    private Connection connection;

    public SQLiteStorage(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    @Override
    public void initialize() throws Exception {
        File dbFile = new File(dataFolder, "data.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        connection.setAutoCommit(true);
        createTables();
        migrateShopToRecycle();
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS market_listings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "seller_uuid VARCHAR(36) NOT NULL," +
                "seller_name VARCHAR(16) NOT NULL," +
                "item_data TEXT NOT NULL," +
                "price DOUBLE NOT NULL," +
                "list_time BIGINT NOT NULL," +
                "expire_time BIGINT NOT NULL," +
                "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'," +
                "buyer_uuid VARCHAR(36)," +
                "buyer_name VARCHAR(16))"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS auctions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "seller_uuid VARCHAR(36) NOT NULL," +
                "seller_name VARCHAR(16) NOT NULL," +
                "item_data TEXT NOT NULL," +
                "starting_price DOUBLE NOT NULL," +
                "current_price DOUBLE NOT NULL," +
                "highest_bidder_uuid VARCHAR(36)," +
                "highest_bidder_name VARCHAR(16)," +
                "start_time BIGINT NOT NULL," +
                "end_time BIGINT NOT NULL," +
                "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE')"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS shop_data (" +
                "item_id VARCHAR(64) PRIMARY KEY," +
                "material VARCHAR(64) NOT NULL," +
                "base_buy_price DOUBLE NOT NULL," +
                "base_sell_price DOUBLE NOT NULL DEFAULT 0," +
                "total_bought INTEGER NOT NULL DEFAULT 0," +
                "total_sold INTEGER NOT NULL DEFAULT 0," +
                "buy_multiplier DOUBLE NOT NULL DEFAULT 1.0," +
                "sell_multiplier DOUBLE NOT NULL DEFAULT 1.0," +
                "last_update BIGINT NOT NULL)"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS recycle_data (" +
                "item_id VARCHAR(64) PRIMARY KEY," +
                "material VARCHAR(64) NOT NULL," +
                "base_recycle_price DOUBLE NOT NULL," +
                "total_recycled INTEGER NOT NULL DEFAULT 0," +
                "recycle_multiplier DOUBLE NOT NULL DEFAULT 1.0," +
                "last_update BIGINT NOT NULL)"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_mail (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "item_data TEXT," +
                "money DOUBLE NOT NULL DEFAULT 0," +
                "message TEXT," +
                "timestamp BIGINT NOT NULL," +
                "claimed BOOLEAN NOT NULL DEFAULT 0)"
            );
        }
    }

    @Override
    public void shutdown() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}
    }

    // ---- Market Listings ----

    @Override
    public int saveListing(MarketListing listing) {
        String sql = "INSERT INTO market_listings (seller_uuid, seller_name, item_data, price, list_time, expire_time, status) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, listing.getSellerUuid().toString());
            ps.setString(2, listing.getSellerName());
            ps.setString(3, listing.getItemData());
            ps.setDouble(4, listing.getPrice());
            ps.setLong(5, listing.getListTime());
            ps.setLong(6, listing.getExpireTime());
            ps.setString(7, listing.getStatus().name());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    listing.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    @Override
    public void updateListing(MarketListing listing) {
        String sql = "UPDATE market_listings SET status=?, buyer_uuid=?, buyer_name=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, listing.getStatus().name());
            ps.setString(2, listing.getBuyerUuid() != null ? listing.getBuyerUuid().toString() : null);
            ps.setString(3, listing.getBuyerName());
            ps.setInt(4, listing.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public MarketListing getListing(int id) {
        String sql = "SELECT * FROM market_listings WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapListing(rs);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public List<MarketListing> getActiveListings() {
        List<MarketListing> list = new ArrayList<>();
        String sql = "SELECT * FROM market_listings WHERE status='ACTIVE' ORDER BY list_time DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(mapListing(rs));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    @Override
    public List<MarketListing> getPlayerListings(UUID playerUuid) {
        List<MarketListing> list = new ArrayList<>();
        String sql = "SELECT * FROM market_listings WHERE seller_uuid=? AND status='ACTIVE' ORDER BY list_time DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapListing(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    private MarketListing mapListing(ResultSet rs) throws SQLException {
        MarketListing l = new MarketListing();
        l.setId(rs.getInt("id"));
        l.setSellerUuid(UUID.fromString(rs.getString("seller_uuid")));
        l.setSellerName(rs.getString("seller_name"));
        l.setItemData(rs.getString("item_data"));
        l.setItemStack(ItemSerializer.deserialize(rs.getString("item_data")));
        l.setPrice(rs.getDouble("price"));
        l.setListTime(rs.getLong("list_time"));
        l.setExpireTime(rs.getLong("expire_time"));
        l.setStatus(MarketListing.Status.valueOf(rs.getString("status")));
        String buyerUuid = rs.getString("buyer_uuid");
        if (buyerUuid != null) l.setBuyerUuid(UUID.fromString(buyerUuid));
        l.setBuyerName(rs.getString("buyer_name"));
        return l;
    }

    // ---- Auctions ----

    @Override
    public int saveAuction(Auction auction) {
        String sql = "INSERT INTO auctions (seller_uuid, seller_name, item_data, starting_price, current_price, highest_bidder_uuid, highest_bidder_name, start_time, end_time, status) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, auction.getSellerUuid().toString());
            ps.setString(2, auction.getSellerName());
            ps.setString(3, auction.getItemData());
            ps.setDouble(4, auction.getStartingPrice());
            ps.setDouble(5, auction.getCurrentPrice());
            ps.setString(6, auction.getHighestBidderUuid() != null ? auction.getHighestBidderUuid().toString() : null);
            ps.setString(7, auction.getHighestBidderName());
            ps.setLong(8, auction.getStartTime());
            ps.setLong(9, auction.getEndTime());
            ps.setString(10, auction.getStatus().name());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    auction.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    @Override
    public void updateAuction(Auction auction) {
        String sql = "UPDATE auctions SET current_price=?, highest_bidder_uuid=?, highest_bidder_name=?, status=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, auction.getCurrentPrice());
            ps.setString(2, auction.getHighestBidderUuid() != null ? auction.getHighestBidderUuid().toString() : null);
            ps.setString(3, auction.getHighestBidderName());
            ps.setString(4, auction.getStatus().name());
            ps.setInt(5, auction.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Auction getAuction(int id) {
        String sql = "SELECT * FROM auctions WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapAuction(rs);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public List<Auction> getActiveAuctions() {
        List<Auction> list = new ArrayList<>();
        String sql = "SELECT * FROM auctions WHERE status='ACTIVE' ORDER BY end_time ASC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(mapAuction(rs));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    private Auction mapAuction(ResultSet rs) throws SQLException {
        Auction a = new Auction();
        a.setId(rs.getInt("id"));
        a.setSellerUuid(UUID.fromString(rs.getString("seller_uuid")));
        a.setSellerName(rs.getString("seller_name"));
        a.setItemData(rs.getString("item_data"));
        a.setItemStack(ItemSerializer.deserialize(rs.getString("item_data")));
        a.setStartingPrice(rs.getDouble("starting_price"));
        a.setCurrentPrice(rs.getDouble("current_price"));
        String bidderUuid = rs.getString("highest_bidder_uuid");
        if (bidderUuid != null) a.setHighestBidderUuid(UUID.fromString(bidderUuid));
        a.setHighestBidderName(rs.getString("highest_bidder_name"));
        a.setStartTime(rs.getLong("start_time"));
        a.setEndTime(rs.getLong("end_time"));
        a.setStatus(Auction.Status.valueOf(rs.getString("status")));
        return a;
    }

    private void migrateShopToRecycle() {
        try {
            // 检查 recycle_data 是否为空，且 shop_data 是否有 sell 数据可迁移
            boolean recycleEmpty;
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM recycle_data")) {
                rs.next();
                recycleEmpty = rs.getInt(1) == 0;
            }
            if (!recycleEmpty) return;

            int migrated = 0;
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM shop_data WHERE base_sell_price > 0")) {
                while (rs.next()) {
                    String itemId = rs.getString("item_id");
                    String material = rs.getString("material");
                    double baseSellPrice = rs.getDouble("base_sell_price");
                    int totalSold = rs.getInt("total_sold");
                    double sellMultiplier = rs.getDouble("sell_multiplier");
                    long lastUpdate = rs.getLong("last_update");

                    String insertSql = "INSERT OR IGNORE INTO recycle_data (item_id, material, base_recycle_price, total_recycled, recycle_multiplier, last_update) VALUES (?,?,?,?,?,?)";
                    try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                        ps.setString(1, itemId);
                        ps.setString(2, material);
                        ps.setDouble(3, baseSellPrice);
                        ps.setInt(4, totalSold);
                        ps.setDouble(5, sellMultiplier);
                        ps.setLong(6, lastUpdate);
                        ps.executeUpdate();
                        migrated++;
                    }
                }
            }
            if (migrated > 0) {
                System.out.println("[GensouMarket] 已从 shop_data 迁移 " + migrated + " 个物品到 recycle_data");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ---- Shop Data ----

    @Override
    public void saveShopData(ShopItem item) {
        String sql = "INSERT OR REPLACE INTO shop_data (item_id, material, base_buy_price, base_sell_price, total_bought, total_sold, buy_multiplier, sell_multiplier, last_update) VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item.getId());
            ps.setString(2, item.getMaterial().name());
            ps.setDouble(3, item.getBaseBuyPrice());
            ps.setDouble(4, 0);
            ps.setInt(5, item.getTotalBought());
            ps.setInt(6, 0);
            ps.setDouble(7, 1.0);
            ps.setDouble(8, 1.0);
            ps.setLong(9, item.getLastUpdate());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void saveAllShopData(Map<String, ShopItem> items) {
        for (ShopItem item : items.values()) {
            saveShopData(item);
        }
    }

    @Override
    public Map<String, ShopItem> loadShopData() {
        Map<String, ShopItem> map = new LinkedHashMap<>();
        String sql = "SELECT item_id, material, base_buy_price, total_bought, last_update FROM shop_data";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("item_id");
                Material mat = Material.matchMaterial(rs.getString("material"));
                if (mat == null) continue;
                ShopItem item = new ShopItem(id, mat, rs.getDouble("base_buy_price"));
                item.setTotalBought(rs.getInt("total_bought"));
                item.setLastUpdate(rs.getLong("last_update"));
                map.put(id, item);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return map;
    }

    // ---- Recycle Data ----

    @Override
    public void saveRecycleData(RecycleItem item) {
        String sql = "INSERT OR REPLACE INTO recycle_data (item_id, material, base_recycle_price, total_recycled, recycle_multiplier, last_update) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item.getId());
            ps.setString(2, item.getMaterial().name());
            ps.setDouble(3, item.getBaseRecyclePrice());
            ps.setInt(4, item.getTotalRecycled());
            ps.setDouble(5, item.getRecycleMultiplier());
            ps.setLong(6, item.getLastUpdate());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void saveAllRecycleData(Map<String, RecycleItem> items) {
        for (RecycleItem item : items.values()) {
            saveRecycleData(item);
        }
    }

    @Override
    public Map<String, RecycleItem> loadRecycleData() {
        Map<String, RecycleItem> map = new LinkedHashMap<>();
        String sql = "SELECT * FROM recycle_data";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("item_id");
                Material mat = Material.matchMaterial(rs.getString("material"));
                if (mat == null) continue;
                RecycleItem item = new RecycleItem(id, mat, rs.getDouble("base_recycle_price"));
                item.setTotalRecycled(rs.getInt("total_recycled"));
                item.setRecycleMultiplier(rs.getDouble("recycle_multiplier"));
                item.setLastUpdate(rs.getLong("last_update"));
                map.put(id, item);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return map;
    }

    // ---- Mail ----

    @Override
    public int saveMail(MailEntry entry) {
        String sql = "INSERT INTO player_mail (player_uuid, item_data, money, message, timestamp, claimed) VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entry.getPlayerUuid().toString());
            ps.setString(2, entry.getItemData());
            ps.setDouble(3, entry.getMoney());
            ps.setString(4, entry.getMessage());
            ps.setLong(5, entry.getTimestamp());
            ps.setBoolean(6, entry.isClaimed());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    entry.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    @Override
    public List<MailEntry> getPlayerMail(UUID playerUuid) {
        List<MailEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM player_mail WHERE player_uuid=? AND claimed=0 ORDER BY timestamp DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapMail(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    @Override
    public void deleteMail(int id) {
        String sql = "UPDATE player_mail SET claimed=1 WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private MailEntry mapMail(ResultSet rs) throws SQLException {
        MailEntry m = new MailEntry();
        m.setId(rs.getInt("id"));
        m.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        String itemData = rs.getString("item_data");
        m.setItemData(itemData);
        if (itemData != null) m.setItemStack(ItemSerializer.deserialize(itemData));
        m.setMoney(rs.getDouble("money"));
        m.setMessage(rs.getString("message"));
        m.setTimestamp(rs.getLong("timestamp"));
        m.setClaimed(rs.getBoolean("claimed"));
        return m;
    }
}
