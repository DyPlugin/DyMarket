package dev.dymarket.importer;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Reads balances from EssentialsX: userdata/&lt;uuid&gt;.yml, key {@code money}. Only reads. */
public final class EssentialsImporter {
    public static final class Result {
        public final List<ImportEntry> entries = new ArrayList<>();
        public int skipped;
    }

    private final File folder;
    private final Logger logger;

    public EssentialsImporter(File userdataFolder, Logger logger) {
        this.folder = userdataFolder;
        this.logger = logger;
    }

    public boolean present() {
        return folder.isDirectory();
    }

    public Result read() {
        Result result = new Result();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return result;
        int done = 0;
        for (File file : files) {
            UUID id;
            try {
                id = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
            } catch (IllegalArgumentException notUuid) {
                result.skipped++;
                continue;
            }
            try {
                YamlConfiguration data = new YamlConfiguration();
                data.load(file);
                Object money = data.get("money");
                if (money == null) {
                    result.skipped++;
                    continue;
                }
                BigDecimal amount = new BigDecimal(String.valueOf(money).trim());
                if (amount.signum() < 0) {
                    result.skipped++;
                    continue;
                }
                result.entries.add(new ImportEntry(id, data.getString("last-account-name", data.getString("lastAccountName")), amount));
            } catch (Exception unreadable) {
                result.skipped++;
            }
            if (++done % 1000 == 0) logger.info("Essentials import: read " + done + " of " + files.length + " files.");
        }
        return result;
    }
}
