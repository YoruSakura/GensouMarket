package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.shop.PriceResult;
import net.scarletphantasy.gensouMarket.shop.ShopManager;
import net.scarletphantasy.gensouMarket.util.ItemNameUtil;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ShopGui {

    private static final int PAGE_SIZE = 45;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private ShopGui() {}

    public static void openShop(GensouMarket plugin, Player player, int page) {
        Map<String, ShopItem> items = plugin.getShopManager().getShopItems();
        List<ShopItem> itemList = new ArrayList<>(items.values());

        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.SHOP);
        holder.setData("page", page);
        holder.setData("items", itemList);

        int totalPages = Math.max(1, (int) Math.ceil((double) itemList.size() / PAGE_SIZE));
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("服务器商店 ", NamedTextColor.GREEN)
                        .append(Component.text("(" + (page + 1) + "/" + totalPages + ")", NamedTextColor.GRAY)));
        holder.setInventory(inv);

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, itemList.size());

        ShopManager shopManager = plugin.getShopManager();
        for (int i = start; i < end; i++) {
            ShopItem shopItem = itemList.get(i);
            PriceResult result = shopManager.computeCurrentPriceResult(shopItem);
            double price = Math.max(result.unitPrice(), 0.01);
            int stock = shopManager.computeAvailableStock(shopItem);
            boolean outOfStock = stock <= 0;

            ItemStack display = new ItemStack(shopItem.getMaterial());
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.displayName(ItemNameUtil.getLocalizedName(shopItem.getMaterial()));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                lore.addAll(buildModeLore(shopItem, shopManager, result, price, stock, outOfStock));
                lore.add(Component.empty());
                if (outOfStock) {
                    lore.add(LEGACY.deserialize("&c&l缺货中"));
                } else {
                    lore.add(LEGACY.deserialize("&a左键购买1个"));
                    lore.add(LEGACY.deserialize("&aShift+左键购买64个"));
                }
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(i - start, display);
        }

        ItemStack filler = MarketGui.createMenuItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        if (page > 0) {
            inv.setItem(45, MarketGui.createMenuItem(Material.ARROW, "&a上一页"));
        }
        inv.setItem(49, MarketGui.createMenuItem(Material.BARRIER, "&c返回主菜单"));
        if (page < totalPages - 1) {
            inv.setItem(53, MarketGui.createMenuItem(Material.ARROW, "&a下一页"));
        }

        player.openInventory(inv);
    }

    private static List<Component> buildModeLore(ShopItem shopItem, ShopManager shopManager,
                                                 PriceResult result, double price, int stock, boolean outOfStock) {
        List<Component> lore = new ArrayList<>();
        String priceStr = MessageUtil.formatMoney(price);

        if (shopItem.isFixedUnlimited()) {
            lore.add(LEGACY.deserialize("&a购买价格: &e" + priceStr));
            lore.add(LEGACY.deserialize("&7库存: &f无限"));
            lore.add(LEGACY.deserialize("&7售价来源: &f基础价格"));
        } else if (shopItem.isFixedLimited()) {
            lore.add(LEGACY.deserialize("&a购买价格: &e" + priceStr));
            if (outOfStock) {
                lore.add(LEGACY.deserialize("&7库存: &c0 (缺货)"));
            } else {
                lore.add(LEGACY.deserialize("&7库存: &f" + stock));
            }
            lore.add(LEGACY.deserialize("&7售价来源: &f库存"));
        } else if (shopItem.isRecycled()) {
            lore.add(LEGACY.deserialize("&a当前售价: &e" + priceStr));
            RecycleItem source = shopManager.resolveRecycleSource(shopItem);
            String sourceName = source != null ? source.getId() : "?";
            lore.add(LEGACY.deserialize("&7回流来源: &f" + sourceName));
            if (outOfStock) {
                lore.add(LEGACY.deserialize("&7回流库存: &c0 (缺货)"));
            } else {
                lore.add(LEGACY.deserialize("&7回流库存: &f" + stock));
            }
            lore.add(LEGACY.deserialize("&7售价来源: &f回流库存"));
        }
        return lore;
    }
}
