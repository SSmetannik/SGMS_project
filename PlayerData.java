package com.steelrework.playerprogress.data;

import com.steelrework.playerprogress.Skill;

import java.util.UUID;

/** Прогресс одного игрока. Меняется только в основном потоке сервера. */
public final class PlayerData {

    private final UUID uuid;
    private String name;
    private final int[] levels = new int[Skill.all().length];
    private final int[] points = new int[Skill.all().length];

    public PlayerData(UUID uuid, String name, int startLevel) {
        this.uuid = uuid;
        this.name = name == null ? "?" : name;
        for (int i = 0; i < levels.length; i++) levels[i] = startLevel;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        if (name != null) this.name = name;
    }

    public int level(Skill skill) {
        return levels[skill.ordinal()];
    }

    public void level(Skill skill, int value) {
        levels[skill.ordinal()] = value;
    }

    public int points(Skill skill) {
        return points[skill.ordinal()];
    }

    public void points(Skill skill, int value) {
        points[skill.ordinal()] = Math.max(0, value);
    }

    public int totalLevel() {
        int sum = 0;
        for (int l : levels) sum += l;
        return sum;
    }

    /** Строка вида attack=5;defense=1;... — устойчива к добавлению новых познаний. */
    public String serializeLevels() {
        return serialize(levels);
    }

    public String serializePoints() {
        return serialize(points);
    }

    private static String serialize(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (Skill s : Skill.all()) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(s.key()).append('=').append(values[s.ordinal()]);
        }
        return sb.toString();
    }

    public void deserializeLevels(String raw, int min, int max) {
        deserialize(raw, levels, min, max);
    }

    public void deserializePoints(String raw) {
        deserialize(raw, points, 0, Integer.MAX_VALUE);
    }

    private static void deserialize(String raw, int[] target, int min, int max) {
        if (raw == null || raw.isEmpty()) return;
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            Skill skill = Skill.byKey(part.substring(0, eq));
            if (skill == null) continue;
            try {
                int v = Integer.parseInt(part.substring(eq + 1).trim());
                target[skill.ordinal()] = Math.max(min, Math.min(max, v));
            } catch (NumberFormatException ignored) {
            }
        }
    }
}
