package net.scarletphantasy.gensouMarket.upgrade;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 每次启动写入 upgrade-report-latest.yml，记录升级/迁移结果。
 * 失败时也必须写报告。
 */
public class UpgradeReportWriter {

    private static final Logger LOGGER = Logger.getLogger("GensouMarket");
    private final File dataFolder;

    public UpgradeReportWriter(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    public void writeReport(String storageType, MigrationResult result) {
        File file = new File(dataFolder, "upgrade-report-latest.yml");
        YamlConfiguration report = new YamlConfiguration();

        report.set("plugin-version", VersionRegistry.PLUGIN_VERSION);
        report.set("storage-type", storageType);
        report.set("data-version-before", result.dataVersionBefore());
        report.set("data-version-after", result.dataVersionAfter());
        report.set("config-version-before", result.configVersionBefore());
        report.set("config-version-after", result.configVersionAfter());
        report.set("migrations", result.executedSteps());
        report.set("result", result.success() ? "SUCCESS" : "FAILED");
        report.set("block-reasons", result.blockReasons());

        if (result.errorMessage() != null) {
            report.set("error", result.errorMessage());
        }

        report.set("timestamp", System.currentTimeMillis());

        try {
            report.save(file);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Upgrade] 无法写入升级报告", e);
        }
    }
}
