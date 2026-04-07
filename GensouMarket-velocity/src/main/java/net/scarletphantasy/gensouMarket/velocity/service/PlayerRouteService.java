package net.scarletphantasy.gensouMarket.velocity.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;
import java.util.UUID;

/**
 * 玩家路由服务：根据 UUID 查找玩家当前所在后端服。
 */
public class PlayerRouteService {

    private final ProxyServer proxyServer;
    private final BackendRegistry backendRegistry;

    public PlayerRouteService(ProxyServer proxyServer, BackendRegistry backendRegistry) {
        this.proxyServer = proxyServer;
        this.backendRegistry = backendRegistry;
    }

    /**
     * 查找玩家当前所在的已注册后端服。
     * 只返回已在 BackendRegistry 注册的后端服（即安装了 GensouMarket 的服）。
     */
    public Optional<RegisteredServer> locatePlayer(UUID playerUuid) {
        Optional<Player> player = proxyServer.getPlayer(playerUuid);
        if (player.isEmpty()) return Optional.empty();

        return player.get().getCurrentServer()
                .map(conn -> conn.getServer())
                .filter(server -> backendRegistry.getServerId(server.getServerInfo().getName()) != null);
    }

    /**
     * 判断玩家是否在线（在任意后端服）。
     */
    public boolean isOnline(UUID playerUuid) {
        return proxyServer.getPlayer(playerUuid).isPresent();
    }

    /**
     * 判断玩家是否在线于已注册的 GensouMarket 后端服。
     */
    public boolean isOnRegisteredServer(UUID playerUuid) {
        return locatePlayer(playerUuid).isPresent();
    }
}
