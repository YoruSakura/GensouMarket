package net.scarletphantasy.gensouMarket.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import net.scarletphantasy.gensouMarket.velocity.service.BackendRegistry;
import org.slf4j.Logger;

/**
 * 监听玩家在后端服之间切换。
 * 当前 Phase 1 仅记录日志，后续 Phase 可用于触发 GUI 关闭/数据同步。
 */
public class PlayerConnectionListener {

    private final Logger logger;
    private final BackendRegistry backendRegistry;

    public PlayerConnectionListener(Logger logger, BackendRegistry backendRegistry) {
        this.logger = logger;
        this.backendRegistry = backendRegistry;
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        var player = event.getPlayer();
        var currentServer = player.getCurrentServer().orElse(null);
        if (currentServer == null) return;

        var previousServer = event.getPreviousServer();
        if (previousServer != null) {
            logger.debug("玩家 {} 从 {} 切换到 {}",
                    player.getUsername(),
                    previousServer.getServerInfo().getName(),
                    currentServer.getServerInfo().getName());
            cleanupIfEmpty(previousServer.getServerInfo().getName(), previousServer.getPlayersConnected().size());
        } else {
            logger.debug("玩家 {} 加入后端 {}",
                    player.getUsername(),
                    currentServer.getServerInfo().getName());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(connection ->
                cleanupIfEmpty(connection.getServerInfo().getName(), connection.getServer().getPlayersConnected().size()));
    }

    private void cleanupIfEmpty(String serverName, int connectedPlayers) {
        if (connectedPlayers == 0) {
            backendRegistry.unregisterByServerName(serverName);
        }
    }
}
