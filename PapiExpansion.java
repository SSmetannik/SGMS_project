package com.steelrework.playerprogress;

import com.steelrework.playerprogress.data.DataManager;
import com.steelrework.playerprogress.data.PlayerData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;

/**
 * Плейсхолдеры:
 *  %playerprogress_<познание>_level|points|required|xp|name%
 *  %playerprogress_total_level%
 *  %playerprogress_top_<познание|total>_<место>_name|value%
 *  %playerprogress_rank_<познание|total>%
 */
public final class PapiExpansion extends PlaceholderExpansion {

    private final PlayerProgressPlugin plugin;

    public PapiExpansion(PlayerProgressPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "playerprogress";
    }

    @Override
    public String getAuthor() {
        return "SteelRework";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        DataManager data = plugin.data();

        if (p.startsWith("top_")) {
            // top_<key>_<n>_<name|value>
            String[] parts = p.split("_");
            if (parts.length != 4) return null;
            int place;
            try {
                place = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return null;
            }
            List<DataManager.TopEntry> top = data.top(parts[1]);
            if (place < 1 || place > top.size()) return parts[3].equals("value") ? "0" : "-";
            DataManager.TopEntry e = top.get(place - 1);
            return switch (parts[3]) {
                case "name" -> e.name();
                case "value" -> String.valueOf(e.value());
                default -> null;
            };
        }

        if (player == null) return "";
        if (p.startsWith("rank_")) {
            int rank = data.rank(p.substring(5), player.getUniqueId());
            return rank == 0 ? "-" : String.valueOf(rank);
        }

        PlayerData d = data.get(player.getUniqueId());
        if (p.equals("total_level")) return d == null ? String.valueOf(Skill.all().length) : String.valueOf(d.totalLevel());

        int underscore = p.indexOf('_');
        if (underscore <= 0) return null;
        Skill skill = Skill.byKey(p.substring(0, underscore));
        if (skill == null) return null;
        ProgressManager pm = plugin.progress();
        int level = d == null ? 1 : d.level(skill);
        int max = pm.maxLevel();
        return switch (p.substring(underscore + 1)) {
            case "level" -> String.valueOf(level);
            case "points" -> String.valueOf(d == null ? 0 : d.points(skill));
            case "required" -> level >= max ? "-" : String.valueOf(pm.requiredPoints(skill, level + 1));
            case "xp" -> level >= max ? "-" : String.valueOf(pm.xpCost(level + 1));
            case "name" -> pm.skillName(skill);
            default -> null;
        };
    }
}
