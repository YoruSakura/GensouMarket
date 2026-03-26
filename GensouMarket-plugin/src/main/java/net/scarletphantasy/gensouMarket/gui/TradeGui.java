package net.scarletphantasy.gensouMarket.gui;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.trade.TradeManager.TradeSession;
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
import java.util.Set;
import java.util.UUID;

public final class TradeGui {

    private TradeGui() {}

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    public static final int[] MY_ITEM_SLOTS = {
        0, 1, 2, 3,
        9, 10, 11, 12,
        18, 19, 20, 21,
        27, 28, 29, 30,
        36, 37, 38, 39
    };

    public static final int[] THEIR_ITEM_SLOTS = {
        5, 6, 7, 8,
        14, 15, 16, 17,
        23, 24, 25, 26,
        32, 33, 34, 35,
        41, 42, 43, 44
    };

    public static final int[] SEPARATOR_SLOTS = {4, 13, 22, 31, 40, 49};

    public static final int SLOT_MY_STATUS = 45;
    public static final int SLOT_CONFIRM = 47;
    public static final int SLOT_CANCEL = 51;
    public static final int SLOT_THEIR_STATUS = 53;
    public static final int[] FILLER_SLOTS = {46, 48, 50, 52};

    private static final Set<Integer> MY_ITEM_SLOT_SET = new java.util.HashSet<>();
    private static final Set<Integer> READ_ONLY_SLOTS = new java.util.HashSet<>();

    static {
        for (int s : MY_ITEM_SLOTS) MY_ITEM_SLOT_SET.add(s);
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

    public static void openTradeGui(GensouMarket plugin, Player player, TradeSession session) {
        UUID uuid = player.getUniqueId();
        String otherName = session.getOtherName(uuid);

        GuiHolder holder = new GuiHolder(GuiHolder.GuiType.TRADE);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Component.text("交易 - ", NamedTextColor.GOLD)
                        .append(Component.text(otherName, NamedTextColor.WHITE)));
        holder.setInventory(inv);

        ItemStack separator = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot : SEPARATOR_SLOTS) {
            inv.setItem(slot, separator);
        }

        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot : FILLER_SLOTS) {
            inv.setItem(slot, filler);
        }

        refreshStatusBar(inv, session, uuid);

        if (session.isPlayerA(uuid)) {
            session.setPlayerAInventory(inv);
        } else {
            session.setPlayerBInventory(inv);
        }

        player.openInventory(inv);
    }

    public static void refreshOpponentItems(Inventory inv, TradeSession session, UUID viewerUuid) {
        ItemStack[] opponentItems = session.getOpponentItems(viewerUuid);
        for (int i = 0; i < THEIR_ITEM_SLOTS.length; i++) {
            inv.setItem(THEIR_ITEM_SLOTS[i], opponentItems[i] != null ? opponentItems[i].clone() : null);
        }
    }

    public static void refreshStatusBar(Inventory inv, TradeSession session, UUID viewerUuid) {
        boolean myConfirmed = session.isConfirmed(viewerUuid);
        UUID otherUuid = session.getOtherUuid(viewerUuid);
        boolean theirConfirmed = session.isConfirmed(otherUuid);
        String otherName = session.getOtherName(viewerUuid);

        inv.setItem(SLOT_MY_STATUS, myConfirmed
                ? createItem(Material.LIME_DYE, "&a你: 已确认")
                : createItem(Material.GRAY_DYE, "&7你: 未确认"));

        inv.setItem(SLOT_CONFIRM, myConfirmed
                ? createItem(Material.YELLOW_STAINED_GLASS_PANE, "&e&l等待对方确认", "&7你已确认，等待对方...")
                : createItem(Material.LIME_STAINED_GLASS_PANE, "&a&l确认交易", "&7点击确认你的交易物品"));

        inv.setItem(SLOT_CANCEL, createItem(Material.RED_STAINED_GLASS_PANE, "&c&l取消交易", "&7点击取消交易"));

        inv.setItem(SLOT_THEIR_STATUS, theirConfirmed
                ? createItem(Material.LIME_DYE, "&a" + otherName + ": 已确认")
                : createItem(Material.GRAY_DYE, "&7" + otherName + ": 未确认"));
    }

    public static int findFirstEmptyMySlot(Inventory inv) {
        for (int slot : MY_ITEM_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) {
                return slot;
            }
        }
        return -1;
    }

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
