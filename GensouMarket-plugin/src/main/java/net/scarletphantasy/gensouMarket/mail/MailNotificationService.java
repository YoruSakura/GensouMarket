package net.scarletphantasy.gensouMarket.mail;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.model.MailEntry;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback mailbox notifier for cluster mode.
 *
 * Bukkit plugin messages can only be sent through an online player on the source
 * server. When a source server writes mail while empty, the target player's
 * server must discover the new mail from shared storage instead.
 */
public class MailNotificationService {

    private static final long POLL_INITIAL_DELAY_TICKS = 100L;
    private static final long POLL_PERIOD_TICKS = 100L;
    private static final long EXTERNAL_NOTICE_SUPPRESS_MS = 15000L;
    private static final String DEFAULT_MAIL_NOTIFY =
            "&e你有新的待领取物品/金币！使用 &a/gmarket collect &e领取";

    private final GensouMarket plugin;
    private final Map<UUID, Set<Integer>> knownMailIds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastExternalNoticeAt = new ConcurrentHashMap<>();
    private BukkitTask pollTask;

    public MailNotificationService(GensouMarket plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (pollTask != null) return;
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pollOnlinePlayers,
                POLL_INITIAL_DELAY_TICKS, POLL_PERIOD_TICKS);
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        knownMailIds.clear();
        lastExternalNoticeAt.clear();
    }

    public void remember(UUID playerUuid, List<MailEntry> mail) {
        knownMailIds.put(playerUuid, toIdSet(mail));
    }

    public void recordExternalNotice(UUID playerUuid) {
        lastExternalNoticeAt.put(playerUuid, System.currentTimeMillis());
    }

    public void rememberCurrentMailAsync(UUID playerUuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                knownMailIds.put(playerUuid, loadMailIds(playerUuid)));
    }

    public void forget(UUID playerUuid) {
        knownMailIds.remove(playerUuid);
    }

    private void pollOnlinePlayers() {
        Set<UUID> onlineUuids = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlineUuids.add(player.getUniqueId());
        }

        knownMailIds.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));
        lastExternalNoticeAt.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));
        if (onlineUuids.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, Set<Integer>> currentByPlayer = new HashMap<>();
            Set<UUID> playersToNotify = new HashSet<>();

            for (UUID uuid : onlineUuids) {
                Set<Integer> current = loadMailIds(uuid);
                Set<Integer> known = knownMailIds.get(uuid);
                currentByPlayer.put(uuid, current);

                if (shouldNotify(uuid, current, known)) {
                    playersToNotify.add(uuid);
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                currentByPlayer.forEach(knownMailIds::put);
                for (UUID uuid : playersToNotify) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        MessageUtil.send(player, DEFAULT_MAIL_NOTIFY);
                    }
                }
            });
        });
    }

    private Set<Integer> loadMailIds(UUID playerUuid) {
        return toIdSet(plugin.getStorage().getPlayerMail(playerUuid));
    }

    private Set<Integer> toIdSet(List<MailEntry> mail) {
        Set<Integer> ids = new HashSet<>();
        for (MailEntry entry : mail) {
            ids.add(entry.getId());
        }
        return ids;
    }

    private boolean hasNewMail(Set<Integer> current, Set<Integer> known) {
        for (Integer id : current) {
            if (!known.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldNotify(UUID uuid, Set<Integer> current, Set<Integer> known) {
        if (current.isEmpty() || recentlyExternallyNotified(uuid)) {
            return false;
        }
        return known == null || hasNewMail(current, known);
    }

    private boolean recentlyExternallyNotified(UUID uuid) {
        Long timestamp = lastExternalNoticeAt.get(uuid);
        return timestamp != null && System.currentTimeMillis() - timestamp <= EXTERNAL_NOTICE_SUPPRESS_MS;
    }
}
