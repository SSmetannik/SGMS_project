package com.steelrework.playerprogress.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** SQLite. Все записи идут в отдельном потоке, чтобы не нагружать сервер. */
public final class Storage {

    /** Неизменяемая копия данных игрока для записи в другом потоке. */
    public record Snapshot(UUID uuid, String name, String levels, String points) {
        public static Snapshot of(PlayerData d) {
            return new Snapshot(d.uuid(), d.name(), d.serializeLevels(), d.serializePoints());
        }
    }

    private final Logger logger;
    private final Connection connection;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayerProgress-Database");
        t.setDaemon(true);
        return t;
    });

    public Storage(File file, Logger logger) throws SQLException {
        this.logger = logger;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Драйвер SQLite не найден", e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS players ("
                    + "uuid TEXT PRIMARY KEY, name TEXT NOT NULL, levels TEXT NOT NULL, points TEXT NOT NULL)");
        }
    }

    /** Загружает всех игроков (вызывается один раз при запуске). */
    public List<PlayerData> loadAll(int startLevel, int maxLevel) throws SQLException {
        List<PlayerData> list = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, name, levels, points FROM players")) {
            while (rs.next()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(rs.getString(1));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                PlayerData d = new PlayerData(uuid, rs.getString(2), startLevel);
                d.deserializeLevels(rs.getString(3), 1, maxLevel);
                d.deserializePoints(rs.getString(4));
                list.add(d);
            }
        }
        return list;
    }

    /**
     * Перенос уровней из старой версии плагина (таблица progress, формат "attack:5,defense:1|...").
     * Выполняется один раз, только если новая таблица пуста. Очки старой версии не переносятся.
     */
    public int migrateLegacy(int maxLevel) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM players")) {
            if (rs.next() && rs.getInt(1) > 0) return 0;
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='progress'")) {
            if (!rs.next()) return 0;
        }
        List<Snapshot> list = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, name, data FROM progress")) {
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
                    String levels = raw.split("\\|", 2)[0];
                    d.deserializeLevels(levels.replace(':', '=').replace(',', ';'), 1, maxLevel);
                }
                list.add(Snapshot.of(d));
            }
        }
        write(list);
        return list.size();
    }

    public void saveAsync(List<Snapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        executor.execute(() -> write(snapshots));
    }

    /** Останавливает поток записи и синхронно дописывает последние данные. */
    public void close(List<Snapshot> lastSnapshots) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Поток базы данных не успел завершиться за 10 секунд.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        write(lastSnapshots);
        try {
            connection.close();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при закрытии базы", e);
        }
    }

    private synchronized void write(List<Snapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        String sql = "INSERT INTO players(uuid, name, levels, points) VALUES(?,?,?,?) "
                + "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, levels=excluded.levels, points=excluded.points";
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (Snapshot s : snapshots) {
                    ps.setString(1, s.uuid().toString());
                    ps.setString(2, s.name());
                    ps.setString(3, s.levels());
                    ps.setString(4, s.points());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Не удалось сохранить прогресс игроков", e);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }
}
