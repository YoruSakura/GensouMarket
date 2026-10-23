package net.scarletphantasy.gensouMarket.velocity.service;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理已注册的 GensouMarket 后端服。
 * 后端服通过发送 SERVER_HELLO 注册，断开或发送 SERVER_GOODBYE 时注销。
 */
public class BackendRegistry {

    private final Logger logger;

    // serverId -> RegisteredServer
    private final Map<String, RegisteredServer> backends = new ConcurrentHashMap<>();
    // RegisteredServer name -> serverId (反向映射，用于断连时查找)
    private final Map<String, String> serverNameToId = new ConcurrentHashMap<>();

    public BackendRegistry(Logger logger) {
        this.logger = logger;
    }

    public synchronized void register(String serverId, RegisteredServer server) {
        if (serverId == null || serverId.isBlank() || "velocity".equalsIgnoreCase(serverId)) {
            logger.warn("拒绝无效后端 ID: {}", serverId);
            return;
        }
        String serverName = server.getServerInfo().getName();
        RegisteredServer existing = backends.get(serverId);
        if (existing != null && !existing.getServerInfo().getName().equals(serverName)) {
            logger.warn("拒绝重复后端 ID: {} ({})", serverId, serverName);
            return;
        }
        String previousId = serverNameToId.get(serverName);
        if (previousId != null && !previousId.equals(serverId)) backends.remove(previousId);
        backends.put(serverId, server);
        serverNameToId.put(server.getServerInfo().getName(), serverId);
        logger.info("后端服已注册: {} ({})", serverId, server.getServerInfo().getName());
    }

    public synchronized void unregister(String serverId) {
        RegisteredServer removed = backends.remove(serverId);
        if (removed != null) {
            serverNameToId.remove(removed.getServerInfo().getName());
            logger.info("后端服已注销: {}", serverId);
        }
    }

    /**
     * 根据 Velocity 的 RegisteredServer name 注销（用于服务器断连）。
     */
    public synchronized void unregisterByServerName(String serverName) {
        String serverId = serverNameToId.remove(serverName);
        if (serverId != null) {
            backends.remove(serverId);
            logger.info("后端服已注销 (断连): {} ({})", serverId, serverName);
        }
    }

    public RegisteredServer getServer(String serverId) {
        return backends.get(serverId);
    }

    public String getServerId(String serverName) {
        return serverNameToId.get(serverName);
    }

    public boolean isRegistered(String serverId) {
        return backends.containsKey(serverId);
    }

    public Collection<RegisteredServer> getAllServers() {
        return backends.values();
    }

    public int size() {
        return backends.size();
    }
}
