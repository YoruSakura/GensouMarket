package net.scarletphantasy.gensouMarket.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;
import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.util.ConfigMigrator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final GensouMarket plugin;
    private FileConfiguration config;
    private FileConfiguration shopConfig;
    private FileConfiguration recycleConfig;

    public ConfigManager(GensouMarket plugin) {
        this.plugin = plugin;
    }

    public void load() {
        // 确保默认配置文件存在
        plugin.saveDefaultConfig();

        // 对 config.yml 执行自动迁移
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        InputStream defaultConfigStream = plugin.getResource("config.yml");
        if (ConfigMigrator.migrate(configFile, defaultConfigStream, plugin.getLogger())) {
            plugin.getLogger().info("config.yml 迁移完成，正在重新加载...");
        }

        plugin.reloadConfig();
        config = plugin.getConfig();

        // shop.yml 不做结构迁移（内容由管理员管理），仅确保文件存在
        File shopFile = new File(plugin.getDataFolder(), "shop.yml");
        if (!shopFile.exists()) {
            plugin.saveResource("shop.yml", false);
        }
        shopConfig = YamlConfiguration.loadConfiguration(shopFile);

        // recycle.yml 不做结构迁移（内容由管理员管理），仅确保文件存在
        File recycleFile = new File(plugin.getDataFolder(), "recycle.yml");
        if (!recycleFile.exists()) {
            plugin.saveResource("recycle.yml", false);
        }
        recycleConfig = YamlConfiguration.loadConfiguration(recycleFile);
    }

    public void reload() {
        load();
    }

    // ---- Storage ----
    public String getStorageType() { return config.getString("storage.type", "sqlite"); }
    public String getMysqlHost() { return config.getString("storage.mysql.host", "localhost"); }
    public int getMysqlPort() { return config.getInt("storage.mysql.port", 3306); }
    public String getMysqlDatabase() { return config.getString("storage.mysql.database", "gensoumarket"); }
    public String getMysqlUsername() { return config.getString("storage.mysql.username", "root"); }
    public String getMysqlPassword() { return config.getString("storage.mysql.password", "password"); }

    // ---- Market ----
    public boolean isMarketEnabled() { return config.getBoolean("market.enabled", true); }
    public double getListingTax() { return config.getDouble("market.listing-tax", 0.05); }
    public double getTransactionTax() { return config.getDouble("market.transaction-tax", 0.10); }
    public int getMaxListings() { return config.getInt("market.max-listings", 20); }
    public int getExpireHours() { return config.getInt("market.expire-hours", 168); }

    // ---- Auction ----
    public boolean isAuctionEnabled() { return config.getBoolean("auction.enabled", true); }
    public int getDefaultAuctionDuration() { return config.getInt("auction.default-duration", 60); }
    public int getMinAuctionDuration() { return config.getInt("auction.min-duration", 10); }
    public int getMaxAuctionDuration() { return config.getInt("auction.max-duration", 1440); }
    public double getAuctionListingFeeRate() { return config.getDouble("auction.listing-fee-rate", 0.05); }

    // ---- Mail ----
    public boolean isMailEnabled() { return config.getBoolean("mail.enabled", true); }

    // ---- Personal Shop ----
    public boolean isPersonalShopEnabled() { return config.getBoolean("personal-shop.enabled", true); }

    // ---- Shop ----
    public boolean isShopEnabled() { return config.getBoolean("shop.enabled", true); }

    // ---- Recycle ----
    public boolean isRecycleEnabled() { return config.getBoolean("recycle.enabled", true); }

    // ---- Dynamic Pricing ----
    public boolean isDynamicPricingEnabled() { return config.getBoolean("dynamic-pricing.enabled", true); }
    public double getAdjustmentRate() { return config.getDouble("dynamic-pricing.adjustment-rate", 0.002); }
    public double getMaxMultiplier() { return config.getDouble("dynamic-pricing.max-multiplier", 3.0); }
    public double getMinMultiplier() { return config.getDouble("dynamic-pricing.min-multiplier", 0.1); }
    public double getRecoveryRate() { return config.getDouble("dynamic-pricing.recovery-rate", 0.01); }

    // ---- Market Fluctuation ----
    public record SineWaveConfig(double periodHours, double amplitude) {}

    public boolean isMarketFluctuationEnabled() {
        return config.getBoolean("dynamic-pricing.market-fluctuation.enabled", true);
    }

    public List<SineWaveConfig> getFluctuationSineWaves() {
        List<SineWaveConfig> waves = new ArrayList<>();
        List<?> list = config.getList("dynamic-pricing.market-fluctuation.sine-waves");
        if (list != null) {
            for (Object obj : list) {
                if (obj instanceof Map<?, ?> map) {
                    Object periodObj = map.get("period");
                    Object ampObj = map.get("amplitude");
                    double period = periodObj instanceof Number n ? n.doubleValue() : 24.0;
                    double amplitude = ampObj instanceof Number n ? n.doubleValue() : 0.08;
                    waves.add(new SineWaveConfig(period, amplitude));
                }
            }
        }
        if (waves.isEmpty()) {
            waves.add(new SineWaveConfig(24, 0.08));
            waves.add(new SineWaveConfig(72, 0.12));
            waves.add(new SineWaveConfig(168, 0.10));
        }
        return waves;
    }

    public double getFluctuationNoiseAmplitude() {
        return config.getDouble("dynamic-pricing.market-fluctuation.noise-amplitude", 0.06);
    }

    public double getFluctuationNoiseScale() {
        return config.getDouble("dynamic-pricing.market-fluctuation.noise-scale", 12.0);
    }

    public double getMinFluctuation() {
        return config.getDouble("dynamic-pricing.market-fluctuation.min-fluctuation", 0.5);
    }

    public double getMaxFluctuation() {
        return config.getDouble("dynamic-pricing.market-fluctuation.max-fluctuation", 1.5);
    }

    public int getFluctuationCycleMinutes() {
        return config.getInt("dynamic-pricing.market-fluctuation.cycle-minutes", 20);
    }

    // ---- Trade ----
    public boolean isTradeEnabled() { return config.getBoolean("trade.enabled", true); }
    public int getTradeRequestTimeout() { return config.getInt("trade.request-timeout", 30); }
    public double getTradeMaxDistance() { return config.getDouble("trade.max-distance", 10.0); }
    public int getTradeDistanceCheckInterval() { return config.getInt("trade.distance-check-interval", 20); }

    // ---- Prefix ----
    public String getPrefix() { return config.getString("prefix", "&6[幻想集市] &r"); }

    // ---- Cluster ----
    public boolean isClusterEnabled() { return config.getBoolean("cluster.enabled", false); }
    public String getClusterServerId() { return config.getString("cluster.server-id", "default"); }
    public String getClusterChannel() { return config.getString("cluster.channel", "gensoumarket:main"); }

    // ---- Debug ----
    public boolean isDebug() { return config.getBoolean("debug", false); }

    // ---- Shop Items ----
    public Map<String, ShopItem> loadShopItems() {
        Map<String, ShopItem> items = new LinkedHashMap<>();
        ConfigurationSection section = shopConfig.getConfigurationSection("items");
        if (section == null) return items;

        for (String key : section.getKeys(false)) {
            ConfigurationSection itemSec = section.getConfigurationSection(key);
            if (itemSec == null) continue;

            String materialName = itemSec.getString("material");
            if (materialName == null) continue;
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().warning("商店配置: 无效的物品材质 " + materialName + " (ID: " + key + ")");
                continue;
            }

            double buyPrice = itemSec.getDouble("buy-price", 0);
            ShopItem item = new ShopItem(key, material, buyPrice);

            // 模式解析（旧数据无 mode 字段则按 fixed + unlimited 兼容）
            String modeStr = itemSec.getString("mode", "fixed");
            if ("recycled".equalsIgnoreCase(modeStr)) {
                item.setMode(ShopItem.Mode.RECYCLED);
                String source = itemSec.getString("recycle-source");
                if (source == null || source.isEmpty()) {
                    plugin.getLogger().warning("商店配置: recycled 模式物品缺少 recycle-source (ID: " + key + ")，已跳过");
                    continue;
                }
                item.setRecycleSourceId(source);
                double mult = itemSec.getDouble("sell-multiplier", 1.0);
                if (mult <= 0) {
                    plugin.getLogger().warning("商店配置: recycled 模式物品 sell-multiplier 非法 (ID: " + key + ")，已按 1.0 处理");
                    mult = 1.0;
                }
                item.setSellMultiplier(mult);
                // recycled 模式 stockMode 忽略，内部按有限库存处理
                item.setStockMode(ShopItem.StockMode.LIMITED);
            } else {
                item.setMode(ShopItem.Mode.FIXED);
                String stockModeStr = itemSec.getString("stock-mode", "unlimited");
                if ("limited".equalsIgnoreCase(stockModeStr)) {
                    item.setStockMode(ShopItem.StockMode.LIMITED);
                    // availableStock 用 -1 哨兵表示"待与 DB 合并"，ShopManager 合并时若 DB 无记录才使用 initial-stock
                    item.setAvailableStock(-1);
                } else {
                    item.setStockMode(ShopItem.StockMode.UNLIMITED);
                }
            }

            items.put(key, item);
        }
        return items;
    }

    /**
     * 读取商店配置中 fixed+limited 模式的 initial-stock（供 ShopManager 合并时初始化持久化库存使用）。
     */
    public int getShopInitialStock(String id, int fallback) {
        ConfigurationSection section = shopConfig.getConfigurationSection("items." + id);
        if (section == null) return fallback;
        int v = section.getInt("initial-stock", fallback);
        return Math.max(v, 0);
    }

    // ---- Recycle Items ----
    public Map<String, RecycleItem> loadRecycleItems() {
        Map<String, RecycleItem> items = new LinkedHashMap<>();
        ConfigurationSection section = recycleConfig.getConfigurationSection("items");
        if (section == null) return items;

        for (String key : section.getKeys(false)) {
            ConfigurationSection itemSec = section.getConfigurationSection(key);
            if (itemSec == null) continue;

            String materialName = itemSec.getString("material");
            if (materialName == null) continue;
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().warning("回收配置: 无效的物品材质 " + materialName + " (ID: " + key + ")");
                continue;
            }

            double recyclePrice = itemSec.getDouble("recycle-price", 0);
            items.put(key, new RecycleItem(key, material, recyclePrice));
        }
        return items;
    }

    // ---- Shop Config Persistence ----
    public void saveShopItem(String id, Material material, double buyPrice) {
        // 管理员 /gmarket shop add 仅创建 fixed+unlimited 商品（task-05）
        shopConfig.set("items." + id + ".material", material.name());
        shopConfig.set("items." + id + ".mode", "fixed");
        shopConfig.set("items." + id + ".buy-price", buyPrice);
        shopConfig.set("items." + id + ".stock-mode", "unlimited");
        saveShopConfig();
    }

    /**
     * 只修改指定商品的 buy-price，不触碰 mode / stock-mode / initial-stock / recycle-source / sell-multiplier。
     * 供 /gmarket shop setprice 使用，避免把 fixed+limited 商品意外降级为 fixed+unlimited。
     * @return true 表示写入成功（配置中存在该商品）
     */
    public boolean updateShopBuyPrice(String id, double buyPrice) {
        String path = "items." + id;
        if (!shopConfig.contains(path)) return false;
        shopConfig.set(path + ".buy-price", buyPrice);
        saveShopConfig();
        return true;
    }

    public void removeShopItem(String id) {
        shopConfig.set("items." + id, null);
        saveShopConfig();
    }

    private void saveShopConfig() {
        try {
            shopConfig.save(new File(plugin.getDataFolder(), "shop.yml"));
        } catch (IOException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "无法保存 shop.yml！", e);
        }
    }

    // ---- Recycle Config Persistence ----
    public void saveRecycleItem(String id, Material material, double recyclePrice) {
        recycleConfig.set("items." + id + ".material", material.name());
        recycleConfig.set("items." + id + ".recycle-price", recyclePrice);
        saveRecycleConfig();
    }

    public void removeRecycleItem(String id) {
        recycleConfig.set("items." + id, null);
        saveRecycleConfig();
    }

    private void saveRecycleConfig() {
        try {
            recycleConfig.save(new File(plugin.getDataFolder(), "recycle.yml"));
        } catch (IOException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "无法保存 recycle.yml！", e);
        }
    }
}
