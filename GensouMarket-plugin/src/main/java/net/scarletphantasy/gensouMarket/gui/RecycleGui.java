package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.shop.MarketFluctuation;
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
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RecycleGui {

    private static final int PAGE_SIZE = 45;
    private static final long REFRESH_INTERVAL_TICKS = 20L;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private static final Map<UUID, BukkitTask> refreshTasks = new ConcurrentHashMap<>();

    private RecycleGui() {}

    public static void openRecycle(GensouMarket plugin, Player player, int page) {
        Map<String, RecycleItem> items = plugin.getRecycleManager().getRecycleItems();
        List<RecycleItem> itemList = new ArrayList<>(items.values());

        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.RECYCLE);
        holder.setData("page", page);
        holder.setData("items", itemList);

        int totalPages = Math.max(1, (int) Math.ceil((double) itemList.size() / PAGE_SIZE));
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("回收站 ", NamedTextColor.RED)
                        .append(Component.text("(" + (page + 1) + "/" + totalPages + ")", NamedTextColor.GRAY)));
        holder.setInventory(inv);

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, itemList.size());

        MarketFluctuation fluctuation = plugin.getRecycleManager().getMarketFluctuation();
        long cycleMillis = plugin.getConfigManager().getFluctuationCycleMinutes() * 60L * 1000L;
        long now = System.currentTimeMillis();
        for (int i = start; i < end; i++) {
            inv.setItem(i - start, buildDisplayItem(itemList.get(i), fluctuation, now, cycleMillis));
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
        startRefreshTask(plugin, player, inv, itemList, start, end);
    }

    static ItemStack buildDisplayItem(RecycleItem recycleItem, MarketFluctuation fluctuation, long now, long cycleMillis) {
        ItemStack display = new ItemStack(recycleItem.getMaterial());
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.displayName(ItemNameUtil.getLocalizedName(recycleItem.getMaterial()));
            meta.lore(buildLore(recycleItem, fluctuation, now, cycleMillis));
            display.setItemMeta(meta);
        }
        return display;
    }

    private static List<Component> buildLore(RecycleItem recycleItem, MarketFluctuation fluctuation, long now, long cycleMillis) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(LEGACY.deserialize("&7基准价格: &e" + MessageUtil.formatMoney(recycleItem.getBaseRecyclePrice())));

        double fluc = fluctuation.calculate(recycleItem.getId(), now);
        double currentPrice = recycleItem.getCurrentRecyclePrice(fluc);

        if (recycleItem.getSnapshotPrice() <= 0 || now - recycleItem.getSnapshotTime() >= cycleMillis) {
            recycleItem.setSnapshotPrice(currentPrice);
            recycleItem.setSnapshotTime(now);
        }
        double changePercent = 0;
        if (recycleItem.getSnapshotPrice() > 0) {
            changePercent = (currentPrice - recycleItem.getSnapshotPrice()) / recycleItem.getSnapshotPrice() * 100.0;
        }

        String trend = computeTrend(changePercent);
        lore.add(LEGACY.deserialize(trend));
        lore.add(LEGACY.deserialize("&7当前回收价: &e" + MessageUtil.formatMoney(currentPrice)));
        lore.add(Component.empty());
        lore.add(LEGACY.deserialize("&e左键 &7回收1个"));
        lore.add(LEGACY.deserialize("&e右键 &7回收1组"));
        return lore;
    }

    private static String computeTrend(double changePercent) {
        if (changePercent > 0.1) {
            return "&c↑ +" + String.format("%.1f", changePercent) + "%";
        } else if (changePercent < -0.1) {
            return "&a↓ " + String.format("%.1f", changePercent) + "%";
        } else {
            return "&7— 持平";
        }
    }

    private static void startRefreshTask(GensouMarket plugin, Player player, Inventory inv,
                                         List<RecycleItem> itemList, int start, int end) {
        cancelRefreshTask(player.getUniqueId());

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inv) {
                cancelRefreshTask(player.getUniqueId());
                return;
            }

            MarketFluctuation fluctuation = plugin.getRecycleManager().getMarketFluctuation();
            long cyclMs = plugin.getConfigManager().getFluctuationCycleMinutes() * 60L * 1000L;
            long now = System.currentTimeMillis();
            for (int i = start; i < end; i++) {
                int slot = i - start;
                ItemStack existing = inv.getItem(slot);
                if (existing == null || existing.getType().isAir()) continue;

                ItemMeta meta = existing.getItemMeta();
                if (meta != null) {
                    meta.lore(buildLore(itemList.get(i), fluctuation, now, cyclMs));
                    existing.setItemMeta(meta);
                }
            }
        }, REFRESH_INTERVAL_TICKS, REFRESH_INTERVAL_TICKS);

        refreshTasks.put(player.getUniqueId(), task);
    }

    public static void cancelRefreshTask(UUID playerId) {
        BukkitTask task = refreshTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }
}
