package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.config.PricingConfigResolver;
import net.scarletphantasy.gensouMarket.config.RecyclePricingConfig;
import net.scarletphantasy.gensouMarket.economy.EconomySnapshotService;
import net.scarletphantasy.gensouMarket.model.RecycleItem;
import net.scarletphantasy.gensouMarket.shop.PriceContext;
import net.scarletphantasy.gensouMarket.shop.PriceEngine;
import net.scarletphantasy.gensouMarket.shop.PriceResult;
import net.scarletphantasy.gensouMarket.shop.PressureWindowManager;
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

        for (int i = start; i < end; i++) {
            inv.setItem(i - start, buildDisplayItem(plugin, player, itemList.get(i)));
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

    /**
     * 构造回收价格上下文（v1.1.1）。供同包 GuiListener 锁价校验使用。
     */
    public static PriceContext buildRecyclePriceContext(GensouMarket plugin, RecycleItem item) {
        long now = System.currentTimeMillis();
        PriceEngine engine = plugin.getRecycleManager().getPriceEngine();
        PricingConfigResolver resolver = engine.getConfigResolver();
        RecyclePricingConfig rcfg = resolver.resolveForRecycleItem(item.getId());
        PressureWindowManager pw = plugin.getRecycleManager().getPressureWindow();
        int activeVolume = pw.getActiveVolume(item.getId(), rcfg, now);

        EconomySnapshotService economy = plugin.getEconomySnapshotService();
        double econRecycleMultiplier = economy != null ? economy.getRecycleMultiplier() : 1.0;

        return new PriceContext(now, econRecycleMultiplier, 1.0, activeVolume,
                item.getRecycledStock(), 0, 0);
    }

    static ItemStack buildDisplayItem(GensouMarket plugin, Player player, RecycleItem recycleItem) {
        ItemStack display = new ItemStack(recycleItem.getMaterial());
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.displayName(ItemNameUtil.getLocalizedName(recycleItem.getMaterial()));
            meta.lore(buildLore(plugin, player, recycleItem));
            display.setItemMeta(meta);
        }
        return display;
    }

    private static List<Component> buildLore(GensouMarket plugin, Player player, RecycleItem recycleItem) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        PriceContext ctx = buildRecyclePriceContext(plugin, recycleItem);
        PriceEngine engine = plugin.getRecycleManager().getPriceEngine();

        // 单价（当前 activeVolume 下的第一件单价）
        PriceResult singleResult = engine.calculateRecyclePrice(recycleItem, ctx);
        double currentPrice = singleResult.unitPrice();

        lore.add(LEGACY.deserialize("&7基准价格: &e" + MessageUtil.formatMoney(recycleItem.getBaseRecyclePrice())));

        // 价格趋势（基于快照）
        if (recycleItem.getSnapshotPrice() <= 0) {
            recycleItem.setSnapshotPrice(currentPrice);
            recycleItem.setSnapshotTime(ctx.nowMillis());
        }
        double changePercent = 0;
        if (recycleItem.getSnapshotPrice() > 0) {
            changePercent = (currentPrice - recycleItem.getSnapshotPrice()) / recycleItem.getSnapshotPrice() * 100.0;
        }
        lore.add(LEGACY.deserialize(computeTrend(changePercent)));
        lore.add(LEGACY.deserialize("&7当前回收价: &e" + MessageUtil.formatMoney(currentPrice)));

        // 压力信息
        if (singleResult.pressureRatio() > 0.01) {
            int percent = (int) (singleResult.pressureRatio() * 100);
            lore.add(LEGACY.deserialize("&7回收压力: &c" + percent + "%"));
        }

        // 批量预览（玩家背包数量或64）
        int available = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == recycleItem.getMaterial()) {
                available += item.getAmount();
            }
        }
        int batchAmount = Math.min(64, available);
        if (batchAmount > 0) {
            PriceResult batchResult = engine.calculateBatchRecyclePrice(recycleItem, ctx, batchAmount);
            lore.add(LEGACY.deserialize("&7一组(" + batchAmount + ")预计: &e" + MessageUtil.formatMoney(batchResult.totalPrice())
                    + " &7(均&e" + MessageUtil.formatMoney(batchResult.averagePrice()) + "&7)"));
        } else {
            lore.add(LEGACY.deserialize("&7一组(0)预计: &e" + MessageUtil.formatMoney(0)
                    + " &7(均&e" + MessageUtil.formatMoney(0) + "&7)"));
        }

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

            for (int i = start; i < end; i++) {
                int slot = i - start;
                ItemStack existing = inv.getItem(slot);
                if (existing == null || existing.getType().isAir()) continue;

                ItemMeta meta = existing.getItemMeta();
                if (meta != null) {
                    meta.lore(buildLore(plugin, player, itemList.get(i)));
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
