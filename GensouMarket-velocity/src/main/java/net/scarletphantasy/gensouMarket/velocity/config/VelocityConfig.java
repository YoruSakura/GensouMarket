package net.scarletphantasy.gensouMarket.velocity.config;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Velocity 端配置。使用 properties 文件，保持轻量。
 */
public class VelocityConfig {

    private final Path dataDirectory;
    private final Logger logger;

    private String channel = "gensoumarket:main";
    private int requestTimeoutSeconds = 10;
    private int dedupTtlSeconds = 30;

    public VelocityConfig(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public void load() {
        Path configPath = dataDirectory.resolve("config.properties");

        if (!Files.exists(configPath)) {
            saveDefault(configPath);
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("无法读取配置文件，使用默认值: {}", e.getMessage());
            return;
        }

        channel = props.getProperty("channel", channel);
        requestTimeoutSeconds = parseInt(props, "request-timeout-seconds", requestTimeoutSeconds);
        dedupTtlSeconds = parseInt(props, "dedup-ttl-seconds", dedupTtlSeconds);

        logger.info("配置已加载 (channel={}, timeout={}s, dedup={}s)", channel, requestTimeoutSeconds, dedupTtlSeconds);
    }

    private void saveDefault(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            String content = """
                    # GensouMarket Velocity 配置
                    # Plugin Messaging Channel 名称 (须与 Paper 端 cluster.channel 一致)
                    channel=gensoumarket:main
                    # 远程请求超时 (秒)
                    request-timeout-seconds=10
                    # 幂等去重 TTL (秒)
                    dedup-ttl-seconds=30
                    """;
            Files.writeString(configPath, content);
        } catch (IOException e) {
            logger.warn("无法保存默认配置: {}", e.getMessage());
        }
    }

    private int parseInt(Properties props, String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            logger.warn("配置项 {} 格式错误: {}，使用默认值 {}", key, val, defaultValue);
            return defaultValue;
        }
    }

    public String getChannel() {
        return channel;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public int getDedupTtlSeconds() {
        return dedupTtlSeconds;
    }
}
