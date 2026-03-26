package net.scarletphantasy.gensouMarket.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.scarletphantasy.gensouMarket.GensouMarket;

import java.util.HashMap;

public final class MessageUtil {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    private MessageUtil() {}

    public static String color(String message) {
        return SECTION.serialize(AMPERSAND.deserialize(message));
    }

    public static void send(CommandSender sender, String message) {
        String prefix = GensouMarket.getInstance().getConfigManager().getPrefix();
        sender.sendMessage(color(prefix + message));
    }

    public static void send(CommandSender sender, Component message) {
        String rawPrefix = GensouMarket.getInstance().getConfigManager().getPrefix();
        Component prefix = LegacyComponentSerializer.legacyAmpersand().deserialize(rawPrefix);
        sender.sendMessage(prefix.append(message));
    }

    public static void sendNoPrefix(CommandSender sender, String message) {
        sender.sendMessage(color(message));
    }

    public static void sendNoPrefix(CommandSender sender, Component message) {
        sender.sendMessage(message);
    }

    public static String formatMoney(double amount) {
        if (amount == (long) amount) {
            return String.format("%,d", (long) amount);
        }
        return String.format("%,.2f", amount);
    }

    /**
     * 检查玩家背包是否有空位。
     */
    public static boolean hasInventorySpace(Player player) {
        return player.getInventory().firstEmpty() != -1;
    }

    /**
     * 给玩家物品，背包满则掉落并提示。
     */
    public static void giveItem(Player player, ItemStack item) {
        if (item == null) return;
        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            for (ItemStack drop : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            send(player, "&e背包已满，物品已掉落在你脚下！");
        }
    }
}
