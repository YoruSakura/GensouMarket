package net.scarletphantasy.gensouMarket.config;

import net.scarletphantasy.gensouMarket.util.ConfigMigrator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingGroupStoreTest {
    @TempDir Path directory;

    private PricingGroupStore store() {
        var store = new PricingGroupStore(directory.resolve("pricing-groups"), mock(Logger.class));
        store.load(true);
        return store;
    }

    @Test void oneFilePerGroupSurvivesReloadAndDeletionOnlyRemovesTheRequestedGroup() throws Exception {
        var store = store();
        store.create("farm");
        store.create("rare");
        store.set("farm", "recycle", "cycle-amplitude", "0.1");
        store.set("rare", "shop", "fixed-dynamic-enabled", "true");
        var reloaded = store();
        assertEquals(List.of("farm", "rare"), reloaded.ids());
        assertEquals(0.1, reloaded.get("farm").getDouble("recycle.cycle-amplitude"));
        assertTrue(reloaded.get("rare").getBoolean("shop.fixed-dynamic-enabled"));
        reloaded.delete("farm");
        assertFalse(Files.exists(directory.resolve("pricing-groups/farm.yml")));
        assertEquals(List.of("rare"), store().ids());
    }

    @Test void disabledGroupsDoNotCreateDirectoriesAndExistingFilesRemainUntouched() throws Exception {
        var store = new PricingGroupStore(directory.resolve("pricing-groups"), mock(Logger.class));
        store.load(false);
        assertFalse(Files.exists(directory.resolve("pricing-groups")));
        store.load(true);
        store.create("farm");
        String content = Files.readString(directory.resolve("pricing-groups/farm.yml"));
        store.load(false);
        assertTrue(store.ids().isEmpty());
        assertEquals(content, Files.readString(directory.resolve("pricing-groups/farm.yml")));
    }

    @Test void invalidNamesAndDuplicateCreationCannotOverwriteFiles() throws Exception {
        var store = store();
        for (String id : List.of("../config", "", "a.b", "Farm", "a/b", "a".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> store.create(id));
        }
        store.create("farm");
        store.set("farm", "recycle", "min-price-volume", "100");
        assertThrows(IllegalArgumentException.class, () -> store.create("farm"));
        assertEquals(100, store().get("farm").getInt("recycle.min-price-volume"));
    }

    @Test void malformedFilesAreSkippedWithoutAffectingValidGroups() throws Exception {
        var store = store();
        store.create("farm");
        Files.writeString(directory.resolve("pricing-groups/broken.yml"), "recycle: [broken\n");
        store.load(true);
        assertEquals(List.of("farm"), store.ids());
        assertNull(store.get("broken"));
    }

    @Test void parametersRejectNonFiniteValuesInvalidTypesAndUnknownKeys() throws Exception {
        var store = store();
        store.create("farm");
        for (String value : List.of("NaN", "Infinity", "-1", "1.01")) {
            assertThrows(IllegalArgumentException.class, () -> store.set("farm", "recycle", "cycle-amplitude", value));
        }
        assertThrows(IllegalArgumentException.class, () -> store.set("farm", "shop", "fixed-dynamic-enabled", "yes"));
        assertThrows(IllegalArgumentException.class, () -> store.set("farm", "recycle", "bucket-seconds", "0"));
        assertThrows(IllegalArgumentException.class, () -> store.set("farm", "recycle", "min-price-volume", "2.5"));
        assertThrows(IllegalArgumentException.class, () -> store.set("farm", "shop", "anti-arbitrage-multiplier", "0.9"));
        assertThrows(IllegalArgumentException.class, () -> store.set("farm", "shop", "unknown", "1"));
        assertThrows(IllegalArgumentException.class, () -> store.set("farm", "unknown", "dynamic-enabled", "true"));
        store.set("farm", "recycle", "cycle-amplitude", "0");
        store.set("farm", "recycle", "min-price-volume", "0");
        assertEquals(0, store().get("farm").getDouble("recycle.cycle-amplitude"));
        store.set("farm", "recycle", "cycle-amplitude", "default");
        assertFalse(store().get("farm").contains("recycle.cycle-amplitude"));
    }

    @Test void failedWriteDoesNotChangeTheInMemoryGroupOrLeaveTemporaryFiles() throws Exception {
        var store = store();
        store.create("farm");
        store.set("farm", "recycle", "min-price-volume", "100");
        Path file = directory.resolve("pricing-groups/farm.yml");
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(IOException.class, () -> store.set("farm", "recycle", "min-price-volume", "200"));
        assertEquals(100, store.get("farm").getInt("recycle.min-price-volume"));
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of("farm.yml"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test void mainConfigurationMigrationDoesNotTouchIndependentGroupFiles() throws Exception {
        var store = store();
        store.create("farm");
        store.set("farm", "recycle", "min-price-volume", "1234");
        Path config = directory.resolve("config.yml");
        Files.writeString(config, "debug: false\nobsolete: true\n");
        String defaults = "debug: false\ndynamic-pricing:\n  groups-enabled: true\n";
        assertTrue(ConfigMigrator.migrate(config.toFile(), new ByteArrayInputStream(defaults.getBytes(StandardCharsets.UTF_8)),
                mock(Logger.class)));
        assertTrue(YamlConfiguration.loadConfiguration(config.toFile()).getBoolean("dynamic-pricing.groups-enabled"));
        assertEquals(1234, store().get("farm").getInt("recycle.min-price-volume"));
    }
}
