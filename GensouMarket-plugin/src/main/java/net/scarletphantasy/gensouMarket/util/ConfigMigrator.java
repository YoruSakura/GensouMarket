package net.scarletphantasy.gensouMarket.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

public final class ConfigMigrator {

    private ConfigMigrator() {}

    /**
     * 将磁盘上的配置文件与 jar 内的默认配置进行对比，自动迁移。
     * - 保留用户已有的配置值
     * - 补充新版本新增的配置项（使用默认值）
     * - 识别旧版本已移除的配置项并警告
     * - 变更时备份旧文件为 .bak
     *
     * @return true 如果执行了迁移
     */
    public static boolean migrate(File configFile, InputStream defaultStream, Logger logger) {
        if (!configFile.exists() || defaultStream == null) return false;

        YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(configFile);
        YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(defaultStream, StandardCharsets.UTF_8));

        Set<String> oldLeafKeys = getLeafKeys(oldConfig);
        Set<String> defaultLeafKeys = getLeafKeys(defaultConfig);

        // 旧配置有但新配置没有 → 已移除的配置项
        Set<String> removedKeys = new LinkedHashSet<>(oldLeafKeys);
        removedKeys.removeAll(defaultLeafKeys);

        // 新配置有但旧配置没有 → 新增的配置项
        Set<String> addedKeys = new LinkedHashSet<>(defaultLeafKeys);
        addedKeys.removeAll(oldLeafKeys);

        if (removedKeys.isEmpty() && addedKeys.isEmpty()) return false;

        // 构建合并后的配置：以默认结构为骨架，覆盖用户已有值
        YamlConfiguration merged = new YamlConfiguration();
        for (String key : defaultLeafKeys) {
            if (oldConfig.contains(key)) {
                merged.set(key, oldConfig.get(key));
            } else {
                merged.set(key, defaultConfig.get(key));
            }
        }

        // 备份旧配置
        String fileName = configFile.getName();
        File backupFile = new File(configFile.getParent(), fileName + ".bak");
        if (backupFile.exists()) backupFile.delete();
        if (!configFile.renameTo(backupFile)) {
            logger.severe("无法备份旧配置文件 " + fileName + "，迁移中止");
            return false;
        }

        // 保存合并后的配置
        try {
            merged.save(configFile);
        } catch (IOException e) {
            logger.severe("无法保存迁移后的配置文件 " + fileName);
            e.printStackTrace();
            // 尝试恢复备份
            backupFile.renameTo(configFile);
            return false;
        }

        // 控制台提醒
        logger.warning("========================================");
        logger.warning("配置文件 " + fileName + " 已自动迁移更新");
        if (!removedKeys.isEmpty()) {
            logger.warning("以下配置项在新版本中已被移除:");
            for (String key : removedKeys) {
                logger.warning("  - " + key + " (旧值: " + oldConfig.get(key) + ")");
            }
        }
        if (!addedKeys.isEmpty()) {
            logger.info("以下配置项为新增 (已使用默认值):");
            for (String key : addedKeys) {
                logger.info("  + " + key + " = " + defaultConfig.get(key));
            }
        }
        logger.warning("旧配置文件已备份为: " + backupFile.getName());
        logger.warning("========================================");

        return true;
    }

    private static Set<String> getLeafKeys(YamlConfiguration config) {
        Set<String> leafKeys = new LinkedHashSet<>();
        for (String key : config.getKeys(true)) {
            if (!config.isConfigurationSection(key)) {
                leafKeys.add(key);
            }
        }
        return leafKeys;
    }
}
