package net.scarletphantasy.gensouMarket.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.scarletphantasy.gensouMarket.velocity.config.VelocityConfig;
import net.scarletphantasy.gensouMarket.velocity.listener.PlayerConnectionListener;
import net.scarletphantasy.gensouMarket.velocity.listener.PluginMessageListener;
import net.scarletphantasy.gensouMarket.velocity.service.BackendRegistry;
import net.scarletphantasy.gensouMarket.velocity.service.BroadcastService;
import net.scarletphantasy.gensouMarket.velocity.service.PlayerRouteService;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "gensoumarket-velocity",
        name = "GensouMarket-Velocity",
        version = "1.0.0",
        description = "GensouMarket 跨服消息路由代理端",
        authors = {"ScarletPhantasy"}
)
public class GensouMarketVelocity {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityConfig config;
    private BackendRegistry backendRegistry;
    private PlayerRouteService playerRouteService;
    private BroadcastService broadcastService;

    @Inject
    public GensouMarketVelocity(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // 加载配置
        config = new VelocityConfig(dataDirectory, logger);
        config.load();

        // 解析 channel 标识符
        String channelStr = config.getChannel();
        MinecraftChannelIdentifier channelId;
        try {
            String[] parts = channelStr.split(":", 2);
            if (parts.length != 2) {
                logger.error("channel 格式错误 (需要 namespace:key): {}", channelStr);
                return;
            }
            channelId = MinecraftChannelIdentifier.from(channelStr);
        } catch (Exception e) {
            logger.error("无法解析 channel: {}", channelStr, e);
            return;
        }

        // 注册 channel
        proxyServer.getChannelRegistrar().register(channelId);
        logger.info("已注册 Plugin Messaging Channel: {}", channelStr);

        // 初始化服务
        backendRegistry = new BackendRegistry(logger);
        playerRouteService = new PlayerRouteService(proxyServer, backendRegistry);
        broadcastService = new BroadcastService(logger, backendRegistry, channelId);

        // 注册监听器
        var eventManager = proxyServer.getEventManager();
        eventManager.register(this, new PluginMessageListener(
                logger, channelId, backendRegistry, broadcastService, playerRouteService));
        eventManager.register(this, new PlayerConnectionListener(logger, backendRegistry));

        logger.info("GensouMarket Velocity 已启动 (已注册后端: {})", backendRegistry.size());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("GensouMarket Velocity 已关闭");
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public VelocityConfig getConfig() {
        return config;
    }

    public BackendRegistry getBackendRegistry() {
        return backendRegistry;
    }

    public PlayerRouteService getPlayerRouteService() {
        return playerRouteService;
    }

    public BroadcastService getBroadcastService() {
        return broadcastService;
    }
}
