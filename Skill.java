package com.steelrework.playerprogress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Восемь познаний. Порядок важен: он же порядок в меню. */
public enum Skill {
    ATTACK("attack", "Атака"),
    DEFENSE("defense", "Защита"),
    AGILITY("agility", "Выносливость"),
    MAGIC("magic", "Магия"),
    BUILDING("building", "Строительство"),
    GATHERING("gathering", "Собирательство"),
    MINING("mining", "Горное дело"),
    FARMING("farming", "Земледелие");

    private static final Skill[] VALUES = values();

    private final String key;
    private final String defaultName;

    Skill(String key, String defaultName) {
        this.key = key;
        this.defaultName = defaultName;
    }

    public String key() {
        return key;
    }

    public String defaultName() {
        return defaultName;
    }

    public static Skill byKey(String key) {
        if (key == null) return null;
        String k = key.toLowerCase(Locale.ROOT);
        for (Skill s : VALUES) {
            if (s.key.equals(k)) return s;
        }
        return null;
    }

    public static List<String> keys() {
        List<String> list = new ArrayList<>(VALUES.length);
        for (Skill s : VALUES) list.add(s.key);
        return list;
    }

    public static Skill[] all() {
        return VALUES;
    }
}
