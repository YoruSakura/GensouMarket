package net.scarletphantasy.gensouMarket.command;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.shop.RecycleManager;
import net.scarletphantasy.gensouMarket.shop.ShopManager;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PricingGroupCommandTest {
    @TempDir Path directory;
    private GensouMarket plugin;
    private ConfigManager config;
    private Player player;
    private CommandManager commands;
    private ShopManager shop;
    private RecycleManager recycle;
    private MockedStatic<GensouMarket> instance;

    @BeforeEach void setUp() throws Exception {
        for (String name : List.of("config.yml", "shop.yml", "recycle.yml")) {
            try (var resource = getClass().getResourceAsStream("/" + name)) {
                Files.copy(Objects.requireNonNull(resource), directory.resolve(name));
            }
        }
        plugin = mock(GensouMarket.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getResource("config.yml")).thenAnswer(ignored -> getClass().getResourceAsStream("/config.yml"));
        when(plugin.getConfig()).thenAnswer(ignored -> YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile()));
        config = new ConfigManager(plugin);
        config.load();
        when(plugin.getConfigManager()).thenReturn(config);
        player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(true);
        shop = mock(ShopManager.class);
        recycle = mock(RecycleManager.class);
        when(plugin.getShopManager()).thenReturn(shop);
        when(plugin.getRecycleManager()).thenReturn(recycle);
        var shopItems = new LinkedHashMap<String, ShopItem>();
        shopItems.put("diamond", new ShopItem("diamond", Material.DIAMOND, 1000));
        when(shop.getShopItems()).thenReturn(shopItems);
        var recycleItems = new LinkedHashMap<String, RecycleItem>();
        recycleItems.put("diamond", new RecycleItem("diamond", Material.DIAMOND, 500));
        when(recycle.getRecycleItems()).thenReturn(recycleItems);
        commands = new CommandManager(plugin);
        instance = mockStatic(GensouMarket.class);
        instance.when(GensouMarket::getInstance).thenReturn(plugin);
    }

    @AfterEach void close() { instance.close(); }

    @Test void commandsCreateTuneAssignDetachAndDeleteWithoutDeletingTheCommodity() {
        run("group", "create", "farm");
        run("group", "set", "farm", "recycle", "cycle-amplitude", "0.1");
        run("group", "add", "farm", "recycle", "diamond");
        assertEquals(0.1, config.getPricingGroupSection("farm").getDouble("recycle.cycle-amplitude"));
        assertEquals("farm", config.getRecycleItemPricingGroup("diamond"));
        config.reload();
        assertEquals("farm", config.getRecycleItemPricingGroup("diamond"));
        run("group", "remove", "farm", "recycle", "diamond");
        assertNull(config.getRecycleItemPricingGroup("diamond"));
        var saved = YamlConfiguration.loadConfiguration(directory.resolve("recycle.yml").toFile());
        assertEquals(500, saved.getDouble("items.diamond.recycle-price"));
        verify(recycle, never()).removeItem(anyString());
        run("group", "delete", "farm");
        assertFalse(Files.exists(directory.resolve("pricing-groups/farm.yml")));
    }

    @Test void groupPermissionIsRequiredForBothChangesAndCompletion() {
        when(player.hasPermission(PricingGroupCommand.PERMISSION)).thenReturn(false);
        run("group", "create", "farm");
        assertNull(config.getPricingGroupSection("farm"));
        assertTrue(commands.onTabComplete(player, mock(Command.class), "gmarket", new String[]{"group", ""}).isEmpty());
    }

    @Test void editingMembershipAlsoRequiresTheCorrespondingCommodityPermission() throws Exception {
        config.getPricingGroups().create("farm");
        when(player.hasPermission("gensoumarket.admin.recycle.edit")).thenReturn(false);
        run("group", "add", "farm", "recycle", "diamond");
        assertNull(config.getRecycleItemPricingGroup("diamond"));
        assertTrue(commands.onTabComplete(player, mock(Command.class), "gmarket",
                new String[]{"group", "add", "farm", "recycle", ""}).isEmpty());
    }

    @Test void disablingGroupsBlocksManagementAndHidesCommands() throws Exception {
        var global = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        global.set("dynamic-pricing.groups-enabled", false);
        global.save(directory.resolve("config.yml").toFile());
        config.reload();
        run("group", "create", "farm");
        assertFalse(Files.exists(directory.resolve("pricing-groups/farm.yml")));
        assertFalse(commands.onTabComplete(player, mock(Command.class), "gmarket", new String[]{""}).contains("group"));
    }

    @Test void groupWithMembersCannotBeDeletedAndRemovingFromAnotherGroupIsRejected() throws Exception {
        config.getPricingGroups().create("farm");
        config.getPricingGroups().create("rare");
        run("group", "add", "farm", "shop", "diamond");
        run("group", "delete", "farm");
        assertTrue(Files.exists(directory.resolve("pricing-groups/farm.yml")));
        run("group", "remove", "rare", "shop", "diamond");
        assertEquals("farm", config.getShopItemPricingGroup("diamond"));
    }

    @Test void groupHandRegistrationPassesTheCompleteHeldStackAndRejectsNonFinitePrices() throws Exception {
        config.getPricingGroups().create("farm");
        ItemStack hand = hand();
        when(recycle.addItem("custom", hand, 12, "farm")).thenReturn(true);
        run("group", "add", "farm", "recycle", "custom", "12");
        verify(recycle).addItem("custom", hand, 12, "farm");
        run("group", "add", "farm", "recycle", "invalid", "NaN");
        verify(recycle, never()).addItem(eq("invalid"), any(ItemStack.class), anyDouble(), any());
        verify(player.getInventory(), never()).setItemInMainHand(any());
        verify(hand, never()).setAmount(anyInt());
    }

    @Test void standaloneAddhandWorksWithoutGroupingAndChecksOptionalGroupPermission() throws Exception {
        ItemStack hand = hand();
        when(shop.addItem("custom", hand, 50, null)).thenReturn(true);
        run("shop", "addhand", "custom", "50");
        verify(shop).addItem("custom", hand, 50, null);
        config.getPricingGroups().create("farm");
        when(player.hasPermission(PricingGroupCommand.PERMISSION)).thenReturn(false);
        run("shop", "addhand", "grouped", "50", "farm");
        verify(shop, never()).addItem(eq("grouped"), any(ItemStack.class), anyDouble(), any());
    }

    @Test void existingHandAddAlsoPassesTheItemDataAndTabIncludesGroupParameters() throws Exception {
        ItemStack hand = hand();
        run("shop", "add", "10");
        verify(shop).addItem("paper", hand, 10, null);
        config.getPricingGroups().create("farm");
        assertTrue(commands.onTabComplete(player, mock(Command.class), "gmarket",
                new String[]{"group", "set", "farm", "recycle", "cycle-"}).contains("cycle-amplitude"));
        assertEquals(List.of("farm"), commands.onTabComplete(player, mock(Command.class), "gmarket",
                new String[]{"shop", "addhand", "new", "10", "f"}));
    }

    private ItemStack hand() {
        var inventory = mock(PlayerInventory.class);
        var hand = mock(ItemStack.class);
        var material = mock(Material.class);
        when(material.name()).thenReturn("PAPER");
        when(material.isAir()).thenReturn(false);
        when(hand.getType()).thenReturn(material);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(hand);
        return hand;
    }

    private void run(String... args) {
        assertTrue(commands.onCommand(player, mock(Command.class), "gmarket", args));
    }
}
