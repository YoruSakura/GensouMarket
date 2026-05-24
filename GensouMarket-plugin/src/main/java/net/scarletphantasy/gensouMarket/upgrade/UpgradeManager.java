package net.scarletphantasy.gensouMarket.upgrade;

import net.scarletphantasy.gensouMarket.storage.MySQLStorage;
import net.scarletphantasy.gensouMarket.storage.StorageProvider;
import net.scarletphantasy.gensouMarket.upgrade.meta.MetaStore;
import net.scarletphantasy.gensouMarket.upgrade.meta.SqlMetaStore;
import net.scarletphantasy.gensouMarket.upgrade.meta.YamlMetaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * v1.1.2 升级与降级框架入口。
 * <p>
 * 职责：
 * <ul>
 *   <li>初始化配置侧和存储侧元数据存储</li>
 *   <li>检查降级阻断条件</li>
 *   <li>执行有序迁移步骤</li>
 *   <li>记录数据版本</li>
 *   <li>输出升级报告</li>
 * </ul>
 * <p>
 * 启动顺序：
 * <pre>
 * 1. ConfigManager.load()
 * 2. UpgradeManager.initConfigSide()   — 初始化 upgrade-state.yml
 * 3. Vault setup
 * 4. StorageFactory.create()
 * 5. UpgradeManager.runStorageMigration() — 初始化 gensoumarket_meta，检查降级，执行迁移
 * 6. storage.initialize()
 * 7. 业务 Manager 初始化
 * </pre>
 */
public class UpgradeManager {

    private static final Logger LOGGER = Logger.getLogger("GensouMarket");

    private final File dataFolder;
    private final String storageType;
    private final BackupService backupService;
    private final UpgradeReportWriter reportWriter;

    /** 配置侧元数据（upgrade-state.yml） */
    private YamlMetaStore configMeta;
    /** 存储侧元数据（gensoumarket_meta 表或 upgrade-state.yml 内的 data-* 节点） */
    private MetaStore dataMeta;

    public UpgradeManager(File dataFolder, String storageType) {
        this.dataFolder = dataFolder;
        this.storageType = storageType;
        this.backupService = new BackupService(dataFolder);
        this.reportWriter = new UpgradeReportWriter(dataFolder);
    }

    // ========== Phase 1: 配置侧初始化 ==========

    /**
     * 在 ConfigManager.load() 之后、Vault/Storage 之前调用。
     * 初始化 upgrade-state.yml 并写入当前配置版本。
     */
    public void initConfigSide() throws Exception {
        configMeta = new YamlMetaStore(dataFolder);
        configMeta.initialize();

        int storedConfigVersion = configMeta.getInt("config-version", 0);

        // 降级检查：配置版本
        if (storedConfigVersion > VersionRegistry.CURRENT_CONFIG_VERSION) {
            String reason = String.format(
                    "配置版本降级阻断！存储配置版本=%d > 当前支持最大=%d。" +
                    "请恢复到最后使用的插件版本或联系开发者。",
                    storedConfigVersion, VersionRegistry.CURRENT_CONFIG_VERSION);
            LOGGER.severe("[Upgrade] " + reason);

            MigrationResult blocked = MigrationResult.blocked(
                    0, 0, storedConfigVersion, VersionRegistry.CURRENT_CONFIG_VERSION,
                    List.of(reason));
            reportWriter.writeReport(storageType, blocked);

            throw new UpgradeBlockedException(reason);
        }

        // 首次安装或从旧版本升级
        if (storedConfigVersion == 0) {
            configMeta.setInt("config-version", VersionRegistry.CURRENT_CONFIG_VERSION);
        }

        configMeta.set("last-plugin-version", VersionRegistry.PLUGIN_VERSION);
    }

    // ========== Phase 2: 存储侧迁移 ==========

    /**
     * 在 StorageFactory.create() 之后、storage.initialize() 之前调用。
     * <p>
     * MySQL 模式下会先初始化连接池和 meta 表，然后检查降级和执行迁移。
     * SQLite/YAML 模式下使用 upgrade-state.yml 的 data-version 节点。
     *
     * @return 迁移结果
     */
    public MigrationResult runStorageMigration(StorageProvider storage) throws Exception {
        // 1. 初始化 dataMeta
        if (storage instanceof MySQLStorage mysqlStorage) {
            mysqlStorage.initDataSource();
            SqlMetaStore sqlMeta = new SqlMetaStore(mysqlStorage.getDataSource(), true);
            sqlMeta.initialize();
            dataMeta = sqlMeta;
        } else {
            // SQLite / YAML 使用 configMeta 的 data-version 节点（共享 upgrade-state.yml）
            dataMeta = configMeta;
        }

        int storedDataVersion = dataMeta.getInt("data-version", 0);
        int storedConfigVersion = configMeta.getInt("config-version", 0);

        // 2. 降级检查：数据版本
        if (storedDataVersion > VersionRegistry.CURRENT_DATA_VERSION) {
            String reason = String.format(
                    "数据版本降级阻断！存储数据版本=%d > 当前支持最大=%d。" +
                    "请恢复到最后使用的插件版本或联系开发者。",
                    storedDataVersion, VersionRegistry.CURRENT_DATA_VERSION);
            LOGGER.severe("[Upgrade] " + reason);

            MigrationResult blocked = MigrationResult.blocked(
                    storedDataVersion, VersionRegistry.CURRENT_DATA_VERSION,
                    storedConfigVersion, VersionRegistry.CURRENT_CONFIG_VERSION,
                    List.of(reason));
            reportWriter.writeReport(storageType, blocked);

            throw new UpgradeBlockedException(reason);
        }

        // 3. 已是最新版本
        if (storedDataVersion >= VersionRegistry.CURRENT_DATA_VERSION) {
            LOGGER.info("[Upgrade] 数据版本已是最新 (v" + storedDataVersion + ")");
            dataMeta.set("last-plugin-version", VersionRegistry.PLUGIN_VERSION);

            MigrationResult result = MigrationResult.success(
                    storedDataVersion, storedDataVersion,
                    storedConfigVersion, VersionRegistry.CURRENT_CONFIG_VERSION,
                    Collections.emptyList());
            reportWriter.writeReport(storageType, result);
            return result;
        }

        // 4. 需要迁移
        LOGGER.info("[Upgrade] 检测到数据版本 v" + storedDataVersion
                + " → v" + VersionRegistry.CURRENT_DATA_VERSION + "，开始迁移...");

        // 4.1 备份
        if (storage instanceof MySQLStorage) {
            LOGGER.warning("[Upgrade] ⚠ MySQL 模式下将执行数据迁移。建议提前备份数据库！");
        }
        backupService.createBackup();

        // 4.2 构建迁移步骤
        List<MigrationStep> steps = buildMigrationSteps(storage, storedDataVersion);
        List<String> executedSteps = new ArrayList<>();

        try {
            for (MigrationStep step : steps) {
                LOGGER.info("[Upgrade] 执行迁移: " + step.id()
                        + " (v" + step.fromDataVersion() + " → v" + step.toDataVersion() + ")");
                step.migrate();
                executedSteps.add(step.id());
                dataMeta.setInt("data-version", step.toDataVersion());
                dataMeta.set("last-migration-id", step.id());
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[Upgrade] 迁移失败！插件将禁用。", e);

            MigrationResult failed = MigrationResult.failed(
                    storedDataVersion, storedConfigVersion,
                    executedSteps, e.getMessage());
            reportWriter.writeReport(storageType, failed);

            throw new UpgradeMigrationException("迁移步骤失败: " + e.getMessage(), e);
        }

        // 5. 迁移成功
        dataMeta.setInt("data-version", VersionRegistry.CURRENT_DATA_VERSION);
        dataMeta.set("last-plugin-version", VersionRegistry.PLUGIN_VERSION);
        configMeta.setInt("config-version", VersionRegistry.CURRENT_CONFIG_VERSION);

        MigrationResult result = MigrationResult.success(
                storedDataVersion, VersionRegistry.CURRENT_DATA_VERSION,
                storedConfigVersion, VersionRegistry.CURRENT_CONFIG_VERSION,
                executedSteps);
        reportWriter.writeReport(storageType, result);

        LOGGER.info("[Upgrade] 迁移完成！数据版本: v" + VersionRegistry.CURRENT_DATA_VERSION);
        return result;
    }

    /**
     * 根据当前数据版本构建需要执行的迁移步骤列表。
     */
    private List<MigrationStep> buildMigrationSteps(StorageProvider storage, int fromVersion) {
        List<MigrationStep> steps = new ArrayList<>();

        // v4 → v5: 确保 gensoumarket_meta 表已创建（v1.1.1 → v1.1.2）
        // 实际 schema 变更（recycled_stock 等列）已在 storage.initialize() 的
        // migrateSchema 中处理，此步骤仅标记版本跃迁。
        if (fromVersion < VersionRegistry.DATA_VERSION_1_1_2) {
            steps.add(new NoOpMigrationStep(
                    "v1.1.1-to-v1.1.2-meta-init",
                    VersionRegistry.DATA_VERSION_1_1_1,
                    VersionRegistry.DATA_VERSION_1_1_2
            ));
        }

        return steps;
    }

    // ========== 内部类 ==========

    /**
     * 空操作迁移步骤。用于标记版本跃迁，不执行实际 SQL 变更（变更已由 storage 层处理）。
     */
    private record NoOpMigrationStep(String id, int fromDataVersion, int toDataVersion) implements MigrationStep {
        @Override
        public void migrate() {
            // 幂等空操作：schema 变更已由 storage.initialize() 中的 migrateSchema/createTables 执行
        }
    }

    /**
     * 降级阻断异常。
     */
    public static class UpgradeBlockedException extends Exception {
        public UpgradeBlockedException(String message) {
            super(message);
        }
    }

    /**
     * 迁移执行异常。
     */
    public static class UpgradeMigrationException extends Exception {
        public UpgradeMigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
