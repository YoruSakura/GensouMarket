package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.trade.TradeManager.TradeSession;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TradeGui {

    private TradeGui() {}

    // 自己的物品区 slots (col 0-3, row 0-4)
    public static final int[] MY_ITEM_SLOTS = {
        0, 1, 2, 3,
        9, 10, 11, 12,
        18, 19, 20, 21,
        27, 28, 29, 30,
        36, 37, 38, 39
    };

    // 对方的物品区 slots (col 5-8, row 0-4)
    public static final int[] THEIR_ITEM_SLOTS = {
        5, 6, 7, 8,
        14, 15, 16, 17,
        23, 24, 25, 26,
        32, 33, 34, 35,
        41, 42, 43, 44
    };

    // 分隔栏 slots (col 4, row 0-5)
    public static final int[] SEPARATOR_SLOTS = {4, 13, 22, 31, 40, 49};

    // 底栏按钮
    public static final int SLOT_MY_STATUS = 45;
    public static final int SLOT_CONFIRM = 47;
    public static final int SLOT_CANCEL = 51;
    public static final int SLOT_THEIR_STATUS = 53;
    public static final int[] FILLER_SLOTS = {46, 48, 50, 52};

    private static final Set<Integer> MY_ITEM_SLOT_SET = new java.util.HashSet<>();
    private static final Set<Integer> THEIR_ITEM_SLOT_SET = new java.util.HashSet<>();
    private static final Set<Integer> READ_ONLY_SLOTS = new java.util.HashSet<>();

    static {
        for (int s : MY_ITEM_SLOTS) MY_ITEM_SLOT_SET.add(s);
        for (int s : THEIR_ITEM_SLOTS) THEIR_ITEM_SLOT_SET.add(s);
        // 只读区域: 对方物品区 + 分隔栏 + 底栏所有
        for (int s : THEIR_ITEM_SLOTS) READ_ONLY_SLOTS.add(s);
        for (int s : SEPARATOR_SLOTS) READ_ONLY_SLOTS.add(s);
        READ_ONLY_SLOTS.add(SLOT_MY_STATUS);
        READ_ONLY_SLOTS.add(SLOT_CONFIRM);
        READ_ONLY_SLOTS.add(SLOT_CANCEL);
        READ_ONLY_SLOTS.add(SLOT_THEIR_STATUS);
        for (int s : FILLER_SLOTS) READ_ONLY_SLOTS.add(s);
    }

    public static boolean isMyItemSlot(int slot) {
        return MY_ITEM_SLOT_SET.contains(slot);
    }

    public static boolean isReadOnlySlot(int slot) {
        return READ_ONLY_SLOTS.contains(slot);
    }

    /**
     * 为玩家打开交易 GUI
     */
    public static void openTradeGui(GensouMarket plugin, Player player, TradeSession session) {
        UUID uuid = player.getUniqueId();
        String otherName = session.getOtherName(uuid);

        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.TRADE);
        Inventory inv = Bukkit.createInventory(holder, 54,
                ChatColor.GOLD + "交易 - " + ChatColor.WHITE + otherName);
        holder.setInventory(inv);

        // 分隔栏
        ItemStack separator = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot : SEPARATOR_SLOTS) {
            inv.setItem(slot, separator);
        }

        // 底栏填充
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot : FILLER_SLOTS) {
            inv.setItem(slot, filler);
        }

        // 底栏按钮
        refreshStatusBar(inv, session, uuid);

        // 注册 inventory 到 session
        if (session.isPlayerA(uuid)) {
            session.setPlayerAInventory(inv);
        } else {
            session.setPlayerBInventory(inv);
        }

        player.openInventory(inv);
    }

    /**
     * 刷新对方物品区的展示
     */
    public static void refreshOpponentItems(Inventory inv, TradeSession session, UUID viewerUuid) {
        ItemStack[] opponentItems = session.getOpponentItems(viewerUuid);
        for (int i = 0; i < THEIR_ITEM_SLOTS.length; i++) {
            inv.setItem(THEIR_ITEM_SLOTS[i], opponentItems[i] != null ? opponentItems[i].clone() : null);
        }
    }

    /**
     * 刷新底栏确认状态
     */
    public static void refreshStatusBar(Inventory inv, TradeSession session, UUID viewerUuid) {
        boolean myConfirmed = session.isConfirmed(viewerUuid);
        UUID otherUuid = session.getOtherUuid(viewerUuid);
        boolean theirConfirmed = session.isConfirmed(otherUuid);
        String otherName = session.getOtherName(viewerUuid);

        // 己方状态灯
        if (myConfirmed) {
            inv.setItem(SLOT_MY_STATUS, createItem(Material.LIME_DYE, "&a你: 已确认"));
        } else {
            inv.setItem(SLOT_MY_STATUS, createItem(Material.GRAY_DYE, "&7你: 未确认"));
        }

        // 确认按钮
        if (myConfirmed) {
            inv.setItem(SLOT_CONFIRM, createItem(Material.YELLOW_STAINED_GLASS_PANE,
                    "&e&l等待对方确认", "&7你已确认，等待对方..."));
        } else {
            inv.setItem(SLOT_CONFIRM, createItem(Material.LIME_STAINED_GLASS_PANE,
                    "&a&l确认交易", "&7点击确认你的交易物品"));
        }

        // 取消按钮
        inv.setItem(SLOT_CANCEL, createItem(Material.RED_STAINED_GLASS_PANE,
                "&c&l取消交易", "&7点击取消交易"));

        // 对方状态灯
        if (theirConfirmed) {
            inv.setItem(SLOT_THEIR_STATUS, createItem(Material.LIME_DYE, "&a" + otherName + ": 已确认"));
        } else {
            inv.setItem(SLOT_THEIR_STATUS, createItem(Material.GRAY_DYE, "&7" + otherName + ": 未确认"));
        }
    }

    /**
     * 找到自己物品区中第一个空位的 slot，没有则返回 -1
     */
    public static int findFirstEmptyMySlot(Inventory inv) {
        for (int slot : MY_ITEM_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * 从 GUI 的自己物品区读取物品数组
     */
    public static ItemStack[] readMyItems(Inventory inv) {
        ItemStack[] items = new ItemStack[MY_ITEM_SLOTS.length];
        for (int i = 0; i < MY_ITEM_SLOTS.length; i++) {
            ItemStack item = inv.getItem(MY_ITEM_SLOTS[i]);
            items[i] = (item != null && !item.getType().isAir()) ? item.clone() : null;
        }
        return items;
    }

    private static ItemStack createItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MessageUtil.color(name));
            if (loreLines.length > 0) {
                List<String> lore = new ArrayList<>();
                for (String line : loreLines) {
                    lore.add(MessageUtil.color(line));
                }
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
