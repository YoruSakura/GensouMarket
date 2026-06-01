package net.scarletphantasy.gensouMarket.storage;

import net.scarletphantasy.gensouMarket.config.ConfigManager;

import java.io.File;

public final class StorageFactory {

    private StorageFactory() {}

    public static StorageProvider create(ConfigManager config, File dataFolder) {
        String type = config.getStorageType().toLowerCase();
        return switch (type) {
            case "mysql" -> new MySQLStorage(
                    config.getMysqlHost(),
                    config.getMysqlPort(),
                    config.getMysqlDatabase(),
                    config.getMysqlUsername(),
                    config.getMysqlPassword()
            );
            case "sqlite" -> new SQLiteStorage(dataFolder, config.getSqlitePath());
            default -> throw new IllegalArgumentException("不支持的存储方式: " + type + "，仅支持 sqlite 或 mysql");
        };
    }
}
