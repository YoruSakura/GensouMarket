package net.scarletphantasy.gensouMarket.config;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.model.PressureBucket;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.shop.PriceContext;
import net.scarletphantasy.gensouMarket.shop.PriceEngine;
import net.scarletphantasy.gensouMarket.shop.PressureWindowManager;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class PricingGroupTest {

    @TempDir Path directory;
    private GensouMarket plugin;
    private Logger logger;
    private YamlConfiguration global;
    private YamlConfiguration recycle;
    private YamlConfiguration shop;
    private ConfigManager manager;
    private PricingConfigResolver resolver;
    private PriceEngine engine;
    private final Map<String, YamlConfiguration> groupFiles = new LinkedHashMap<>();

    @BeforeEach void setUp() throws Exception {
        for (String name : List.of("config.yml", "shop.yml", "recycle.yml")) {
            try (var resource = getClass().getResourceAsStream("/" + name)) {
                Files.copy(Objects.requireNonNull(resource), directory.resolve(name));
            }
        }
        global = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        recycle = YamlConfiguration.loadConfiguration(directory.resolve("recycle.yml").toFile());
        shop = YamlConfiguration.loadConfiguration(directory.resolve("shop.yml").toFile());
        plugin = mock(GensouMarket.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> getClass().getResourceAsStream("/config.yml"));
        when(plugin.getConfig()).thenAnswer(ignored ->
                YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile()));
        manager = new ConfigManager(plugin);
        resolver = new PricingConfigResolver(manager, logger);
        engine = new PriceEngine(resolver);
    }

    @Test void ungroupedItemsKeepGlobalAndIndividualSettings() throws Exception {
        global.set("dynamic-pricing.recycle.cycle-amplitude", 0.12);
        global.set("dynamic-pricing.shop.fixed-dynamic-enabled", true);
        recycle.set("items.diamond.pricing.min-price-volume", 123);
        recycle.set("items.diamond.pricing.cycle-period-hours", 6);
        recycle.set("items.diamond.pricing.cycle-amplitude", 0);
        shop.set("items.diamond.pricing.max-sell-multiplier", 2);
        reload();

        var recycled = resolver.resolveForRecycleItem("diamond");
        assertEquals(123, recycled.minPriceVolume());
        assertEquals(120, recycled.pressureWindowMinutes());
        assertEquals(0.5, recycled.minMultiplier());
        assertEquals(0.12, resolver.resolveForRecycleItem("iron_ingot").cycleAmplitude());
        var selling = resolver.resolveForShopItem("diamond");
        assertTrue(selling.fixedDynamicEnabled());
        assertEquals(2, selling.maxSellMultiplier());
        assertEquals(6, selling.cyclePeriodHours());
        assertEquals(0, selling.cycleAmplitude());
    }

    @Test void differentGroupsChangeActualRecycleQuotes() throws Exception {
        group("farm", "recycle", "min-price-volume", 100);
        group("farm", "recycle", "cycle-amplitude", 0);
        group("rare", "recycle", "min-price-volume", 10);
        group("rare", "recycle", "cycle-amplitude", 0);
        recycle.set("items.wheat.pricing-group", "farm");
        recycle.set("items.diamond.pricing-group", "rare");
        reload();

        // 基础价相同、已回收 50 件：farm 到达门槛一半，rare 已到下限。
        assertEquals(75, recycleQuote("wheat", 50));
        assertEquals(50, recycleQuote("diamond", 50));
        assertEquals(100, recycleQuote("wheat", 0));
    }

    @Test void individualFieldsOverrideTheGroupIncludingFalseAndZero() throws Exception {
        group("farm", "recycle", "dynamic-enabled", false);
        group("farm", "recycle", "min-price-volume", 100);
        group("farm", "recycle", "min-multiplier", 0.6);
        group("farm", "recycle", "cycle-amplitude", 0.15);
        group("farm", "recycle", "pressure-window-minutes", 90);
        group("farm", "recycle", "bucket-seconds", 30);
        group("farm", "shop", "fixed-dynamic-enabled", true);
        group("farm", "shop", "target-recycled-stock", 1000);
        group("farm", "shop", "pricing-reference-stock", 777);
        recycle.set("items.diamond.pricing-group", "farm");
        recycle.set("items.iron_ingot.pricing-group", "farm");
        recycle.set("items.diamond.pricing.dynamic-enabled", true);
        recycle.set("items.diamond.pricing.cycle-amplitude", 0);
        shop.set("items.diamond.pricing-group", "farm");
        shop.set("items.diamond.pricing.fixed-dynamic-enabled", false);
        shop.set("items.diamond.pricing.target-recycled-stock", 0);
        reload();

        var resolved = resolver.resolveForRecycleItem("diamond");
        assertTrue(resolved.dynamicEnabled());
        assertEquals(0, resolved.cycleAmplitude());
        assertEquals(90, resolved.pressureWindowMinutes());
        assertEquals(30, resolved.bucketSeconds());
        assertEquals(100, resolved.minPriceVolume());
        assertEquals(0.6, resolved.minMultiplier());
        assertEquals(1.5, resolved.maxMultiplier());
        assertEquals(60, recycleQuote("diamond", 100));
        assertFalse(resolver.resolveForRecycleItem("iron_ingot").dynamicEnabled());
        assertEquals(0.15, resolver.resolveForRecycleItem("iron_ingot").cycleAmplitude());
        var selling = resolver.resolveForShopItem("diamond");
        assertFalse(selling.fixedDynamicEnabled());
        assertEquals(0, selling.targetRecycledStock());
        assertEquals(777, selling.pricingReferenceStock());
        assertEquals(0.15, manager.getPricingGroupSection("farm").getDouble("recycle.cycle-amplitude"));
    }

    @Test void priceBoundsAreValidatedAfterApplyingIndividualOverrides() throws Exception {
        group("limits", "recycle", "min-multiplier", 0.9);
        group("limits", "recycle", "max-multiplier", 0.8);
        group("limits", "shop", "min-sell-price", 150);
        group("limits", "shop", "max-sell-price", 100);
        recycle.set("items.diamond.pricing-group", "limits");
        recycle.set("items.diamond.pricing.max-multiplier", 1.2);
        shop.set("items.diamond.pricing-group", "limits");
        shop.set("items.diamond.pricing.max-sell-price", 200);
        reload();

        assertEquals(0.9, resolver.resolveForRecycleItem("diamond").minMultiplier());
        assertEquals(1.2, resolver.resolveForRecycleItem("diamond").maxMultiplier());
        assertEquals(150, resolver.resolveForShopItem("diamond").minSellPrice());
        assertEquals(200, resolver.resolveForShopItem("diamond").maxSellPrice());
    }

    @Test void missingGroupsWarnOnceAndKeepIndividualAndGlobalFallbacks() throws Exception {
        recycle.set("items.diamond.pricing-group", "missing");
        recycle.set("items.diamond.pricing.min-price-volume", 17);
        shop.set("items.diamond.pricing-group", "missing");
        shop.set("items.diamond.pricing.min-sell-multiplier", 0.7);
        reload();

        for (int i = 0; i < 3; i++) {
            assertEquals(17, resolver.resolveForRecycleItem("diamond").minPriceVolume());
            assertEquals(0.5, resolver.resolveForRecycleItem("diamond").minMultiplier());
            assertEquals(0.7, resolver.resolveForShopItem("diamond").minSellMultiplier());
            assertEquals(4096, resolver.resolveForShopItem("diamond").targetRecycledStock());
        }
        verify(logger, times(1)).warning(contains("回收定价配置: diamond 引用了不存在的分组 missing"));
        verify(logger, times(1)).warning(contains("商店定价配置: diamond 引用了不存在的分组 missing"));
    }

    @Test void groupNamesAllowSurroundingWhitespace() throws Exception {
        group("farm", "recycle", "min-price-volume", 1234);
        recycle.set("items.diamond.pricing-group", " farm ");
        reload();
        assertEquals(1234, resolver.resolveForRecycleItem("diamond").minPriceVolume());
    }

    @Test void reloadAppliesChangedGroupsWithoutRecreatingThePriceEngine() throws Exception {
        group("farm", "recycle", "min-price-volume", 100);
        group("farm", "recycle", "cycle-amplitude", 0);
        group("farm", "shop", "target-recycled-stock", 100);
        recycle.set("items.diamond.pricing-group", "farm");
        shop.set("items.diamond.pricing-group", "farm");
        reload();
        assertEquals(75, recycleQuote("diamond", 50));

        group("farm", "recycle", "min-price-volume", 200);
        group("farm", "shop", "target-recycled-stock", 200);
        global.set("obsolete-setting", "remove me");
        reload();
        assertEquals(92.19, recycleQuote("diamond", 50));
        assertEquals(200, resolver.resolveForShopItem("diamond").targetRecycledStock());
        var saved = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(200, YamlConfiguration.loadConfiguration(directory.resolve("pricing-groups/farm.yml").toFile())
                .getInt("recycle.min-price-volume"));
        assertFalse(saved.contains("obsolete-setting"));
    }

    @Test void disablingGroupsFallsBackWithoutDeletingFilesOrMemberships() throws Exception {
        group("farm", "recycle", "min-price-volume", 10);
        group("farm", "recycle", "cycle-amplitude", 0);
        recycle.set("items.diamond.pricing-group", "farm");
        recycle.set("items.diamond.pricing.min-multiplier", 0.6);
        reload();
        assertEquals(60, recycleQuote("diamond", 50));
        global.set("dynamic-pricing.groups-enabled", false);
        reload();
        assertNull(manager.getRecycleItemPricingGroup("diamond"));
        assertNull(manager.getPricingGroupSection("farm"));
        assertEquals(4096, resolver.resolveForRecycleItem("diamond").minPriceVolume());
        assertEquals(0.6, resolver.resolveForRecycleItem("diamond").minMultiplier());
        assertTrue(Files.isRegularFile(directory.resolve("pricing-groups/farm.yml")));
        assertEquals(List.of("diamond"), manager.getGroupItemIds("recycle", "farm"));
        global.set("dynamic-pricing.groups-enabled", true);
        reload();
        assertEquals(60, recycleQuote("diamond", 50));
    }

    @Test void ungroupedRecycledShopKeepsItsSourcesCycleEvenWithADifferentId() throws Exception {
        group("source", "recycle", "cycle-period-hours", 6);
        group("source", "recycle", "cycle-amplitude", 0.13);
        recycle.set("items.diamond.pricing-group", "source");
        shop.set("items.gems.mode", "recycled");
        shop.set("items.gems.recycle-source", "diamond");
        reload();

        var resolved = resolver.resolveForShopItem("gems");
        assertEquals(6, resolved.cyclePeriodHours());
        assertEquals(0.13, resolved.cycleAmplitude());
    }

    @Test void shopCycleOverridesTheGroupsRecycleCycleAndIndividualCycleOverridesBoth() throws Exception {
        group("farm", "recycle", "cycle-period-hours", 12);
        group("farm", "recycle", "cycle-amplitude", 0.2);
        group("farm", "shop", "fixed-dynamic-enabled", true);
        shop.set("items.diamond.pricing-group", "farm");
        reload();
        assertEquals(12, resolver.resolveForShopItem("diamond").cyclePeriodHours());
        assertEquals(0.2, resolver.resolveForShopItem("diamond").cycleAmplitude());
        for (ShopItem.StockMode mode : ShopItem.StockMode.values()) {
            var item = new ShopItem("diamond", Material.DIAMOND, 100);
            item.setStockMode(mode);
            var quote = engine.calculateSellPrice(item, null, new PriceContext(0, 1, 1, 0, 0, 100, 100));
            assertNotEquals(1, quote.cycleMultiplier(), "两种 fixed 库存模式都应使用分组周期");
            assertNotEquals(100, quote.unitPrice());
            var halfCycle = engine.calculateSellPrice(item, null,
                    new PriceContext(6 * 3600_000L, 1, 1, 0, 0, 100, 100));
            assertEquals(2, quote.cycleMultiplier() + halfCycle.cycleMultiplier(), 0.000001,
                    "12 小时周期在间隔 6 小时时应处于相反波动位置");
        }

        group("farm", "shop", "cycle-period-hours", 8);
        group("farm", "shop", "cycle-amplitude", 0.1);
        reload();
        assertEquals(8, resolver.resolveForShopItem("diamond").cyclePeriodHours());
        assertEquals(0.1, resolver.resolveForShopItem("diamond").cycleAmplitude());
        var fixed = new ShopItem("diamond", Material.DIAMOND, 100);
        assertEquals(2, engine.calculateSellPrice(fixed, null, PriceContext.ofTime(0)).cycleMultiplier()
                + engine.calculateSellPrice(fixed, null, PriceContext.ofTime(4 * 3600_000L)).cycleMultiplier(),
                0.000001);
        shop.set("items.diamond.pricing.cycle-period-hours", 4);
        shop.set("items.diamond.pricing.cycle-amplitude", 0);
        reload();
        assertEquals(4, resolver.resolveForShopItem("diamond").cyclePeriodHours());
        assertEquals(100, engine.calculateSellPrice(new ShopItem("diamond", Material.DIAMOND, 100),
                null, PriceContext.ofTime(0)).unitPrice());
    }

    @Test void recycledShopCycleDoesNotReplaceItsSourcesAntiArbitragePrice() throws Exception {
        group("source", "recycle", "cycle-amplitude", 0);
        group("source", "recycle", "min-price", 120);
        recycle.set("items.diamond.pricing-group", "source");
        group("store", "shop", "cycle-amplitude", 0.1);
        group("store", "shop", "cycle-period-hours", 8);
        shop.set("items.gems.mode", "recycled");
        shop.set("items.gems.recycle-source", "diamond");
        shop.set("items.gems.pricing-group", "store");
        reload();
        var item = new ShopItem("gems", Material.DIAMOND, 100);
        item.setMode(ShopItem.Mode.RECYCLED);
        item.setRecycleSourceId("diamond");
        var source = new RecycleItem("diamond", Material.DIAMOND, 100);
        var context = new PriceContext(0, 1, 1, 0, 4096, 0, 0);
        var quote = engine.calculateSellPrice(item, source, context);

        assertNotEquals(1, quote.cycleMultiplier());
        assertEquals(138, quote.unitPrice(), "来源回收价 120 × 防套利 1.15");
        assertTrue(quote.antiArbitrageApplied());
    }

    @Test void groupStockConstantsChangeTheActualShopPrice() throws Exception {
        group("stock", "recycle", "cycle-amplitude", 0);
        group("stock", "shop", "fixed-dynamic-enabled", true);
        group("stock", "shop", "pricing-reference-stock", 200);
        group("stock", "shop", "target-recycled-stock", 100);
        shop.set("items.diamond.pricing-group", "stock");
        reload();
        var item = new ShopItem("diamond", Material.DIAMOND, 100);
        item.setStockMode(ShopItem.StockMode.LIMITED);
        var context = new PriceContext(0, 1, 1, 4096, 50, 100, 100);
        assertEquals(125, engine.calculateSellPrice(item, null, context).unitPrice());
        assertEquals(250.25, engine.calculateBatchSellPrice(item, null, context, 2).totalPrice());
        item.setMode(ShopItem.Mode.RECYCLED);
        var source = new RecycleItem("diamond", Material.DIAMOND, 100);
        assertEquals(110, engine.calculateSellPrice(item, source, context).unitPrice());
        assertEquals(220.5, engine.calculateBatchSellPrice(item, source, context, 2).totalPrice());
    }

    @Test void groupWindowsApplyToPressureBucketsAndKeepItemsSeparate() throws Exception {
        group("short", "recycle", "pressure-window-minutes", 10);
        group("long", "recycle", "pressure-window-minutes", 120);
        recycle.set("items.diamond.pricing-group", "short");
        recycle.set("items.iron_ingot.pricing-group", "long");
        recycle.set("items.wheat.pricing-group", "short");
        reload();
        long now = System.currentTimeMillis();
        long old = now - 30 * 60_000;
        var storage = mock(StorageProvider.class);
        when(storage.loadAllPressureBuckets()).thenReturn(Map.of(
                "diamond", List.of(new PressureBucket("diamond", old, 50, old)),
                "iron_ingot", List.of(new PressureBucket("iron_ingot", old, 100, old))));
        var pressure = new PressureWindowManager(plugin, storage, logger);
        var shortConfig = resolver.resolveForRecycleItem("diamond");
        var longConfig = resolver.resolveForRecycleItem("iron_ingot");
        pressure.loadAll(Map.of("diamond", shortConfig, "iron_ingot", longConfig));
        assertEquals(0, pressure.getActiveVolume("diamond", shortConfig, now));
        assertEquals(100, pressure.getActiveVolume("iron_ingot", longConfig, now));
        pressure.mergeRemotePressure("diamond", now, 7);
        assertEquals(7, pressure.getActiveVolume("diamond", shortConfig, now));
        assertEquals(0, pressure.getActiveVolume("wheat", resolver.resolveForRecycleItem("wheat"), now));
        assertEquals(100, pressure.getActiveVolume("iron_ingot", longConfig, now));
    }

    private void group(String id, String module, String key, Object value) {
        groupFiles.computeIfAbsent(id, ignored -> new YamlConfiguration()).set(module + "." + key, value);
    }

    private double recycleQuote(String id, int volume) {
        return engine.calculateRecyclePrice(new RecycleItem(id, Material.DIAMOND, 100),
                new PriceContext(0, 1, 1, volume, 0, 0, 0)).unitPrice();
    }

    private void reload() throws Exception {
        global.save(directory.resolve("config.yml").toFile());
        shop.save(directory.resolve("shop.yml").toFile());
        recycle.save(directory.resolve("recycle.yml").toFile());
        Files.createDirectories(directory.resolve("pricing-groups"));
        for (var entry : groupFiles.entrySet()) {
            entry.getValue().save(directory.resolve("pricing-groups/" + entry.getKey() + ".yml").toFile());
        }
        manager.reload();
    }
}
