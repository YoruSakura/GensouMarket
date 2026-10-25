package net.scarletphantasy.gensouMarket.util;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigFiles {
    private ConfigFiles() {}

    public static void save(FileConfiguration config, Path file) throws IOException {
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".gmarket-", ".tmp");
        try {
            Files.writeString(temporary, config.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
