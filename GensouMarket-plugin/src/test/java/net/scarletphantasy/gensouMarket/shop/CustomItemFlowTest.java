package net.scarletphantasy.gensouMarket.shop;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.bridge.ClusterEventPublisher;
import net.scarletphantasy.gensouMarket.economy.VaultHook;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Paper 的二进制编码与组件比较在 API 边界用替身提供，配置、匹配、库存及结算使用生产类。 */
class CustomItemFlowTest {
    @TempDir Path directory;
    private GensouMarket plugin;
    private Material material;
    private ConfigManager config;
    private RecycleManager recycle;
    private ShopManager shop;
    private StorageProvider storage;
    private VaultHook vault;
    private Player player;
    private PlayerInventory inventory;
    private ItemStack[] contents = new ItemStack[0];
    private final List<ItemStack> delivered = new ArrayList<>();
    private final Map<ItemStack, String> identities = new IdentityHashMap<>();
    private MockedStatic<Material> materials;
    private MockedStatic<ItemStack> items;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<GensouMarket> instance;

    @BeforeEach void setUp() throws Exception {
        material = mock(Material.class);
        when(material.name()).thenReturn("PAPER");
        when(material.translationKey()).thenReturn("item.minecraft.paper");
        materials = mockStatic(Material.class);
        materials.when(() -> Material.matchMaterial("PAPER")).thenReturn(material);
        items = mockStatic(ItemStack.class);
        items.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenAnswer(call -> {
            String[] payload = new String(call.getArgument(0, byte[].class), StandardCharsets.UTF_8).split("\\n");
            return stack(payload[0], Integer.parseInt(payload[1]));
        });
        try (var resource = getClass().getResourceAsStream("/config.yml")) {
            Files.copy(Objects.requireNonNull(resource), directory.resolve("config.yml"));
        }
        var global = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        global.set("dynamic-pricing.enabled", false);
        global.save(directory.resolve("config.yml").toFile());
        Files.writeString(directory.resolve("shop.yml"), "items: {}\n");
        Files.writeString(directory.resolve("recycle.yml"), "items: {}\n");
        plugin = mock(GensouMarket.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> getClass().getResourceAsStream("/config.yml"));
        when(plugin.getConfig()).thenAnswer(ignored -> YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile()));
        config = new ConfigManager(plugin);
        config.load();
        when(plugin.getConfigManager()).thenReturn(config);
        storage = mock(StorageProvider.class);
        when(plugin.getStorage()).thenReturn(storage);
        when(storage.loadRecycleData()).thenReturn(Map.of());
        when(storage.loadShopData()).thenReturn(Map.of());
        when(storage.loadAllPressureBuckets()).thenReturn(Map.of());
        vault = mock(VaultHook.class);
        when(plugin.getVaultHook()).thenReturn(vault);
        when(vault.has(any(), anyDouble())).thenReturn(true);
        when(vault.withdraw(any(), anyDouble())).thenReturn(true);
        when(vault.deposit(any(), anyDouble())).thenReturn(true);
        recycle = new RecycleManager(plugin);
        shop = new ShopManager(plugin);
        when(plugin.getRecycleManager()).thenReturn(recycle);
        when(plugin.getShopManager()).thenReturn(shop);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(inventory.getContents()).thenAnswer(ignored -> contents);
        doAnswer(call -> { contents[call.getArgument(0, Integer.class)] = call.getArgument(1); return null; })
                .when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> {
            delivered.add(call.getArgument(0));
            return new HashMap<Integer, ItemStack>();
        });
        var scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run(); return null;
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run(); return null;
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        instance = mockStatic(GensouMarket.class);
        instance.when(GensouMarket::getInstance).thenReturn(plugin);
    }

    @AfterEach void close() {
        for (MockedStatic<?> mock : new MockedStatic<?>[]{instance, bukkit, items, materials}) {
            if (mock != null) mock.close();
        }
    }

    @Test void capturedDataSurvivesReloadAndSameMaterialVariantsKeepSeparatePrices() throws Exception {
        var first = stack("name=red;nbt=one;components=custom", 64);
        var second = stack("name=blue;nbt=two;components=other", 12);
        assertTrue(config.saveRecycleItem("red", material, first, 10, null));
        assertTrue(config.saveRecycleItem("blue", material, second, 100, null));
        reloadItems();
        assertEquals("red", recycle.getItemForStack(first).getId());
        assertEquals("blue", recycle.getItemForStack(second).getId());
        assertEquals(10, recycle.getItemForStack(first).getBaseRecyclePrice());
        assertEquals(100, recycle.getItemForStack(second).getBaseRecyclePrice());
        assertEquals(64, first.getAmount(), "注册模板不得消耗或修改手持数量");
        assertEquals(12, second.getAmount());
        assertEquals(1, recycle.getItemForStack(first).createItemStack(1).getAmount());
    }

    @Test void duplicateShopTemplateIsRejectedWithoutChangingFilesOrExistingDefinition() throws Exception {
        var original = stack("name=red;nbt=one;components=custom", 16);
        assertTrue(shop.addItem("original", original, 100, null));
        var definition = shop.getShopItems().get("original");
        definition.setTotalBought(3);
        definition.setAvailableStock(7);
        String saved = Files.readString(directory.resolve("shop.yml"));

        assertFalse(shop.addItem("duplicate", stack("name=red;nbt=one;components=custom", 1), 200, null));

        assertEquals(saved, Files.readString(directory.resolve("shop.yml")));
        assertEquals(Set.of("original"), shop.getShopItems().keySet());
        assertEquals(100, definition.getBaseBuyPrice());
        assertEquals(3, definition.getTotalBought());
        assertEquals(7, definition.getAvailableStock());
        assertEquals(16, original.getAmount());
        verify(storage, times(1)).saveShopData(any(ShopItem.class));

        assertTrue(shop.addItem("different", stack("name=red;nbt=two;components=custom", 1), 200, null));
        assertEquals(Set.of("original", "different"), shop.getShopItems().keySet());
    }

    @Test void reloadedShopTemplateAlsoPreventsDuplicateRegistration() throws Exception {
        assertTrue(config.saveShopItem("original", material, stack("persisted-nbt", 32), 100, null));
        reloadItems();
        String saved = Files.readString(directory.resolve("shop.yml"));

        assertFalse(shop.addItem("duplicate", stack("persisted-nbt", 1), 200, null));

        assertEquals(saved, Files.readString(directory.resolve("shop.yml")));
        assertEquals(Set.of("original"), shop.getShopItems().keySet());
        verify(storage, never()).saveShopData(any(ShopItem.class));
    }

    @Test void recycleOnlyCountsAndRemovesTheExactVariant() throws Exception {
        var red = stack("red-with-nbt", 3);
        var blue = stack("blue-with-nbt", 4);
        assertTrue(config.saveRecycleItem("red", material, red, 10, null));
        assertTrue(config.saveRecycleItem("blue", material, blue, 100, null));
        reloadItems();
        contents = new ItemStack[]{red, blue};
        var definition = recycle.getItemForStack(red);
        assertEquals(3, recycle.countMatching(player, definition));
        assertTrue(recycle.recycleItem(player, definition, 2));
        assertEquals(1, red.getAmount());
        assertEquals(4, blue.getAmount());
        verify(vault).deposit(player, 20);
        assertEquals(2, definition.getRecycledStock());
        assertEquals(0, recycle.getItemForStack(blue).getRecycledStock());
    }

    @Test void allShopDeliveryPathsPreserveTheCorrectTemplate() throws Exception {
        assertTrue(config.saveShopItem("custom", material, stack("shop-component", 32), 10, null));
        assertTrue(config.saveRecycleItem("source", material, stack("source-component", 16), 10, null));
        reloadItems();
        var item = shop.getShopItems().get("custom");
        shop.buyFromShop(player, "custom", 2);
        assertDelivered("shop-component", 2);
        item.setStockMode(ShopItem.StockMode.LIMITED);
        item.setAvailableStock(5);
        item.setInitialStock(5);
        when(storage.getShopStock("custom")).thenReturn(5, 3);
        when(storage.consumeShopStockIfEnough("custom", 2)).thenReturn(true);
        shop.buyFromShop(player, "custom", 2);
        assertDelivered("shop-component", 2);
        item.setMode(ShopItem.Mode.RECYCLED);
        item.setRecycleSourceId("source");
        var source = recycle.getRecycleItems().get("source");
        source.setRecycledStock(5);
        shop.buyFromShop(player, "custom", 2);
        assertDelivered("source-component", 2);
        assertEquals(3, source.getRecycledStock());
        assertEquals(3, delivered.size());
        when(plugin.isClusterEnabled()).thenReturn(true);
        when(plugin.getClusterEventPublisher()).thenReturn(mock(ClusterEventPublisher.class));
        when(storage.consumeRecycleStockIfEnough(eq(source), eq(2), anyLong())).thenReturn(true);
        shop.buyFromShop(player, "custom", 2);
        assertDelivered("source-component", 2);
        assertEquals(1, source.getRecycledStock());
        assertEquals(4, delivered.size());
    }

    @Test void priceChangesKeepTemplatesMembershipAndIndividualOverrides() throws Exception {
        config.getPricingGroups().create("farm");
        assertTrue(config.saveRecycleItem("custom", material, stack("all-data", 16), 10, "farm"));
        assertTrue(config.saveShopItem("custom", material, stack("all-data", 16), 20, "farm"));
        reloadItems();
        String recycleData = YamlConfiguration.loadConfiguration(directory.resolve("recycle.yml").toFile())
                .getString("items.custom.item-data");
        assertTrue(recycle.setPrice("custom", 30));
        assertTrue(shop.setPrice("custom", 40));
        reloadItems();
        assertEquals("all-data", identities.get(recycle.getRecycleItems().get("custom").createItemStack(1)));
        assertEquals("all-data", identities.get(shop.createSaleItem(shop.getShopItems().get("custom"), 1)));
        assertEquals("farm", config.getRecycleItemPricingGroup("custom"));
        assertEquals("farm", config.getShopItemPricingGroup("custom"));
        assertEquals(recycleData, YamlConfiguration.loadConfiguration(directory.resolve("recycle.yml").toFile())
                .getString("items.custom.item-data"));
        assertEquals(30, recycle.getRecycleItems().get("custom").getBaseRecyclePrice());
        assertEquals(40, shop.getShopItems().get("custom").getBaseBuyPrice());
        assertTrue(config.setItemPricingGroup("recycle", "custom", null));
        assertTrue(config.setItemPricingGroup("recycle", "custom", "farm"));
        assertEquals(recycleData, YamlConfiguration.loadConfiguration(directory.resolve("recycle.yml").toFile())
                .getString("items.custom.item-data"));
    }

    @Test void unreadableStoredItemIsSkippedAndNeverBecomesAVanillaItem() throws Exception {
        var file = YamlConfiguration.loadConfiguration(directory.resolve("recycle.yml").toFile());
        file.set("items.broken.material", "PAPER");
        file.set("items.broken.item-data", "not base64!");
        file.save(directory.resolve("recycle.yml").toFile());
        reloadItems();
        assertFalse(recycle.getRecycleItems().containsKey("broken"));
        assertFalse(recycle.addItem("broken", stack("different-data", 1), 10, null));
        assertEquals("not base64!", YamlConfiguration.loadConfiguration(directory.resolve("recycle.yml").toFile())
                .getString("items.broken.item-data"));
    }

    @Test void failedItemFileWriteDoesNotRegisterAnUnsavedRuntimeDefinition() throws Exception {
        Files.delete(directory.resolve("recycle.yml"));
        Files.createDirectory(directory.resolve("recycle.yml"));
        assertFalse(recycle.addItem("new_custom", stack("custom-data", 16), 10, null));
        assertTrue(recycle.getRecycleItems().isEmpty());
        verify(storage, never()).saveRecycleData(any());
    }

    @Test void vanillaDefinitionsDoNotMatchCustomVariantsAndQuantityIsIgnored() {
        var plain = stack("vanilla", 64);
        var custom = stack("custom", 64);
        try (var constructions = mockConstruction(ItemStack.class, (mock, context) ->
                when(mock.isSimilar(any())).thenAnswer(call -> "vanilla".equals(identities.get(call.getArgument(0)))))) {
            var vanillaDefinition = new RecycleItem("paper", material, 1);
            assertTrue(vanillaDefinition.matches(plain));
            assertFalse(vanillaDefinition.matches(custom));
            var exact = new RecycleItem("custom", material, 10);
            exact.setItemTemplate(custom);
            assertTrue(exact.matches(stack("custom", 1)));
            assertFalse(exact.matches(plain));
            assertFalse(exact.matches(stack("different-component", 64)));
        }
    }

    private void reloadItems() {
        config.reload();
        recycle.loadItems();
        shop.loadItems();
    }

    private void assertDelivered(String identity, int amount) {
        ItemStack last = delivered.getLast();
        assertEquals(identity, identities.get(last));
        assertEquals(amount, last.getAmount());
    }

    private ItemStack stack(String identity, int initialAmount) {
        var result = mock(ItemStack.class);
        identities.put(result, identity);
        var amount = new AtomicInteger(initialAmount);
        when(result.getType()).thenReturn(material);
        when(result.getAmount()).thenAnswer(ignored -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(result).setAmount(anyInt());
        when(result.clone()).thenAnswer(ignored -> stack(identity, amount.get()));
        when(result.isSimilar(any())).thenAnswer(call -> identity.equals(identities.get(call.getArgument(0))));
        when(result.serializeAsBytes()).thenAnswer(ignored -> (identity + "\n" + amount.get()).getBytes(StandardCharsets.UTF_8));
        return result;
    }
}
