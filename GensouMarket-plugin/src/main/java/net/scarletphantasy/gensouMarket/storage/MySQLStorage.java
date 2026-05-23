package net.scarletphantasy.gensouMarket.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.scarletphantasy.gensouMarket.model.*;
import net.scarletphantasy.gensouMarket.util.ItemSerializer;
import org.bukkit.Material;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MySQLStorage implements StorageProvider {

    private static final Logger LOGGER = Logger.getLogger("GensouMarket");
    private static final long LOG_THROTTLE_MS = 30_000; // 30秒内相同错误只打印一次

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private HikariDataSource dataSource;
    private final AtomicLong lastErrorLog = new AtomicLong(0);

    public MySQLStorage(String host, int port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void logThrottled(String message, Exception e) {
        long now = System.currentTimeMillis();
        long last = lastErrorLog.get();
        if (now - last > LOG_THROTTLE_MS && lastErrorLog.compareAndSet(last, now)) {
            LOGGER.log(Level.WARNING, message, e);
        }
    }

    @Override
    public void initialize() throws Exception {
        dataSource = new HikariDataSource(createHikariConfig());

        try (Connection conn = getConnection()) {
            createTables(conn);
            migrateSchema(conn);
            migrateShopToRecycle(conn);
        }
    }

    private HikariConfig createHikariConfig() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(5000);
        cfg.setValidationTimeout(3000);
        cfg.setIdleTimeout(300000);
        cfg.setMaxLifetime(1800000);
        cfg.setKeepaliveTime(60000);
        cfg.setPoolName("GensouMarket-MySQL");
        cfg.addDataSourceProperty("connectTimeout", "5000");
        cfg.addDataSourceProperty("socketTimeout", "5000");
        cfg.addDataSourceProperty("tcpKeepAlive", "true");
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        return cfg;
    }

    private void createTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS market_listings (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "seller_uuid VARCHAR(36) NOT NULL," +
                "seller_name VARCHAR(16) NOT NULL," +
                "item_data MEDIUMTEXT NOT NULL," +
                "price DOUBLE NOT NULL," +
                "list_time BIGINT NOT NULL," +
                "expire_time BIGINT NOT NULL," +
                "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'," +
                "buyer_uuid VARCHAR(36)," +
                "buyer_name VARCHAR(16)) DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS auctions (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "seller_uuid VARCHAR(36) NOT NULL," +
                "seller_name VARCHAR(16) NOT NULL," +
                "item_data MEDIUMTEXT NOT NULL," +
                "starting_price DOUBLE NOT NULL," +
                "current_price DOUBLE NOT NULL," +
                "highest_bidder_uuid VARCHAR(36)," +
                "highest_bidder_name VARCHAR(16)," +
                "start_time BIGINT NOT NULL," +
                "end_time BIGINT NOT NULL," +
                "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE') DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS shop_data (" +
                "item_id VARCHAR(64) PRIMARY KEY," +
                "material VARCHAR(64) NOT NULL," +
                "base_buy_price DOUBLE NOT NULL," +
                "base_sell_price DOUBLE NOT NULL DEFAULT 0," +
                "total_bought INT NOT NULL DEFAULT 0," +
                "total_sold INT NOT NULL DEFAULT 0," +
                "buy_multiplier DOUBLE NOT NULL DEFAULT 1.0," +
                "sell_multiplier DOUBLE NOT NULL DEFAULT 1.0," +
                "last_update BIGINT NOT NULL," +
                "mode VARCHAR(16) NOT NULL DEFAULT 'FIXED'," +
                "stock_mode VARCHAR(16) NOT NULL DEFAULT 'UNLIMITED'," +
                "available_stock INT NOT NULL DEFAULT -1," +
                "recycle_source_id VARCHAR(64)) DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS recycle_data (" +
                "item_id VARCHAR(64) PRIMARY KEY," +
                "material VARCHAR(64) NOT NULL," +
                "base_recycle_price DOUBLE NOT NULL," +
                "total_recycled INT NOT NULL DEFAULT 0," +
                "recycle_multiplier DOUBLE NOT NULL DEFAULT 1.0," +
                "last_update BIGINT NOT NULL," +
                "recycled_stock INT NOT NULL DEFAULT 0) DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_mail (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "item_data MEDIUMTEXT," +
                "money DOUBLE NOT NULL DEFAULT 0," +
                "message TEXT," +
                "timestamp BIGINT NOT NULL," +
                "claimed BOOLEAN NOT NULL DEFAULT FALSE) DEFAULT CHARSET=utf8mb4"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS price_pressure (" +
                "item_id VARCHAR(64) NOT NULL," +
                "bucket_start BIGINT NOT NULL," +
                "amount INT NOT NULL DEFAULT 0," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (item_id, bucket_start)) DEFAULT CHARSET=utf8mb4"
            );
        }
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    // ---- Market Listings ----

    @Override
    public int saveListing(MarketListing listing) {
        String sql = "INSERT INTO market_listings (seller_uuid, seller_name, item_data, price, list_time, expire_time, status) VALUES (?,?,?,?,?,?,?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
            logThrottled("[MySQL] 保存上架物品失败", e);
        }
        return -1;
    }

    @Override
    public void updateListing(MarketListing listing) {
        String sql = "UPDATE market_listings SET status=?, buyer_uuid=?, buyer_name=? WHERE id=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, listing.getStatus().name());
            ps.setString(2, listing.getBuyerUuid() != null ? listing.getBuyerUuid().toString() : null);
            ps.setString(3, listing.getBuyerName());
            ps.setInt(4, listing.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            logThrottled("[MySQL] 更新上架物品失败", e);
        }
    }

    @Override
    public MarketListing getListing(int id) {
        String sql = "SELECT * FROM market_listings WHERE id=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapListing(rs);
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] 获取上架物品失败", e);
        }
        return null;
    }

    @Override
    public List<MarketListing> getActiveListings() {
        List<MarketListing> list = new ArrayList<>();
        String sql = "SELECT * FROM market_listings WHERE status='ACTIVE' ORDER BY list_time DESC";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(mapListing(rs));
        } catch (SQLException e) {
            logThrottled("[MySQL] 获取活跃上架列表失败", e);
        }
        return list;
    }

    @Override
    public List<MarketListing> getPlayerListings(UUID playerUuid) {
        List<MarketListing> list = new ArrayList<>();
        String sql = "SELECT * FROM market_listings WHERE seller_uuid=? AND status='ACTIVE' ORDER BY list_time DESC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapListing(rs));
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] 获取玩家上架列表失败", e);
        }
        return list;
    }

    @Override
    public boolean markListingSoldIfActive(int listingId, UUID buyerUuid, String buyerName) {
        String sql = "UPDATE market_listings SET status='SOLD', buyer_uuid=?, buyer_name=? WHERE id=? AND status='ACTIVE'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, buyerUuid.toString());
            ps.setString(2, buyerName);
            ps.setInt(3, listingId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logThrottled("[MySQL] 原子标记上架物品售出失败", e);
        }
        return false;
    }

    @Override
    public boolean markListingExpiredIfActive(int listingId) {
        String sql = "UPDATE market_listings SET status='EXPIRED' WHERE id=? AND status='ACTIVE'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, listingId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logThrottled("[MySQL] 原子标记上架物品过期失败", e);
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
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
            logThrottled("[MySQL] 保存拍卖失败", e);
        }
        return -1;
    }

    @Override
    public void updateAuction(Auction auction) {
        String sql = "UPDATE auctions SET current_price=?, highest_bidder_uuid=?, highest_bidder_name=?, status=? WHERE id=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, auction.getCurrentPrice());
            ps.setString(2, auction.getHighestBidderUuid() != null ? auction.getHighestBidderUuid().toString() : null);
            ps.setString(3, auction.getHighestBidderName());
            ps.setString(4, auction.getStatus().name());
            ps.setInt(5, auction.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            logThrottled("[MySQL] 更新拍卖失败", e);
        }
    }

    @Override
    public Auction getAuction(int id) {
        String sql = "SELECT * FROM auctions WHERE id=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapAuction(rs);
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] 获取拍卖失败", e);
        }
        return null;
    }

    @Override
    public List<Auction> getActiveAuctions() {
        List<Auction> list = new ArrayList<>();
        String sql = "SELECT * FROM auctions WHERE status='ACTIVE' ORDER BY end_time ASC";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) list.add(mapAuction(rs));
        } catch (SQLException e) {
            logThrottled("[MySQL] 获取活跃拍卖列表失败", e);
        }
        return list;
    }

    @Override
    public boolean updateAuctionBidIfMatch(int auctionId, double expectedCurrentPrice,
                                            double newPrice, UUID newBidderUuid, String newBidderName) {
        String sql = "UPDATE auctions SET current_price=?, highest_bidder_uuid=?, highest_bidder_name=? " +
                     "WHERE id=? AND status='ACTIVE' AND current_price=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, newPrice);
            ps.setString(2, newBidderUuid.toString());
            ps.setString(3, newBidderName);
            ps.setInt(4, auctionId);
            ps.setDouble(5, expectedCurrentPrice);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logThrottled("[MySQL] 原子更新拍卖出价失败", e);
        }
        return false;
    }

    @Override
    public boolean markAuctionEndedIfActive(int auctionId) {
        String sql = "UPDATE auctions SET status='ENDED' WHERE id=? AND status='ACTIVE'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, auctionId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logThrottled("[MySQL] 原子标记拍卖结束失败", e);
        }
        return false;
    }

    @Override
    public boolean markAuctionCancelledIfActive(int auctionId) {
        String sql = "UPDATE auctions SET status='CANCELLED' WHERE id=? AND status='ACTIVE'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, auctionId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logThrottled("[MySQL] 原子标记拍卖取消失败", e);
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

    private void migrateShopToRecycle(Connection conn) {
        try {
            boolean recycleEmpty;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM recycle_data")) {
                rs.next();
                recycleEmpty = rs.getInt(1) == 0;
            }
            if (!recycleEmpty) return;

            String sql = "INSERT IGNORE INTO recycle_data (item_id, material, base_recycle_price, total_recycled, recycle_multiplier, last_update) " +
                    "SELECT item_id, material, base_sell_price, total_sold, sell_multiplier, last_update " +
                    "FROM shop_data WHERE base_sell_price > 0";
            try (Statement stmt = conn.createStatement()) {
                int migrated = stmt.executeUpdate(sql);
                if (migrated > 0) {
                    LOGGER.info("[GensouMarket] 已从 shop_data 迁移 " + migrated + " 个物品到 recycle_data");
                }
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] shop_data 迁移失败", e);
        }
    }

    /**
     * 对旧版数据库补齐 task-05 新增列。使用 information_schema 检测以兼容各版本 MySQL。
     */
    private void migrateSchema(Connection conn) {
        addColumnIfMissing(conn, "shop_data", "mode", "VARCHAR(16) NOT NULL DEFAULT 'FIXED'");
        addColumnIfMissing(conn, "shop_data", "stock_mode", "VARCHAR(16) NOT NULL DEFAULT 'UNLIMITED'");
        addColumnIfMissing(conn, "shop_data", "available_stock", "INT NOT NULL DEFAULT -1");
        addColumnIfMissing(conn, "shop_data", "recycle_source_id", "VARCHAR(64)");
        addColumnIfMissing(conn, "recycle_data", "recycled_stock", "INT NOT NULL DEFAULT 0");
    }

    private void addColumnIfMissing(Connection conn, String table, String column, String definition) {
        String check = "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?";
        try (PreparedStatement ps = conn.prepareStatement(check)) {
            ps.setString(1, database);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) return;
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] 检查 " + table + "." + column + " 失败", e);
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            LOGGER.info("[MySQL] 已补齐列 " + table + "." + column);
        } catch (SQLException e) {
            logThrottled("[MySQL] 添加 " + table + "." + column + " 失败", e);
        }
    }

    // ---- Shop Data ----

    @Override
    public void saveShopData(ShopItem item) {
        String sql = "INSERT INTO shop_data (item_id, material, base_buy_price, base_sell_price, total_bought, total_sold, buy_multiplier, sell_multiplier, last_update, mode, stock_mode, available_stock, recycle_source_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
                "total_bought=VALUES(total_bought), last_update=VALUES(last_update), " +
                "mode=VALUES(mode), stock_mode=VALUES(stock_mode), available_stock=VALUES(available_stock), " +
                "recycle_source_id=VALUES(recycle_source_id), sell_multiplier=VALUES(sell_multiplier), " +
                "base_buy_price=VALUES(base_buy_price)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindShopItem(ps, item);
            ps.executeUpdate();
        } catch (SQLException e) {
            logThrottled("[MySQL] 保存商店数据失败", e);
        }
    }

    @Override
    public void saveAllShopData(Map<String, ShopItem> items) {
        String sql = "INSERT INTO shop_data (item_id, material, base_buy_price, base_sell_price, total_bought, total_sold, buy_multiplier, sell_multiplier, last_update, mode, stock_mode, available_stock, recycle_source_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
                "total_bought=VALUES(total_bought), last_update=VALUES(last_update), " +
                "mode=VALUES(mode), stock_mode=VALUES(stock_mode), available_stock=VALUES(available_stock), " +
                "recycle_source_id=VALUES(recycle_source_id), sell_multiplier=VALUES(sell_multiplier), " +
                "base_buy_price=VALUES(base_buy_price)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ShopItem item : items.values()) {
                bindShopItem(ps, item);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            logThrottled("[MySQL] 批量保存商店数据失败", e);
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
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
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
            logThrottled("[MySQL] 加载商店数据失败", e);
        }
        return map;
    }

    // ---- Recycle Data ----

    @Override
    public void saveRecycleData(RecycleItem item) {
        String sql = "INSERT INTO recycle_data (item_id, material, base_recycle_price, total_recycled, recycle_multiplier, last_update, recycled_stock) " +
                "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
                "total_recycled=VALUES(total_recycled), recycle_multiplier=VALUES(recycle_multiplier), last_update=VALUES(last_update), " +
                "recycled_stock=VALUES(recycled_stock), base_recycle_price=VALUES(base_recycle_price)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, item.getId());
            ps.setString(2, item.getMaterial().name());
            ps.setDouble(3, item.getBaseRecyclePrice());
            ps.setInt(4, item.getTotalRecycled());
            ps.setDouble(5, 1.0); // 废弃的 recycle_multiplier
            ps.setLong(6, item.getLastUpdate());
            ps.setInt(7, item.getRecycledStock());
            ps.executeUpdate();
        } catch (SQLException e) {
            logThrottled("[MySQL] 保存回收数据失败", e);
        }
    }

    @Override
    public void saveAllRecycleData(Map<String, RecycleItem> items) {
        String sql = "INSERT INTO recycle_data (item_id, material, base_recycle_price, total_recycled, recycle_multiplier, last_update, recycled_stock) " +
                "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE " +
                "total_recycled=VALUES(total_recycled), recycle_multiplier=VALUES(recycle_multiplier), last_update=VALUES(last_update), " +
                "recycled_stock=VALUES(recycled_stock), base_recycle_price=VALUES(base_recycle_price)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (RecycleItem item : items.values()) {
                ps.setString(1, item.getId());
                ps.setString(2, item.getMaterial().name());
                ps.setDouble(3, item.getBaseRecyclePrice());
                ps.setInt(4, item.getTotalRecycled());
                ps.setDouble(5, 1.0); // 废弃的 recycle_multiplier
                ps.setLong(6, item.getLastUpdate());
                ps.setInt(7, item.getRecycledStock());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            logThrottled("[MySQL] 批量保存回收数据失败", e);
        }
    }

    @Override
    public Map<String, RecycleItem> loadRecycleData() {
        Map<String, RecycleItem> map = new LinkedHashMap<>();
        String sql = "SELECT * FROM recycle_data";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("item_id");
                Material mat = Material.matchMaterial(rs.getString("material"));
                if (mat == null) continue;
                RecycleItem item = new RecycleItem(id, mat, rs.getDouble("base_recycle_price"));
                item.setTotalRecycled(rs.getInt("total_recycled"));
                // recycle_multiplier v1.1.1 已弃用
                item.setLastUpdate(rs.getLong("last_update"));
                try { item.setRecycledStock(rs.getInt("recycled_stock")); } catch (SQLException ignored) {}
                map.put(id, item);
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] 加载回收数据失败", e);
        }
        return map;
    }

    // ---- Pressure Data (v1.1.1) ----

    @Override
    public void savePressureBucket(PressureBucket bucket) {
        String sql = "INSERT INTO price_pressure (item_id, bucket_start, amount, updated_at) " +
                "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE amount=VALUES(amount), updated_at=VALUES(updated_at)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bucket.itemId());
            ps.setLong(2, bucket.bucketStart());
            ps.setInt(3, bucket.amount());
            ps.setLong(4, bucket.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            logThrottled("[MySQL] 保存压力桶失败", e);
        }
    }

    @Override
    public void saveAllPressureBuckets(String itemId, List<PressureBucket> buckets) {
        // 先删除该物品的所有旧桶，再写入新桶
        String deleteSql = "DELETE FROM price_pressure WHERE item_id=?";
        String insertSql = "INSERT INTO price_pressure (item_id, bucket_start, amount, updated_at) VALUES (?,?,?,?)";
        try (Connection conn = getConnection()) {
            try (PreparedStatement dps = conn.prepareStatement(deleteSql)) {
                dps.setString(1, itemId);
                dps.executeUpdate();
            }
            if (!buckets.isEmpty()) {
                try (PreparedStatement ips = conn.prepareStatement(insertSql)) {
                    for (PressureBucket b : buckets) {
                        ips.setString(1, b.itemId());
                        ips.setLong(2, b.bucketStart());
                        ips.setInt(3, b.amount());
                        ips.setLong(4, b.updatedAt());
                        ips.addBatch();
                    }
                    ips.executeBatch();
                }
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] 批量保存压力桶失败", e);
        }
    }

    @Override
    public void addPressureAmount(String itemId, long bucketStart, int amountDelta, long now) {
        String sql = "INSERT INTO price_pressure (item_id, bucket_start, amount, updated_at) " +
                "VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount), updated_at = VALUES(updated_at)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            ps.setLong(2, bucketStart);
            ps.setInt(3, amountDelta);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logThrottled("[MySQL] 原子增加压力失败", e);
        }
    }

    @Override
    public List<PressureBucket> loadPressureBuckets(String itemId) {
        List<PressureBucket> list = new ArrayList<>();
        String sql = "SELECT * FROM price_pressure WHERE item_id=? ORDER BY bucket_start ASC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapPressureBucket(rs));
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] 加载压力桶失败", e);
        }
        return list;
    }

    @Override
    public Map<String, List<PressureBucket>> loadAllPressureBuckets() {
        Map<String, List<PressureBucket>> map = new LinkedHashMap<>();
        String sql = "SELECT * FROM price_pressure ORDER BY item_id, bucket_start ASC";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                PressureBucket b = mapPressureBucket(rs);
                map.computeIfAbsent(b.itemId(), k -> new ArrayList<>()).add(b);
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] 加载所有压力桶失败", e);
        }
        return map;
    }

    @Override
    public void deleteExpiredPressureBuckets(String itemId, long cutoffTime) {
        String sql = "DELETE FROM price_pressure WHERE item_id=? AND bucket_start<?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            ps.setLong(2, cutoffTime);
            ps.executeUpdate();
        } catch (SQLException e) {
            logThrottled("[MySQL] 删除过期压力桶失败", e);
        }
    }

    private PressureBucket mapPressureBucket(ResultSet rs) throws SQLException {
        return new PressureBucket(
                rs.getString("item_id"),
                rs.getLong("bucket_start"),
                rs.getInt("amount"),
                rs.getLong("updated_at")
        );
    }

    // ---- Mail ----

    @Override
    public int saveMail(MailEntry entry) {
        String sql = "INSERT INTO player_mail (player_uuid, item_data, money, message, timestamp, claimed) VALUES (?,?,?,?,?,?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
            logThrottled("[MySQL] 保存邮件失败", e);
        }
        return -1;
    }

    @Override
    public List<MailEntry> getPlayerMail(UUID playerUuid) {
        List<MailEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM player_mail WHERE player_uuid=? AND claimed=0 ORDER BY timestamp DESC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapMail(rs));
            }
        } catch (SQLException e) {
            logThrottled("[MySQL] 获取玩家邮件失败", e);
        }
        return list;
    }

    @Override
    public boolean claimMail(int id) {
        String sql = "UPDATE player_mail SET claimed=1 WHERE id=? AND claimed=0";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logThrottled("[MySQL] 标记邮件领取失败", e);
        }
        return false;
    }

    @Override
    public boolean unclaimMail(int id) {
        String sql = "UPDATE player_mail SET claimed=0 WHERE id=? AND claimed=1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logThrottled("[MySQL] 回滚邮件领取状态失败", e);
        }
        return false;
    }

    @Override
    public void deleteMail(int id) {
        String sql = "UPDATE player_mail SET claimed=1 WHERE id=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            logThrottled("[MySQL] 删除邮件失败", e);
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
