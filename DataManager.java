package com.steelrework.playerprogress.data;

import com.steelrework.playerprogress.Skill;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/** Кэш всех игроков + топы. Работает только в основном потоке. */
public final class DataManager {

    public static final String TOTAL = "total";

    public record TopEntry(UUID uuid, String name, int value) {
    }

    private final Storage storage;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<String, UUID> byName = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();

    private Map<String, List<TopEntry>> tops = Collections.emptyMap();
    private Map<String, Map<UUID, Integer>> ranks = Collections.emptyMap();

    public DataManager(Storage storage) {
        this.storage = storage;
    }

    public void loadAll(Collection<PlayerData> loaded) {
        for (PlayerData d : loaded) {
            players.put(d.uuid(), d);
            byName.put(d.name().toLowerCase(Locale.ROOT), d.uuid());
        }
    }

    /** Данные игрока онлайн; создаёт новую запись для новичка. */
    public PlayerData get(Player player, int startLevel) {
        PlayerData d = players.get(player.getUniqueId());
        if (d == null) {
            d = new PlayerData(player.getUniqueId(), player.getName(), startLevel);
            players.put(d.uuid(), d);
            dirty.add(d.uuid());
        } else if (!d.name().equals(player.getName())) {
            byName.remove(d.name().toLowerCase(Locale.ROOT));
            d.name(player.getName());
            dirty.add(d.uuid());
        }
        byName.put(player.getName().toLowerCase(Locale.ROOT), d.uuid());
        return d;
    }

    public PlayerData get(UUID uuid) {
        return players.get(uuid);
    }

    public PlayerData getByName(String name) {
        UUID uuid = byName.get(name.toLowerCase(Locale.ROOT));
        return uuid == null ? null : players.get(uuid);
    }

    public Collection<String> knownNames() {
        List<String> list = new ArrayList<>();
        for (PlayerData d : players.values()) list.add(d.name());
        return list;
    }

    public void markDirty(PlayerData data) {
        dirty.add(data.uuid());
    }

    public void saveDirtyAsync() {
        storage.saveAsync(takeDirtySnapshots());
    }

    public void saveAsync(PlayerData data) {
        dirty.remove(data.uuid());
        storage.saveAsync(List.of(Storage.Snapshot.of(data)));
    }

    public void close() {
        storage.close(takeDirtySnapshots());
    }

    private List<Storage.Snapshot> takeDirtySnapshots() {
        List<Storage.Snapshot> list = new ArrayList<>(dirty.size());
        for (UUID uuid : dirty) {
            PlayerData d = players.get(uuid);
            if (d != null) list.add(Storage.Snapshot.of(d));
        }
        dirty.clear();
        return list;
    }

    // ---------------------------------------------------------------- топы

    public void recalculateTops(int size) {
        Map<String, List<TopEntry>> newTops = new HashMap<>();
        Map<String, Map<UUID, Integer>> newRanks = new HashMap<>();
        for (Skill s : Skill.all()) {
            build(s.key(), d -> d.level(s), size, newTops, newRanks);
        }
        build(TOTAL, PlayerData::totalLevel, size, newTops, newRanks);
        tops = newTops;
        ranks = newRanks;
    }

    private void build(String key, ToIntFunction<PlayerData> value, int size,
                       Map<String, List<TopEntry>> outTops, Map<String, Map<UUID, Integer>> outRanks) {
        List<PlayerData> sorted = new ArrayList<>(players.values());
        sorted.sort(Comparator.comparingInt(value).reversed()
                .thenComparing(d -> d.name().toLowerCase(Locale.ROOT)));
        List<TopEntry> top = new ArrayList<>(size);
        Map<UUID, Integer> rank = new HashMap<>(sorted.size() * 2);
        for (int i = 0; i < sorted.size(); i++) {
            PlayerData d = sorted.get(i);
            rank.put(d.uuid(), i + 1);
            if (i < size) top.add(new TopEntry(d.uuid(), d.name(), value.applyAsInt(d)));
        }
        outTops.put(key, top);
        outRanks.put(key, rank);
    }

    /** key — ключ познания или "total". Пустой список, если ключ неизвестен. */
    public List<TopEntry> top(String key) {
        return tops.getOrDefault(key, Collections.emptyList());
    }

    /** Место игрока (с 1), 0 — если неизвестно. */
    public int rank(String key, UUID uuid) {
        Map<UUID, Integer> map = ranks.get(key);
        if (map == null) return 0;
        return map.getOrDefault(uuid, 0);
    }
}
