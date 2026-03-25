package net.scarletphantasy.gensouMarket.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import net.scarletphantasy.gensouMarket.GensouMarket;

public final class MessageUtil {

    private MessageUtil() {}

    public static String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
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
}
