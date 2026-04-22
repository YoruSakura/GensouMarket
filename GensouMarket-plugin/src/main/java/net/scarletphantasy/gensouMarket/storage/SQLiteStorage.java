package net.scarletphantasy.gensouMarket.storage;

import net.scarletphantasy.gensouMarket.model.*;
import net.scarletphantasy.gensouMarket.util.ItemSerializer;
import org.bukkit.Material;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SQLiteStorage implements StorageProvider {

    private static final Logger LOGGER = Logger.getLogger("GensouMarket");
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
        migrateSchema();
        migrateShopToRecycle();
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS market_listings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "seller_uuid TEXT NOT NULL," +
                "seller_name TEXT NOT NULL," +
                "item_data TEXT NOT NULL," +
                "price REAL NOT NULL," +
                "list_time INTEGER NOT NULL," +
                "expire_time INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'ACTIVE'," +
                "buyer_uuid TEXT," +
                "buyer_name TEXT)"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS auctions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "seller_uuid TEXT NOT NULL," +
                "seller_name TEXT NOT NULL," +
                "item_data TEXT NOT NULL," +
                "starting_price REAL NOT NULL," +
                "current_price REAL NOT NULL," +
                "highest_bidder_uuid TEXT," +
                "highest_bidder_name TEXT," +
                "start_time INTEGER NOT NULL," +
                "end_time INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'ACTIVE')"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS shop_data (" +
                "item_id TEXT PRIMARY KEY," +
                "material TEXT NOT NULL," +
                "base_buy_price REAL NOT NULL," +
                "base_sell_price REAL NOT NULL DEFAULT 0," +
                "total_bought INTEGER NOT NULL DEFAULT 0," +
                "total_sold INTEGER NOT NULL DEFAULT 0," +
                "buy_multiplier REAL NOT NULL DEFAULT 1.0," +
                "sell_multiplier REAL NOT NULL DEFAULT 1.0," +
                "last_update INTEGER NOT NULL," +
                "mode TEXT NOT NULL DEFAULT 'FIXED'," +
                "stock_mode TEXT NOT NULL DEFAULT 'UNLIMITED'," +
                "available_stock INTEGER NOT NULL DEFAULT -1," +
                "recycle_source_id TEXT)"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS recycle_data (" +
                "item_id TEXT PRIMARY KEY," +
                "material TEXT NOT NULL," +
                "base_recycle_price REAL NOT NULL," +
                "total_recycled INTEGER NOT NULL DEFAULT 0," +
                "recycle_multiplier REAL NOT NULL DEFAULT 1.0," +
                "last_update INTEGER NOT NULL," +
                "recycled_stock INTEGER NOT NULL DEFAULT 0)"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_mail (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid TEXT NOT NULL," +
                "item_data TEXT," +
                "money REAL NOT NULL DEFAULT 0," +
                "message TEXT," +
                "timestamp INTEGER NOT NULL," +
                "claimed INTEGER NOT NULL DEFAULT 0)"
            );
        }
    }

    /**
     * 对旧版数据库补齐 task-05 新增列。SQLite 不支持 ADD COLUMN IF NOT EXISTS，用 try-catch 逐列检查。
     */
    private void migrateSchema() {
        addColumnIfMissing("shop_data", "mode", "TEXT NOT NULL DEFAULT 'FIXED'");
        addColumnIfMissing("shop_data", "stock_mode", "TEXT NOT NULL DEFAULT 'UNLIMITED'");
        addColumnIfMissing("shop_data", "available_stock", "INTEGER NOT NULL DEFAULT -1");
        addColumnIfMissing("shop_data", "recycle_source_id", "TEXT");
        addColumnIfMissing("recycle_data", "recycled_stock", "INTEGER NOT NULL DEFAULT 0");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return;
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 检查 " + table + "." + column + " 失败", e);
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            LOGGER.info("[SQLite] 已补齐列 " + table + "." + column);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 添加 " + table + "." + column + " 失败", e);
        }
    }

    @Override
    public void shutdown() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 关闭连接失败", e);
        }
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
            LOGGER.log(Level.WARNING, "[SQLite] 保存上架物品失败", e);
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
            LOGGER.log(Level.WARNING, "[SQLite] 更新上架物品失败", e);
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
            LOGGER.log(Level.WARNING, "[SQLite] 获取上架物品失败", e);
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
            LOGGER.log(Level.WARNING, "[SQLite] 获取活跃上架列表失败", e);
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
            LOGGER.log(Level.WARNING, "[SQLite] 获取玩家上架列表失败", e);
        }
        return list;
    }

    @Override
    public boolean markListingSoldIfActive(int listingId, UUID buyerUuid, String buyerName) {
        String sql = "UPDATE market_listings SET status='SOLD', buyer_uuid=?, buyer_name=? WHERE id=? AND status='ACTIVE'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, buyerUuid.toString());
            ps.setString(2, buyerName);
            ps.setInt(3, listingId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 原子标记上架物品售出失败", e);
        }
        return false;
    }

    @Override
    public boolean markListingExpiredIfActive(int listingId) {
        String sql = "UPDATE market_listings SET status='EXPIRED' WHERE id=? AND status='ACTIVE'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, listingId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 原子标记上架物品过期失败", e);
        }
        return false;
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
            LOGGER.log(Level.WARNING, "[SQLite] 保存拍卖失败", e);
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
            LOGGER.log(Level.WARNING, "[SQLite] 更新拍卖失败", e);
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
            LOGGER.log(Level.WARNING, "[SQLite] 获取拍卖失败", e);
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
            LOGGER.log(Level.WARNING, "[SQLite] 获取活跃拍卖列表失败", e);
        }
        return list;
    }

    @Override
    public boolean updateAuctionBidIfMatch(int auctionId, double expectedCurrentPrice,
                                            double newPrice, UUID newBidderUuid, String newBidderName) {
        String sql = "UPDATE auctions SET current_price=?, highest_bidder_uuid=?, highest_bidder_name=? " +
                     "WHERE id=? AND status='ACTIVE' AND current_price=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, newPrice);
            ps.setString(2, newBidderUuid.toString());
            ps.setString(3, newBidderName);
            ps.setInt(4, auctionId);
            ps.setDouble(5, expectedCurrentPrice);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 原子更新拍卖出价失败", e);
        }
        return false;
    }

    @Override
    public boolean markAuctionEndedIfActive(int auctionId) {
        String sql = "UPDATE auctions SET status='ENDED' WHERE id=? AND status='ACTIVE'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, auctionId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 原子标记拍卖结束失败", e);
        }
        return false;
    }

    @Override
    public boolean markAuctionCancelledIfActive(int auctionId) {
        String sql = "UPDATE auctions SET status='CANCELLED' WHERE id=? AND status='ACTIVE'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, auctionId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 原子标记拍卖取消失败", e);
        }
        return false;
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
            boolean recycleEmpty;
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM recycle_data")) {
                rs.next();
                recycleEmpty = rs.getInt(1) == 0;
            }
            if (!recycleEmpty) return;

            String sql = "INSERT OR IGNORE INTO recycle_data (item_id, material, base_recycle_price, total_recycled, recycle_multiplier, last_update) " +
                    "SELECT item_id, material, base_sell_price, total_sold, sell_multiplier, last_update " +
                    "FROM shop_data WHERE base_sell_price > 0";
            try (Statement stmt = connection.createStatement()) {
                int migrated = stmt.executeUpdate(sql);
                if (migrated > 0) {
                    LOGGER.info("[GensouMarket] 已从 shop_data 迁移 " + migrated + " 个物品到 recycle_data");
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] shop_data 迁移失败", e);
        }
    }

    // ---- Shop Data ----

    @Override
    public void saveShopData(ShopItem item) {
        String sql = "INSERT OR REPLACE INTO shop_data (item_id, material, base_buy_price, base_sell_price, total_bought, total_sold, buy_multiplier, sell_multiplier, last_update, mode, stock_mode, available_stock, recycle_source_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bindShopItem(ps, item);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 保存商店数据失败", e);
        }
    }

    @Override
    public void saveAllShopData(Map<String, ShopItem> items) {
        String sql = "INSERT OR REPLACE INTO shop_data (item_id, material, base_buy_price, base_sell_price, total_bought, total_sold, buy_multiplier, sell_multiplier, last_update, mode, stock_mode, available_stock, recycle_source_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (ShopItem item : items.values()) {
                bindShopItem(ps, item);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 批量保存商店数据失败", e);
        }
    }

    private void bindShopItem(PreparedStatement ps, ShopItem item) throws SQLException {
        ps.setString(1, item.getId());
        ps.setString(2, item.getMaterial().name());
        ps.setDouble(3, item.getBaseBuyPrice());
        ps.setDouble(4, 0);
        ps.setInt(5, item.getTotalBought());
        ps.setInt(6, 0);
        ps.setDouble(7, 1.0);
        ps.setDouble(8, item.getSellMultiplier());
        ps.setLong(9, item.getLastUpdate());
        ps.setString(10, item.getMode().name());
        ps.setString(11, item.getStockMode().name());
        ps.setInt(12, item.getAvailableStock());
        ps.setString(13, item.getRecycleSourceId());
    }

    @Override
    public Map<String, ShopItem> loadShopData() {
        Map<String, ShopItem> map = new LinkedHashMap<>();
        String sql = "SELECT item_id, material, base_buy_price, sell_multiplier, total_bought, last_update, mode, stock_mode, available_stock, recycle_source_id FROM shop_data";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("item_id");
                Material mat = Material.matchMaterial(rs.getString("material"));
                if (mat == null) continue;
                ShopItem item = new ShopItem(id, mat, rs.getDouble("base_buy_price"));
                item.setTotalBought(rs.getInt("total_bought"));
                item.setLastUpdate(rs.getLong("last_update"));
                String modeStr = rs.getString("mode");
                if (modeStr != null) {
                    try { item.setMode(ShopItem.Mode.valueOf(modeStr)); } catch (IllegalArgumentException ignored) {}
                }
                String stockModeStr = rs.getString("stock_mode");
                if (stockModeStr != null) {
                    try { item.setStockMode(ShopItem.StockMode.valueOf(stockModeStr)); } catch (IllegalArgumentException ignored) {}
                }
                item.setAvailableStock(rs.getInt("available_stock"));
                item.setRecycleSourceId(rs.getString("recycle_source_id"));
                item.setSellMultiplier(rs.getDouble("sell_multiplier"));
                map.put(id, item);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 加载商店数据失败", e);
        }
        return map;
    }

    // ---- Recycle Data ----

    @Override
    public void saveRecycleData(RecycleItem item) {
        String sql = "INSERT OR REPLACE INTO recycle_data (item_id, material, base_recycle_price, total_recycled, recycle_multiplier, last_update, recycled_stock) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item.getId());
            ps.setString(2, item.getMaterial().name());
            ps.setDouble(3, item.getBaseRecyclePrice());
            ps.setInt(4, item.getTotalRecycled());
            ps.setDouble(5, item.getRecycleMultiplier());
            ps.setLong(6, item.getLastUpdate());
            ps.setInt(7, item.getRecycledStock());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 保存回收数据失败", e);
        }
    }

    @Override
    public void saveAllRecycleData(Map<String, RecycleItem> items) {
        String sql = "INSERT OR REPLACE INTO recycle_data (item_id, material, base_recycle_price, total_recycled, recycle_multiplier, last_update, recycled_stock) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (RecycleItem item : items.values()) {
                ps.setString(1, item.getId());
                ps.setString(2, item.getMaterial().name());
                ps.setDouble(3, item.getBaseRecyclePrice());
                ps.setInt(4, item.getTotalRecycled());
                ps.setDouble(5, item.getRecycleMultiplier());
                ps.setLong(6, item.getLastUpdate());
                ps.setInt(7, item.getRecycledStock());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 批量保存回收数据失败", e);
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
                item.setRecycledStock(rs.getInt("recycled_stock"));
                map.put(id, item);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 加载回收数据失败", e);
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
            ps.setInt(6, entry.isClaimed() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    entry.setId(id);
                    return id;
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 保存邮件失败", e);
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
            LOGGER.log(Level.WARNING, "[SQLite] 获取玩家邮件失败", e);
        }
        return list;
    }

    @Override
    public boolean claimMail(int id) {
        String sql = "UPDATE player_mail SET claimed=1 WHERE id=? AND claimed=0";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 标记邮件领取失败", e);
        }
        return false;
    }

    @Override
    public boolean unclaimMail(int id) {
        String sql = "UPDATE player_mail SET claimed=0 WHERE id=? AND claimed=1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 回滚邮件领取状态失败", e);
        }
        return false;
    }

    @Override
    public void deleteMail(int id) {
        String sql = "UPDATE player_mail SET claimed=1 WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[SQLite] 删除邮件失败", e);
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
        m.setClaimed(rs.getInt("claimed") == 1);
        return m;
    }
}
