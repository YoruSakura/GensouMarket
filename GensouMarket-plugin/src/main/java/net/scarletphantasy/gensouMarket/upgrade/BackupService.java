package net.scarletphantasy.gensouMarket.upgrade;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 升级前配置/数据文件备份服务。
 * <p>
 * 第一版：备份 plugins/GensouMarket/ 下的配置文件和 data/ 目录到 backups/upgrade-{timestamp}/。
 * MySQL 不做完整 dump，迁移前输出强警告。
 */
public class BackupService {

    private static final Logger LOGGER = Logger.getLogger("GensouMarket");
    private final File dataFolder;

    public BackupService(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    /**
     * 创建升级备份目录并复制关键配置文件。
     *
     * @return 备份目录路径，失败返回 null
     */
    public File createBackup() {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File backupDir = new File(dataFolder, "backups/upgrade-" + timestamp);

        if (!backupDir.mkdirs()) {
            LOGGER.warning("[Upgrade] 无法创建备份目录: " + backupDir.getAbsolutePath());
            return null;
        }

        // 备份关键配置文件
        String[] configFiles = {"config.yml", "shop.yml", "recycle.yml", "upgrade-state.yml"};
        for (String name : configFiles) {
            File src = new File(dataFolder, name);
            if (src.exists()) {
                copyFile(src, new File(backupDir, name));
            }
        }

        // 备份 data/ 目录（SQLite/YAML 数据文件）
        File dataDir = new File(dataFolder, "data");
        if (dataDir.exists() && dataDir.isDirectory()) {
            File backupDataDir = new File(backupDir, "data");
            backupDataDir.mkdirs();
            File[] dataFiles = dataDir.listFiles();
            if (dataFiles != null) {
                for (File f : dataFiles) {
                    if (f.isFile()) {
                        copyFile(f, new File(backupDataDir, f.getName()));
                    }
                }
            }
        }

        LOGGER.info("[Upgrade] 配置备份已创建: " + backupDir.getAbsolutePath());
        return backupDir;
    }

    private void copyFile(File src, File dst) {
        try {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[Upgrade] 备份文件失败: " + src.getName(), e);
        }
    }
}
