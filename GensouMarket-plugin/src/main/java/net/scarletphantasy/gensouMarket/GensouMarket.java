package net.scarletphantasy.gensouMarket;

import net.scarletphantasy.gensouMarket.command.CommandManager;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.gui.GuiListener;
import net.scarletphantasy.gensouMarket.listener.PlayerListener;
import net.scarletphantasy.gensouMarket.market.AuctionManager;
import net.scarletphantasy.gensouMarket.market.MarketManager;
import net.scarletphantasy.gensouMarket.shop.RecycleManager;
import net.scarletphantasy.gensouMarket.shop.ShopManager;
import net.scarletphantasy.gensouMarket.listener.TradeInteractListener;
import net.scarletphantasy.gensouMarket.storage.StorageFactory;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import net.scarletphantasy.gensouMarket.trade.TradeManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class GensouMarket extends JavaPlugin {

    private static GensouMarket instance;
    private ConfigManager configManager;
    private VaultHook vaultHook;
    private StorageProvider storage;
    private MarketManager marketManager;
    private AuctionManager auctionManager;
    private ShopManager shopManager;
    private RecycleManager recycleManager;
    private TradeManager tradeManager;

    @Override
    public void onEnable() {
        instance = this;

        // 加载配置
        configManager = new ConfigManager(this);
        configManager.load();

        // 连接 Vault
        vaultHook = new VaultHook();
        if (!vaultHook.setup()) {
            getLogger().severe("无法连接到 Vault 经济系统！插件将禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("已连接 Vault 经济系统");

        // 初始化存储
        storage = StorageFactory.create(configManager, getDataFolder());
        try {
            storage.initialize();
            getLogger().info("数据存储已初始化 (" + configManager.getStorageType() + ")");
        } catch (Exception e) {
            getLogger().severe("无法初始化数据存储！" + e.getMessage());
            getLogger().log(java.util.logging.Level.SEVERE, "存储初始化异常", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 初始化管理器
        marketManager = new MarketManager(this);
        auctionManager = new AuctionManager(this);
        shopManager = new ShopManager(this);
        shopManager.loadItems();
        recycleManager = new RecycleManager(this);
        recycleManager.loadItems();

        tradeManager = new TradeManager(this);

        // 恢复活跃拍卖的定时任务
        auctionManager.restoreActiveAuctions();

        // 注册命令
        CommandManager cmdManager = new CommandManager(this);
        var cmd = getCommand("gensoumarket");
        if (cmd != null) {
            cmd.setExecutor(cmdManager);
            cmd.setTabCompleter(cmdManager);
        }

        // 注册事件监听
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new TradeInteractListener(this), this);

        // 定时任务：检查过期上架和结束拍卖 (每分钟，异步执行)
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            marketManager.checkExpiredListingsAsync();
            auctionManager.checkEndedAuctionsAsync();
        }, 1200L, 1200L);

        // 定时任务：保存商店和回收数据 (每5分钟，异步执行)
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            shopManager.saveAllData();
            recycleManager.saveAllData();
        }, 6000L, 6000L);

        getLogger().info("幻想集市已启用！");
    }

    @Override
    public void onDisable() {
        if (tradeManager != null) tradeManager.cancelAllActiveTrades();
        if (shopManager != null) shopManager.saveAllData();
        if (recycleManager != null) recycleManager.saveAllData();
        if (storage != null) storage.shutdown();
        getLogger().info("幻想集市已禁用！");
    }

    public static GensouMarket getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public VaultHook getVaultHook() { return vaultHook; }
    public StorageProvider getStorage() { return storage; }
    public MarketManager getMarketManager() { return marketManager; }
    public AuctionManager getAuctionManager() { return auctionManager; }
    public ShopManager getShopManager() { return shopManager; }
    public RecycleManager getRecycleManager() { return recycleManager; }
    public TradeManager getTradeManager() { return tradeManager; }
}
