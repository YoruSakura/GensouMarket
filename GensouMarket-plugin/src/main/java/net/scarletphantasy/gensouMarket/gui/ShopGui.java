package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.model.ShopItem;
import net.scarletphantasy.gensouMarket.util.ItemNameUtil;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

    private ShopGui() {}

    public static void openShop(GensouMarket plugin, Player player, int page) {
        Map<String, ShopItem> items = plugin.getShopManager().getShopItems();
        List<ShopItem> itemList = new ArrayList<>(items.values());

        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.SHOP);
        holder.setData("page", page);
        holder.setData("items", itemList);

        int totalPages = Math.max(1, (int) Math.ceil((double) itemList.size() / PAGE_SIZE));
        Inventory inv = Bukkit.createInventory(holder, 54,
                ChatColor.GREEN + "服务器商店 " + ChatColor.GRAY + "(" + (page + 1) + "/" + totalPages + ")");
        holder.setInventory(inv);

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, itemList.size());

        for (int i = start; i < end; i++) {
            ShopItem shopItem = itemList.get(i);
            ItemStack display = new ItemStack(shopItem.getMaterial());
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                meta.displayName(ItemNameUtil.getLocalizedName(shopItem.getMaterial()));
                List<String> lore = new ArrayList<>();
                lore.add("");
                lore.add(MessageUtil.color("&a购买价格: &e" + MessageUtil.formatMoney(shopItem.getCurrentBuyPrice()) + " &7(固定)"));
                lore.add("");
                lore.add(MessageUtil.color("&a左键购买1个"));
                lore.add(MessageUtil.color("&aShift+左键购买64个"));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            inv.setItem(i - start, display);
        }

        // 导航栏
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
}
