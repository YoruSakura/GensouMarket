package net.scarletphantasy.gensouMarket.upgrade.meta;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * YAML 文件实现的元数据存储，用于配置侧版本追踪 (upgrade-state.yml)。
 */
public class YamlMetaStore implements MetaStore {

    private static final Logger LOGGER = Logger.getLogger("GensouMarket");
    private final File file;
    private YamlConfiguration config;

    public YamlMetaStore(File dataFolder) {
        this.file = new File(dataFolder, "upgrade-state.yml");
    }

    @Override
    public void initialize() throws Exception {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            file.createNewFile();
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public String get(String key, String defaultValue) {
        return config.getString(key, defaultValue);
    }

    @Override
    public void set(String key, String value) {
        config.set(key, value);
        save();
    }

    @Override
    public void setStrict(String key, String value) throws Exception {
        config.set(key, value);
        saveStrict();
    }

    @Override
    public void setInt(String key, int value) {
        config.set(key, value);
        save();
    }

    @Override
    public void setIntStrict(String key, int value) throws Exception {
        config.set(key, value);
        saveStrict();
    }

    @Override
    public int getInt(String key, int defaultValue) {
        return config.getInt(key, defaultValue);
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[UpgradeMeta] 保存 upgrade-state.yml 失败", e);
        }
    }

    private void saveStrict() throws IOException {
        config.save(file);
    }
}
