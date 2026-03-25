package net.scarletphantasy.gensouMarket.trade;

import net.scarletphantasy.gensouMarket.GensouMarket;
import net.scarletphantasy.gensouMarket.gui.TradeGui;
import net.scarletphantasy.gensouMarket.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TradeManager {

    private final GensouMarket plugin;

    // 待处理的交易请求: key = 发起者UUID
    private final Map<UUID, TradeRequest> pendingRequests = new ConcurrentHashMap<>();

    // 活跃的交易会话: key = 玩家UUID (双方各一个entry指向同一个session)
    private final Map<UUID, TradeSession> activeSessions = new ConcurrentHashMap<>();

    // 距离检测定时任务
    private BukkitTask distanceCheckTask;

    public TradeManager(GensouMarket plugin) {
        this.plugin = plugin;
    }

    /**
     * 发起交易请求
     */
    public void sendRequest(Player sender, Player target) {
        if (!plugin.getConfigManager().isTradeEnabled()) {
            MessageUtil.send(sender, "&c面对面交易功能已禁用！");
            return;
        }

        if (sender.getUniqueId().equals(target.getUniqueId())) {
            MessageUtil.send(sender, "&c你不能和自己交易！");
            return;
        }

        if (!sender.hasPermission("gensoumarket.trade")) {
            MessageUtil.send(sender, "&c你没有权限进行交易！");
            return;
        }

        if (!target.hasPermission("gensoumarket.trade")) {
            MessageUtil.send(sender, "&c对方没有交易权限！");
            return;
        }

        // 检查双方是否已在交易中
        if (isInTrade(sender.getUniqueId())) {
            MessageUtil.send(sender, "&c你正在进行一笔交易！");
            return;
        }
        if (isInTrade(target.getUniqueId())) {
            MessageUtil.send(sender, "&c对方正在进行一笔交易！");
            return;
        }

        // 检查是否已有待处理请求
        if (hasPendingRequestAsSender(sender.getUniqueId())) {
            MessageUtil.send(sender, "&c你已经发起了一个交易请求！请先取消 (/gmarket trade cancel)");
            return;
        }
        if (hasPendingRequestAsTarget(sender.getUniqueId())) {
            MessageUtil.send(sender, "&c你有一个待处理的交易请求！请先接受或拒绝");
            return;
        }
        if (hasPendingRequestAsSender(target.getUniqueId())) {
            MessageUtil.send(sender, "&c对方已经发起了一个交易请求！");
            return;
        }
        if (hasPendingRequestAsTarget(target.getUniqueId())) {
            MessageUtil.send(sender, "&c对方有一个待处理的交易请求！");
            return;
        }

        // 创建请求
        int timeout = plugin.getConfigManager().getTradeRequestTimeout();
        TradeRequest request = new TradeRequest(sender.getUniqueId(), target.getUniqueId(),
                sender.getName(), target.getName());

        // 设置超时任务
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            TradeRequest req = pendingRequests.remove(sender.getUniqueId());
            if (req != null) {
                Player s = Bukkit.getPlayer(req.getSenderUuid());
                Player t = Bukkit.getPlayer(req.getTargetUuid());
                if (s != null) MessageUtil.send(s, "&e交易请求已超时！");
                if (t != null) MessageUtil.send(t, "&e来自 &f" + req.getSenderName() + " &e的交易请求已超时");
            }
        }, timeout * 20L);
        request.setTimeoutTask(timeoutTask);

        pendingRequests.put(sender.getUniqueId(), request);

        MessageUtil.send(sender, "&a已向 &f" + target.getName() + " &a发起交易请求！等待对方回应...");
        MessageUtil.send(target, "&e玩家 &f" + sender.getName() + " &e向你发起了交易请求！");
        MessageUtil.send(target, "&a蹲下右键点击对方 &7或 &a/gmarket trade accept &7接受");
        MessageUtil.send(target, "&c/gmarket trade deny &7拒绝");
    }

    /**
     * 接受交易请求
     */
    public void acceptRequest(Player acceptor) {
        TradeRequest request = findRequestForTarget(acceptor.getUniqueId());
        if (request == null) {
            MessageUtil.send(acceptor, "&c你没有待处理的交易请求！");
            return;
        }

        Player sender = Bukkit.getPlayer(request.getSenderUuid());
        if (sender == null || !sender.isOnline()) {
            pendingRequests.remove(request.getSenderUuid());
            if (request.getTimeoutTask() != null) request.getTimeoutTask().cancel();
            MessageUtil.send(acceptor, "&c对方已离线，交易请求已取消！");
            return;
        }

        // 距离检查
        double maxDist = plugin.getConfigManager().getTradeMaxDistance();
        if (!sender.getWorld().equals(acceptor.getWorld()) ||
            sender.getLocation().distanceSquared(acceptor.getLocation()) > maxDist * maxDist) {
            MessageUtil.send(acceptor, "&c对方距离过远，无法交易！");
            return;
        }

        // 移除请求
        pendingRequests.remove(request.getSenderUuid());
        if (request.getTimeoutTask() != null) request.getTimeoutTask().cancel();

        // 创建交易会话
        TradeSession session = new TradeSession(
                request.getSenderUuid(), acceptor.getUniqueId(),
                request.getSenderName(), acceptor.getName());

        activeSessions.put(request.getSenderUuid(), session);
        activeSessions.put(acceptor.getUniqueId(), session);

        // 为双方打开交易 GUI
        TradeGui.openTradeGui(plugin, sender, session);
        TradeGui.openTradeGui(plugin, acceptor, session);

        MessageUtil.send(sender, "&a" + acceptor.getName() + " 接受了你的交易请求！");
        MessageUtil.send(acceptor, "&a已接受 " + sender.getName() + " 的交易请求！");

        // 启动距离检测
        startDistanceCheckTask();
    }

    /**
     * 拒绝交易请求
     */
    public void denyRequest(Player denier) {
        TradeRequest request = findRequestForTarget(denier.getUniqueId());
        if (request == null) {
            MessageUtil.send(denier, "&c你没有待处理的交易请求！");
            return;
        }

        pendingRequests.remove(request.getSenderUuid());
        if (request.getTimeoutTask() != null) request.getTimeoutTask().cancel();

        Player sender = Bukkit.getPlayer(request.getSenderUuid());
        if (sender != null) {
            MessageUtil.send(sender, "&c" + denier.getName() + " 拒绝了你的交易请求！");
        }
        MessageUtil.send(denier, "&e已拒绝 " + request.getSenderName() + " 的交易请求");
    }

    /**
     * 取消自己发起的请求
     */
    public void cancelRequest(Player canceller) {
        TradeRequest request = pendingRequests.remove(canceller.getUniqueId());
        if (request == null) {
            MessageUtil.send(canceller, "&c你没有已发起的交易请求！");
            return;
        }

        if (request.getTimeoutTask() != null) request.getTimeoutTask().cancel();

        Player target = Bukkit.getPlayer(request.getTargetUuid());
        if (target != null) {
            MessageUtil.send(target, "&e" + canceller.getName() + " 取消了交易请求");
        }
        MessageUtil.send(canceller, "&e已取消交易请求");
    }

    /**
     * 结束交易会话
     * @param completed true=执行物品交换, false=取消并返还
     */
    public void endSession(TradeSession session, boolean completed) {
        if (session.isEnded()) return;
        session.setEnded(true);

        if (completed) {
            session.executeTrade();
        } else {
            session.returnItems();
        }

        // 从 map 中移除
        activeSessions.remove(session.getPlayerAUuid());
        activeSessions.remove(session.getPlayerBUuid());

        // 延迟 1 tick 关闭双方 GUI（避免递归）
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player a = Bukkit.getPlayer(session.getPlayerAUuid());
            Player b = Bukkit.getPlayer(session.getPlayerBUuid());
            if (a != null && a.isOnline()) a.closeInventory();
            if (b != null && b.isOnline()) b.closeInventory();
        });

        // 无活跃会话时停止距离检测
        if (activeSessions.isEmpty()) {
            stopDistanceCheckTask();
        }
    }

    /**
     * 处理玩家退出
     */
    public void handlePlayerQuit(UUID uuid) {
        // 取消活跃交易
        TradeSession session = activeSessions.get(uuid);
        if (session != null && !session.isEnded()) {
            Player other = Bukkit.getPlayer(session.getOtherUuid(uuid));
            if (other != null) {
                MessageUtil.send(other, "&c对方已离线，交易已取消！");
            }
            endSession(session, false);
        }

        // 取消发起的请求
        TradeRequest sentRequest = pendingRequests.remove(uuid);
        if (sentRequest != null) {
            if (sentRequest.getTimeoutTask() != null) sentRequest.getTimeoutTask().cancel();
            Player target = Bukkit.getPlayer(sentRequest.getTargetUuid());
            if (target != null) {
                MessageUtil.send(target, "&e" + sentRequest.getSenderName() + " 已离线，交易请求已取消");
            }
        }

        // 取消收到的请求
        TradeRequest receivedRequest = findRequestForTarget(uuid);
        if (receivedRequest != null) {
            pendingRequests.remove(receivedRequest.getSenderUuid());
            if (receivedRequest.getTimeoutTask() != null) receivedRequest.getTimeoutTask().cancel();
            Player sender = Bukkit.getPlayer(receivedRequest.getSenderUuid());
            if (sender != null) {
                MessageUtil.send(sender, "&c对方已离线，交易请求已取消！");
            }
        }
    }

    /**
     * 取消所有活跃交易（服务器关闭时）
     */
    public void cancelAllActiveTrades() {
        Set<TradeSession> processed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TradeSession session : activeSessions.values()) {
            if (processed.add(session) && !session.isEnded()) {
                session.setEnded(true);
                session.returnItems();
            }
        }
        activeSessions.clear();
        stopDistanceCheckTask();

        // 取消所有待处理请求的超时任务
        for (TradeRequest req : pendingRequests.values()) {
            if (req.getTimeoutTask() != null) req.getTimeoutTask().cancel();
        }
        pendingRequests.clear();
    }

    public boolean isInTrade(UUID uuid) {
        return activeSessions.containsKey(uuid);
    }

    public TradeSession getSession(UUID uuid) {
        return activeSessions.get(uuid);
    }

    public boolean hasPendingRequestAsSender(UUID uuid) {
        return pendingRequests.containsKey(uuid);
    }

    public boolean hasPendingRequestAsTarget(UUID uuid) {
        return findRequestForTarget(uuid) != null;
    }

    /**
     * 查找指向某目标玩家的请求
     */
    public TradeRequest findRequestForTarget(UUID targetUuid) {
        for (TradeRequest req : pendingRequests.values()) {
            if (req.getTargetUuid().equals(targetUuid)) {
                return req;
            }
        }
        return null;
    }

    /**
     * 查找某玩家发起的指向指定目标的请求
     */
    public TradeRequest findRequestBySenderAndTarget(UUID senderUuid, UUID targetUuid) {
        TradeRequest req = pendingRequests.get(senderUuid);
        if (req != null && req.getTargetUuid().equals(targetUuid)) {
            return req;
        }
        return null;
    }

    private void startDistanceCheckTask() {
        if (distanceCheckTask != null) return;

        int interval = plugin.getConfigManager().getTradeDistanceCheckInterval();
        double maxDist = plugin.getConfigManager().getTradeMaxDistance();
        double maxDistSq = maxDist * maxDist;

        distanceCheckTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<TradeSession> toCancel = new ArrayList<>();
            Set<TradeSession> checked = Collections.newSetFromMap(new IdentityHashMap<>());

            for (TradeSession session : activeSessions.values()) {
                if (!checked.add(session)) continue;
                if (session.isEnded()) continue;

                Player a = Bukkit.getPlayer(session.getPlayerAUuid());
                Player b = Bukkit.getPlayer(session.getPlayerBUuid());

                if (a == null || b == null || !a.isOnline() || !b.isOnline()) {
                    toCancel.add(session);
                    continue;
                }

                if (!a.getWorld().equals(b.getWorld()) ||
                    a.getLocation().distanceSquared(b.getLocation()) > maxDistSq) {
                    toCancel.add(session);
                }
            }

            for (TradeSession session : toCancel) {
                Player a = Bukkit.getPlayer(session.getPlayerAUuid());
                Player b = Bukkit.getPlayer(session.getPlayerBUuid());
                if (a != null) MessageUtil.send(a, "&c交易已取消：双方距离过远！");
                if (b != null) MessageUtil.send(b, "&c交易已取消：双方距离过远！");
                endSession(session, false);
            }

            if (activeSessions.isEmpty()) {
                stopDistanceCheckTask();
            }
        }, interval, interval);
    }

    private void stopDistanceCheckTask() {
        if (distanceCheckTask != null) {
            distanceCheckTask.cancel();
            distanceCheckTask = null;
        }
    }

    // ========== 内部类 ==========

    public static class TradeRequest {
        private final UUID senderUuid;
        private final UUID targetUuid;
        private final String senderName;
        private final String targetName;
        private final long createTime;
        private BukkitTask timeoutTask;

        public TradeRequest(UUID senderUuid, UUID targetUuid, String senderName, String targetName) {
            this.senderUuid = senderUuid;
            this.targetUuid = targetUuid;
            this.senderName = senderName;
            this.targetName = targetName;
            this.createTime = System.currentTimeMillis();
        }

        public UUID getSenderUuid() { return senderUuid; }
        public UUID getTargetUuid() { return targetUuid; }
        public String getSenderName() { return senderName; }
        public String getTargetName() { return targetName; }
        public long getCreateTime() { return createTime; }
        public BukkitTask getTimeoutTask() { return timeoutTask; }
        public void setTimeoutTask(BukkitTask timeoutTask) { this.timeoutTask = timeoutTask; }
    }

    public static class TradeSession {
        private final UUID playerAUuid;
        private final UUID playerBUuid;
        private final String playerAName;
        private final String playerBName;

        private final ItemStack[] playerAItems = new ItemStack[20];
        private final ItemStack[] playerBItems = new ItemStack[20];

        private boolean playerAConfirmed = false;
        private boolean playerBConfirmed = false;

        private Inventory playerAInventory;
        private Inventory playerBInventory;

        private boolean ended = false;

        public TradeSession(UUID playerAUuid, UUID playerBUuid, String playerAName, String playerBName) {
            this.playerAUuid = playerAUuid;
            this.playerBUuid = playerBUuid;
            this.playerAName = playerAName;
            this.playerBName = playerBName;
        }

        public UUID getPlayerAUuid() { return playerAUuid; }
        public UUID getPlayerBUuid() { return playerBUuid; }
        public String getPlayerAName() { return playerAName; }
        public String getPlayerBName() { return playerBName; }
        public boolean isEnded() { return ended; }
        public void setEnded(boolean ended) { this.ended = ended; }

        public Inventory getPlayerAInventory() { return playerAInventory; }
        public void setPlayerAInventory(Inventory inv) { this.playerAInventory = inv; }
        public Inventory getPlayerBInventory() { return playerBInventory; }
        public void setPlayerBInventory(Inventory inv) { this.playerBInventory = inv; }

        public boolean isPlayerA(UUID uuid) { return playerAUuid.equals(uuid); }

        public UUID getOtherUuid(UUID uuid) {
            return isPlayerA(uuid) ? playerBUuid : playerAUuid;
        }

        public String getOtherName(UUID uuid) {
            return isPlayerA(uuid) ? playerBName : playerAName;
        }

        public String getPlayerName(UUID uuid) {
            return isPlayerA(uuid) ? playerAName : playerBName;
        }

        public ItemStack[] getMyItems(UUID uuid) {
            return isPlayerA(uuid) ? playerAItems : playerBItems;
        }

        public ItemStack[] getOpponentItems(UUID uuid) {
            return isPlayerA(uuid) ? playerBItems : playerAItems;
        }

        public Inventory getMyInventory(UUID uuid) {
            return isPlayerA(uuid) ? playerAInventory : playerBInventory;
        }

        public Inventory getOpponentInventory(UUID uuid) {
            return isPlayerA(uuid) ? playerBInventory : playerAInventory;
        }

        public boolean isConfirmed(UUID uuid) {
            return isPlayerA(uuid) ? playerAConfirmed : playerBConfirmed;
        }

        public void setConfirmed(UUID uuid, boolean confirmed) {
            if (isPlayerA(uuid)) {
                playerAConfirmed = confirmed;
            } else {
                playerBConfirmed = confirmed;
            }
        }

        public boolean areBothConfirmed() {
            return playerAConfirmed && playerBConfirmed;
        }

        public void resetConfirmations() {
            playerAConfirmed = false;
            playerBConfirmed = false;
        }

        /**
         * 更新某方的物品数组（从 GUI inventory 同步）
         */
        public void updateItems(UUID uuid, ItemStack[] items) {
            ItemStack[] target = getMyItems(uuid);
            for (int i = 0; i < target.length; i++) {
                target[i] = i < items.length ? items[i] : null;
            }
        }

        /**
         * 执行物品交换
         */
        public void executeTrade() {
            Player a = Bukkit.getPlayer(playerAUuid);
            Player b = Bukkit.getPlayer(playerBUuid);

            // A 的物品给 B
            if (b != null && b.isOnline()) {
                giveItems(b, playerAItems);
            }
            // B 的物品给 A
            if (a != null && a.isOnline()) {
                giveItems(a, playerBItems);
            }

            // 清空 GUI 中的物品（防止关闭时 Bukkit 重复返还）
            clearGuiItems(playerAInventory);
            clearGuiItems(playerBInventory);
        }

        /**
         * 取消交易，返还物品
         */
        public void returnItems() {
            // 先清空 GUI
            clearGuiItems(playerAInventory);
            clearGuiItems(playerBInventory);

            // 返还物品
            Player a = Bukkit.getPlayer(playerAUuid);
            if (a != null && a.isOnline()) {
                giveItems(a, playerAItems);
            }
            Player b = Bukkit.getPlayer(playerBUuid);
            if (b != null && b.isOnline()) {
                giveItems(b, playerBItems);
            }
        }

        private void giveItems(Player player, ItemStack[] items) {
            for (ItemStack item : items) {
                if (item == null) continue;
                HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
                if (!overflow.isEmpty()) {
                    for (ItemStack drop : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    }
                    MessageUtil.send(player, "&e背包已满，部分物品已掉落在你脚下！");
                }
            }
        }

        private void clearGuiItems(Inventory inv) {
            if (inv == null) return;
            for (int slot : TradeGui.MY_ITEM_SLOTS) {
                inv.setItem(slot, null);
            }
        }
    }
}
