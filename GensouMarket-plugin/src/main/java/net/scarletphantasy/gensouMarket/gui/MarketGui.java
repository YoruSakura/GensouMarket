package net.scarletphantasy.gensouMarket.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.bridge.ViewSessionRegistry;
import net.scarletphantasy.gensouMarket.config.ConfigManager;
import net.scarletphantasy.gensouMarket.model.MarketListing;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class MarketGui {

    private static final int PAGE_SIZE = 45;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd HH:mm");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private MarketGui() {}

    @SuppressWarnings("unused")
    public static void openMainMenu(GensouMarket plugin, Player player) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.MAIN_MENU);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("幻想集市", NamedTextColor.GOLD));
        holder.setInventory(inv);

        ConfigManager cm = plugin.getConfigManager();
        if (cm.isPersonalShopEnabled()) {
            inv.setItem(4, createMenuItem(Material.CHEST,
                    "&b&l个人商店", "&7查看自己或指定玩家的在售物品", "&7点击打开自己的个人商店"));
        }
        if (cm.isMarketEnabled()) {
            inv.setItem(10, createMenuItem(Material.ENDER_CHEST,
                    "&6&l全球市场", "&7浏览玩家上架的物品", "&7点击打开"));
        }
        if (cm.isAuctionEnabled()) {
            inv.setItem(12, createMenuItem(Material.GOLDEN_APPLE,
                    "&e&l拍卖行", "&7查看正在进行的拍卖", "&7点击打开"));
        }
        if (cm.isShopEnabled()) {
            inv.setItem(14, createMenuItem(Material.EMERALD,
                    "&a&l服务器商店", "&7从服务器购买物品", "&7价格固定", "&7点击打开"));
        }
        if (cm.isRecycleEnabled()) {
            inv.setItem(16, createMenuItem(Material.HOPPER,
                    "&c&l回收站", "&7将物品卖给服务器", "&7价格随供需变动", "&7点击打开"));
        }

        ItemStack filler = createMenuItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        player.openInventory(inv);
    }

    public static void openMarketBrowse(GensouMarket plugin, Player player, List<MarketListing> listings, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.MARKET_BROWSE);
        holder.setData("page", page);
        holder.setData("listings", listings);

        int totalPages = Math.max(1, (int) Math.ceil((double) listings.size() / PAGE_SIZE));
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("全球市场 ", NamedTextColor.GOLD)
                        .append(Component.text("(" + (page + 1) + "/" + totalPages + ")", NamedTextColor.GRAY)));
        holder.setInventory(inv);

        fillListingsPage(inv, player, listings, page, plugin.getConfigManager().isDebug());

        player.openInventory(inv);
        ViewSessionRegistry registry = plugin.getViewSessionRegistry();
        if (registry != null) {
            registry.register(player.getUniqueId(), GuiHolder.GuiType.MARKET_BROWSE, 0);
        }
    }

    public static void openPersonalShop(GensouMarket plugin, Player player, UUID sellerUuid, String sellerName,
                                        List<MarketListing> listings, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.PERSONAL_SHOP);
        holder.setData("page", page);
        holder.setData("listings", listings);
        holder.setData("sellerUuid", sellerUuid);
        holder.setData("sellerName", sellerName);

        int totalPages = Math.max(1, (int) Math.ceil((double) listings.size() / PAGE_SIZE));
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text(sellerName + " 的个人商店 ", NamedTextColor.AQUA)
                        .append(Component.text("(" + (page + 1) + "/" + totalPages + ")", NamedTextColor.GRAY)));
        holder.setInventory(inv);

        fillListingsPage(inv, player, listings, page, plugin.getConfigManager().isDebug());

        player.openInventory(inv);
        ViewSessionRegistry registry = plugin.getViewSessionRegistry();
        if (registry != null) {
            registry.registerPersonalShop(player.getUniqueId(), sellerUuid);
        }
    }

    private static void fillListingsPage(Inventory inv, Player player, List<MarketListing> listings,
                                         int page, boolean debug) {
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, listings.size());

        for (int i = start; i < end; i++) {
            MarketListing listing = listings.get(i);
            ItemStack display = listing.getItemStack() != null ? listing.getItemStack().clone() : new ItemStack(Material.BARRIER);
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(LEGACY.deserialize("&6价格: &e" + MessageUtil.formatMoney(listing.getPrice())));
                lore.add(LEGACY.deserialize("&7卖家: &f" + listing.getSellerName()));
                lore.add(LEGACY.deserialize("&7上架时间: &f" + DATE_FORMAT.format(new Date(listing.getListTime()))));
                lore.add(LEGACY.deserialize("&7ID: &f#" + listing.getId()));
                lore.add(Component.empty());
                if (listing.getSellerUuid().equals(player.getUniqueId())) {
                    if (debug) {
                        lore.add(LEGACY.deserialize("&a左键点击购买 &7(调试模式)"));
                    }
                    lore.add(LEGACY.deserialize("&c右键点击下架"));
                } else {
                    lore.add(LEGACY.deserialize("&a左键点击购买"));
                }
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(i - start, display);
        }

        ItemStack filler = createMenuItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        int totalPages = Math.max(1, (int) Math.ceil((double) listings.size() / PAGE_SIZE));
        if (page > 0) {
            inv.setItem(45, createMenuItem(Material.ARROW, "&a上一页"));
        }
        inv.setItem(49, createMenuItem(Material.BARRIER, "&c返回主菜单"));
        if (page < totalPages - 1) {
            inv.setItem(53, createMenuItem(Material.ARROW, "&a下一页"));
        }
    }

    static ItemStack createMenuItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name));
            if (loreLines.length > 0) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) {
                    lore.add(LEGACY.deserialize(line));
                }
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
