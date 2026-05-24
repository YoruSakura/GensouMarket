package net.scarletphantasy.gensouMarket.upgrade.meta;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * SQL (MySQL / SQLite) 实现的元数据存储，使用 gensoumarket_meta 表。
 */
public class SqlMetaStore implements MetaStore {

    private static final Logger LOGGER = Logger.getLogger("GensouMarket");
    private final DataSource dataSource;
    private final boolean isMysql;

    /**
     * @param dataSource HikariDataSource 或 SQLite Connection wrapper
     * @param isMysql    true=MySQL, false=SQLite
     */
    public SqlMetaStore(DataSource dataSource, boolean isMysql) {
        this.dataSource = dataSource;
        this.isMysql = isMysql;
    }

    @Override
    public void initialize() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            if (isMysql) {
                stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS gensoumarket_meta (" +
                    "meta_key VARCHAR(64) PRIMARY KEY," +
                    "meta_value TEXT NOT NULL," +
                    "updated_at BIGINT NOT NULL" +
                    ") DEFAULT CHARSET=utf8mb4"
                );
            } else {
                stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS gensoumarket_meta (" +
                    "meta_key TEXT PRIMARY KEY," +
                    "meta_value TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL)"
                );
            }
        }
    }

    @Override
    public String get(String key, String defaultValue) {
        String sql = "SELECT meta_value FROM gensoumarket_meta WHERE meta_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("meta_value");
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "[UpgradeMeta] 读取 meta key=" + key + " 失败", e);
        }
        return defaultValue;
    }

    @Override
    public void set(String key, String value) {
        try {
            setStrict(key, value);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[UpgradeMeta] 写入 meta key=" + key + " 失败", e);
        }
    }

    @Override
    public void setStrict(String key, String value) throws Exception {
        String sql;
        if (isMysql) {
            sql = "INSERT INTO gensoumarket_meta (meta_key, meta_value, updated_at) VALUES (?, ?, ?) " +
                  "ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value), updated_at = VALUES(updated_at)";
        } else {
            sql = "INSERT OR REPLACE INTO gensoumarket_meta (meta_key, meta_value, updated_at) VALUES (?, ?, ?)";
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
