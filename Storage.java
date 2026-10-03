package com.steelrework.playerprogress.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Хранение прогресса в файле players.yml (без сторонних библиотек).
 * Запись идёт в отдельном потоке через временный файл, чтобы данные не повредились.
 */
public final class Storage {

    /** Неизменяемая копия данных игрока для записи в другом потоке. */
    public record Snapshot(UUID uuid, String name, String levels, String points) {
        public static Snapshot of(PlayerData d) {
            return new Snapshot(d.uuid(), d.name(), d.serializeLevels(), d.serializePoints());
        }
    }

    private final Logger logger;
    private final File file;
    private final File tempFile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayerProgress-Save");
        t.setDaemon(true);
        return t;
    });

    public Storage(File dataFolder, Logger logger) {
        this.logger = logger;
        this.file = new File(dataFolder, "players.yml");
        this.tempFile = new File(dataFolder, "players.yml.tmp");
    }

    public boolean exists() {
        return file.exists();
    }

    /** Загружает всех игроков (вызывается один раз при запуске). */
    public List<PlayerData> loadAll(int startLevel, int maxLevel) {
        List<PlayerData> list = new ArrayList<>();
        if (!file.exists()) return list;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return list;
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection sec = players.getConfigurationSection(key);
            if (sec == null) continue;
            PlayerData d = new PlayerData(uuid, sec.getString("name", "?"), startLevel);
            d.deserializeLevels(sec.getString("levels", ""), 1, maxLevel);
            d.deserializePoints(sec.getString("points", ""));
            list.add(d);
        }
        return list;
    }

    /** Сохраняет ВСЕХ игроков (файл переписывается целиком). */
    public void saveAsync(List<Snapshot> all) {
        executor.execute(() -> write(all));
    }

    /** Останавливает поток записи и синхронно записывает последние данные. */
    public void close(List<Snapshot> all) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Поток сохранения не успел завершиться за 10 секунд.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        write(all);
    }

    private synchronized void write(List<Snapshot> all) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Snapshot s : all) {
            String base = "players." + s.uuid();
            yaml.set(base + ".name", s.name());
            yaml.set(base + ".levels", s.levels());
            yaml.set(base + ".points", s.points());
        }
        try {
            yaml.save(tempFile);
            try {
                Files.move(tempFile.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Не удалось сохранить прогресс игроков в players.yml", e);
        }
    }

    /**
     * Перенос уровней из data.db (прошлые версии плагина). Срабатывает один раз — пока нет players.yml.
     * Нужен драйвер SQLite, встроенный в сервер; если его нет — перенос пропускается.
     */
    public List<PlayerData> migrateLegacy(File dataFolder, int maxLevel) {
        List<PlayerData> list = new ArrayList<>();
        File db = new File(dataFolder, "data.db");
        if (file.exists() || !db.exists()) return list;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.warning("Найдена старая база data.db, но в сервере нет драйвера SQLite — уровни не перенесены.");
            return list;
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath())) {
            readLegacyTable(c, "SELECT uuid, name, data FROM progress", true, maxLevel, list);
            if (list.isEmpty()) {
                readLegacyTable(c, "SELECT uuid, name, levels FROM players", false, maxLevel, list);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Не удалось прочитать старую базу data.db", e);
        }
        return list;
    }

    private static void readLegacyTable(Connection c, String sql, boolean oldFormat, int maxLevel,
                                        List<PlayerData> out) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(rs.getString(1));
                } catch (IllegalArgumentException | NullPointerException e) {
                    continue;
                }
                PlayerData d = new PlayerData(uuid, rs.getString(2), 1);
                String raw = rs.getString(3);
                if (raw != null) {
                    String levels = oldFormat
                            ? raw.split("\\|", 2)[0].replace(':', '=').replace(',', ';')
                            : raw;
                    d.deserializeLevels(levels, 1, maxLevel);
                }
                out.add(d);
            }
        } catch (Exception ignored) {
            // такой таблицы нет
        }
    }
}
