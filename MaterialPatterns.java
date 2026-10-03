package com.steelrework.playerprogress.util;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Список материалов из конфига: точные имена ("STONE") или шаблоны со звёздочкой ("*_ORE").
 * Заранее разворачивается в набор материалов, поэтому проверка очень быстрая.
 */
public final class MaterialPatterns {

    private final Set<Material> materials;

    private MaterialPatterns(Set<Material> materials) {
        this.materials = materials;
    }

    public static MaterialPatterns of(Collection<String> patterns) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String raw : patterns) {
            set.addAll(expand(raw));
        }
        return new MaterialPatterns(set);
    }

    public boolean contains(Material material) {
        return material != null && materials.contains(material);
    }

    /** Все материалы, подходящие под одно имя или шаблон. Пустой список, если такого нет. */
    public static List<Material> expand(String raw) {
        List<Material> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        String name = raw.trim().toUpperCase(Locale.ROOT);
        if (name.startsWith("MINECRAFT:")) name = name.substring("MINECRAFT:".length());
        if (!name.contains("*")) {
            Material m = Material.getMaterial(name);
            if (m != null && !isLegacy(m)) out.add(m);
            return out;
        }
        Pattern regex = toRegex(name);
        for (Material m : Material.values()) {
            if (isLegacy(m)) continue;
            if (regex.matcher(m.name()).matches()) out.add(m);
        }
        return out;
    }

    public static Pattern toRegex(String wildcard) {
        StringBuilder sb = new StringBuilder();
        for (char c : wildcard.toCharArray()) {
            if (c == '*') sb.append(".*");
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString());
    }

    private static boolean isLegacy(Material m) {
        return m.name().startsWith("LEGACY_");
    }
}
