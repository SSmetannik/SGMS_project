package com.steelrework.playerprogress;

import com.steelrework.playerprogress.data.DataManager;
import com.steelrework.playerprogress.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /playerprogress (/pp) */
public final class PlayerProgressCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
            "menu", "m", "top", "t", "progress", "p", "points", "give", "admin", "a", "reload", "r");
    private static final List<String> ACTIONS = List.of("set", "add", "take");

    private final PlayerProgressPlugin plugin;

    public PlayerProgressCommand(PlayerProgressPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu", "m" -> {
                if (!(sender instanceof Player p)) {
                    msg().sendPlain(sender, "usage");
                    return true;
                }
                if (!p.hasPermission("playerprogress.menu")) {
                    msg().send(p, "no-permission");
                    return true;
                }
                plugin.menus().openMain(p);
            }
            case "top", "t" -> top(sender, args);
            case "progress", "p" -> change(sender, args, false);
            case "points" -> change(sender, args, true);
            case "give" -> give(sender, args);
            case "admin", "a" -> {
                if (!sender.hasPermission("playerprogress.admin")) {
                    msg().send(sender, "no-permission");
                } else if (sender instanceof Player p) {
                    plugin.menus().openAdminPlayers(p, 0);
                } else {
                    msg().send(sender, "player-only");
                }
            }
            case "reload", "r" -> {
                if (!sender.hasPermission("playerprogress.admin")) {
                    msg().send(sender, "no-permission");
                    return true;
                }
                plugin.reload();
                msg().send(sender, "reloaded");
            }
            default -> msg().sendPlain(sender, "usage");
        }
        return true;
    }

    private void top(CommandSender sender, String[] args) {
        if (!sender.hasPermission("playerprogress.top")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            if (sender instanceof Player p) {
                plugin.menus().openTops(p);
            } else {
                printTop(sender, DataManager.TOTAL);
            }
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        if (!key.equals(DataManager.TOTAL) && Skill.byKey(key) == null) {
            msg().send(sender, "invalid-skill", "list", String.join(", ", Skill.keys()) + ", total");
            return;
        }
        printTop(sender, key);
    }

    private void printTop(CommandSender sender, String key) {
        Skill skill = Skill.byKey(key);
        String name = skill == null ? plugin.getConfig().getString("menu.total-name", "total")
                : plugin.progress().skillName(skill);
        msg().sendPlain(sender, "top-header", "skill", name);
        List<DataManager.TopEntry> top = plugin.data().top(key);
        String line = plugin.getConfig().getString("menu.top-line", "%place%. %player% - %value%");
        if (top.isEmpty()) {
            sender.sendMessage(com.steelrework.playerprogress.util.Text.parse(
                    plugin.getConfig().getString("menu.top-empty", "-")));
        }
        for (int i = 0; i < top.size(); i++) {
            DataManager.TopEntry e = top.get(i);
            sender.sendMessage(com.steelrework.playerprogress.util.Text.parse(
                    com.steelrework.playerprogress.util.Text.replace(line, "place", String.valueOf(i + 1),
                            "player", e.name(), "value", String.valueOf(e.value()))));
        }
    }

    /** /pp progress|points <игрок> <познание> <set|add|take> <значение> */
    private void change(CommandSender sender, String[] args, boolean points) {
        if (!sender.hasPermission("playerprogress.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length < 5) {
            msg().sendPlain(sender, "usage");
            return;
        }
        PlayerData d = findData(args[1]);
        if (d == null) {
            msg().send(sender, "player-not-found", "player", args[1]);
            return;
        }
        Skill skill = Skill.byKey(args[2]);
        if (skill == null) {
            msg().send(sender, "invalid-skill", "list", String.join(", ", Skill.keys()));
            return;
        }
        String action = args[3].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            msg().send(sender, "invalid-action");
            return;
        }
        int value;
        try {
            value = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            msg().send(sender, "invalid-number");
            return;
        }
        if (value < 0) {
            msg().send(sender, "invalid-number");
            return;
        }
        ProgressManager pm = plugin.progress();
        int old = points ? d.points(skill) : d.level(skill);
        long target = switch (action) {
            case "add" -> (long) old + value;
            case "take" -> (long) old - value;
            default -> value;
        };
        int clampedTarget = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, target));
        int now;
        if (points) {
            pm.setPoints(d, skill, clampedTarget);
            now = d.points(skill);
        } else {
            now = pm.setLevel(d, skill, clampedTarget);
        }
        msg().send(sender, points ? "points-changed" : "level-changed",
                "skill", pm.skillName(skill), "player", d.name(),
                "old", String.valueOf(old), "new", String.valueOf(now));
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("playerprogress.admin")) {
            msg().send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            msg().sendPlain(sender, "usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg().send(sender, "player-not-found", "player", args[1]);
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                msg().send(sender, "invalid-number");
                return;
            }
            if (amount < 1) {
                msg().send(sender, "invalid-number");
                return;
            }
        }
        plugin.giveBooster(sender, target, args[2], amount);
    }

    private PlayerData findData(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return plugin.progress().data(online);
        return plugin.data().getByName(name);
    }

    // ================================================================ подсказки

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission("playerprogress.admin");
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) {
                boolean adminOnly = !(s.equals("menu") || s.equals("m") || s.equals("top") || s.equals("t"));
                if (!adminOnly || admin) options.add(s);
            }
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean change = sub.equals("progress") || sub.equals("p") || sub.equals("points");
            if ((sub.equals("top") || sub.equals("t")) && args.length == 2) {
                options.addAll(Skill.keys());
                options.add(DataManager.TOTAL);
            } else if (admin && (change || sub.equals("give"))) {
                if (args.length == 2) {
                    for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
                } else if (args.length == 3) {
                    options.addAll(change ? Skill.keys() : plugin.boosters().keys());
                } else if (args.length == 4) {
                    options.addAll(change ? ACTIONS : List.of("1", "8", "16", "64"));
                } else if (args.length == 5 && change) {
                    options.addAll(List.of("1", "5", "10", "32"));
                }
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return options;
    }
}
