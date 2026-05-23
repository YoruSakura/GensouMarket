package net.scarletphantasy.gensouMarket;

import net.scarletphantasy.gensouMarket.bridge.ClusterEventPublisher;
import net.scarletphantasy.gensouMarket.bridge.ProxyBridge;
import net.scarletphantasy.gensouMarket.bridge.RemoteActionHandler;
import net.scarletphantasy.gensouMarket.bridge.ViewSessionRegistry;
import net.scarletphantasy.gensouMarket.command.CommandManager;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.economy.EconomySnapshotService;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.gui.GuiListener;
import net.scarletphantasy.gensouMarket.listener.PersonalShopSignListener;
import net.scarletphantasy.gensouMarket.listener.PlayerListener;
import net.scarletphantasy.gensouMarket.mail.MailNotificationService;
import net.scarletphantasy.gensouMarket.market.AuctionManager;
import net.scarletphantasy.gensouMarket.market.MarketManager;
import net.scarletphantasy.gensouMarket.shop.PersonalShopSignManager;
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
    private PersonalShopSignManager personalShopSignManager;
    private MailNotificationService mailNotificationService;
    private EconomySnapshotService economySnapshotService;

    // 跨服 Bridge（cluster.enabled=true 时才初始化）
    private ProxyBridge proxyBridge;
    private ClusterEventPublisher clusterEventPublisher;
    private ViewSessionRegistry viewSessionRegistry;

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

        // 跨服模式校验：必须使用 MySQL
        if (configManager.isClusterEnabled() && !"mysql".equalsIgnoreCase(configManager.getStorageType())) {
            getLogger().severe("跨服模式 (cluster.enabled=true) 必须使用 MySQL 存储！当前: " + configManager.getStorageType());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

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

        // v1.1.1 全服经济快照服务
        economySnapshotService = new EconomySnapshotService(this);
        economySnapshotService.start();

        // 个人商店牌子管理（task-02）
        personalShopSignManager = new PersonalShopSignManager(this);
        personalShopSignManager.load();

        // 初始化跨服 Bridge
        if (configManager.isClusterEnabled()) {
            viewSessionRegistry = new ViewSessionRegistry();
            proxyBridge = new ProxyBridge(this, configManager.getClusterChannel(), configManager.getClusterServerId());
            clusterEventPublisher = new ClusterEventPublisher(proxyBridge);
            RemoteActionHandler remoteHandler = new RemoteActionHandler(this, viewSessionRegistry);
            proxyBridge.setRemoteActionHandler(remoteHandler);
            proxyBridge.enable();
            mailNotificationService = new MailNotificationService(this);
            mailNotificationService.start();
            getLogger().info("跨服模式已启用 (serverId=" + configManager.getClusterServerId() + ")");
        }

        // 按当前 auction.enabled 同步拍卖定时器状态（启用时恢复，禁用时不注册）
        auctionManager.applyModuleState();

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
        getServer().getPluginManager().registerEvents(
                new PersonalShopSignListener(this, personalShopSignManager), this);

        // 定时任务：检查过期上架和结束拍卖 (每分钟，异步执行)
        // 各模块按开关控制，被关闭的模块不在本服扫描，由其他开启的子服兜底
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (configManager.isMarketEnabled()) {
                marketManager.checkExpiredListingsAsync();
            }
            if (configManager.isAuctionEnabled()) {
                auctionManager.checkEndedAuctionsAsync();
            }
        }, 1200L, 1200L);

        // 定时任务：保存商店和回收数据 (每5分钟，异步执行)
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (configManager.isShopEnabled()) {
                shopManager.saveAllData();
            }
            if (configManager.isRecycleEnabled()) {
                recycleManager.saveAllData();
            }
        }, 6000L, 6000L);

        // v1.1.1 定时任务：集群模式下定期从 MySQL 校准压力数据 (每3分钟，异步执行)
        if (isClusterEnabled()) {
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                if (configManager.isRecycleEnabled()) {
                    java.util.Map<String, net.scarletphantasy.gensouMarket.config.RecyclePricingConfig> configMap = new java.util.HashMap<>();
                    net.scarletphantasy.gensouMarket.config.PricingConfigResolver resolver = new net.scarletphantasy.gensouMarket.config.PricingConfigResolver(configManager, getLogger());
                    for (String id : recycleManager.getRecycleItems().keySet()) {
                        configMap.put(id, resolver.resolveForRecycleItem(id));
                    }
                    recycleManager.getPressureWindow().loadAll(configMap);
                    getLogger().fine("[Cluster] 压力数据定期校准完成");
                }
            }, 3600L, 3600L);
        }

        getLogger().info("幻想集市已启用！");
    }

    @Override
    public void onDisable() {
        if (economySnapshotService != null) economySnapshotService.stop();
        if (mailNotificationService != null) mailNotificationService.stop();
        if (proxyBridge != null) proxyBridge.disable();
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
    public ProxyBridge getProxyBridge() { return proxyBridge; }
    public ClusterEventPublisher getClusterEventPublisher() { return clusterEventPublisher; }
    public ViewSessionRegistry getViewSessionRegistry() { return viewSessionRegistry; }
    public PersonalShopSignManager getPersonalShopSignManager() { return personalShopSignManager; }
    public MailNotificationService getMailNotificationService() { return mailNotificationService; }
    public EconomySnapshotService getEconomySnapshotService() { return economySnapshotService; }
    public boolean isClusterEnabled() { return proxyBridge != null; }
}
