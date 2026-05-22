package net.scarletphantasy.gensouMarket.listener;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

public class PlayerListener implements Listener {

    private final GensouMarket plugin;

    public PlayerListener(GensouMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 跨服模式：任意玩家加入时都补发一次 HELLO，避免代理端重启后注册状态丢失
        if (plugin.isClusterEnabled()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getProxyBridge().sendHello();
                }
            }, 5L);
        }

        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            List<MailEntry> mail = plugin.getStorage().getPlayerMail(player.getUniqueId());
            if (plugin.getMailNotificationService() != null) {
                plugin.getMailNotificationService().remember(player.getUniqueId(), mail);
            }
            if (mail.isEmpty()) return;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                MessageUtil.send(player, "&e你有 &a" + mail.size() +
                        " &e件待领取的物品/金币！使用 &a/gmarket collect &e领取");
            });
        }, 40L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.getMailNotificationService() != null) {
            plugin.getMailNotificationService().forget(player.getUniqueId());
        }
        if (plugin.getTradeManager() != null) {
            plugin.getTradeManager().handlePlayerQuit(player.getUniqueId());
        }
    }
}
