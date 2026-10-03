package com.steelrework.playerprogress;

import com.steelrework.playerprogress.data.DataManager;
import com.steelrework.playerprogress.data.PlayerData;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Уровни, очки, прокачка, атрибуты и проверка требований. */
public final class ProgressManager {

    /** Чего не хватает игроку: познание, нужный и текущий уровень. */
    public record Missing(Skill skill, int required, int current) {
    }

    public enum UpgradeResult { SUCCESS, MAX_LEVEL, NOT_ENOUGH_POINTS, NOT_ENOUGH_XP, DISABLED_WORLD }

    private final PlayerProgressPlugin plugin;
    private final DataManager data;
    private final Messages messages;

    // настройки
    private int maxLevel;
    private Set<String> disabledWorlds = new HashSet<>();
    private boolean bypassCreative;
    private boolean actionbar;
    private boolean stopAtMax;
    private int xpPerLevel;
    private final int[] pointsPerLevel = new int[Skill.all().length];
    private final List<Map<Integer, Integer>> customPoints = new ArrayList<>();
    private final Map<String, Map<String, Integer>> actionValues = new HashMap<>();
    private final String[] skillNames = new String[Skill.all().length];
    /** attributes[skill] = уровень -> (атрибут -> значение) */
    private final List<TreeMap<Integer, Map<Attribute, Double>>> attributeTables = new ArrayList<>();
    private final Set<Attribute> usedAttributes = new HashSet<>();

    public ProgressManager(PlayerProgressPlugin plugin, DataManager data, Messages messages) {
        this.plugin = plugin;
        this.data = data;
        this.messages = messages;
    }

    // ================================================================ настройки

    public void load(FileConfiguration cfg) {
        maxLevel = Math.max(1, cfg.getInt("settings.max-level", 32));
        disabledWorlds = new HashSet<>();
        for (String w : cfg.getStringList("settings.disabled-worlds")) disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        bypassCreative = cfg.getBoolean("settings.bypass-creative", true);
        actionbar = cfg.getBoolean("settings.actionbar-on-points", true);
        stopAtMax = cfg.getBoolean("settings.stop-points-at-max-level", true);
        xpPerLevel = Math.max(0, cfg.getInt("leveling.xp-cost-per-level", 1));

        customPoints.clear();
        for (Skill s : Skill.all()) {
            String base = "leveling.skills." + s.key();
            pointsPerLevel[s.ordinal()] = Math.max(0, cfg.getInt(base + ".points-per-level", 20));
            Map<Integer, Integer> custom = new HashMap<>();
            ConfigurationSection sec = cfg.getConfigurationSection(base + ".custom");
            if (sec != null) {
                for (String k : sec.getKeys(false)) {
                    try {
                        custom.put(Integer.parseInt(k), Math.max(0, sec.getInt(k)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            customPoints.add(custom);
            skillNames[s.ordinal()] = cfg.getString("skills." + s.key() + ".name", s.defaultName());
        }

        actionValues.clear();
        ConfigurationSection actions = cfg.getConfigurationSection("actions");
        if (actions != null) {
            for (String skillKey : actions.getKeys(false)) {
                ConfigurationSection sec = actions.getConfigurationSection(skillKey);
                if (sec == null) continue;
                Map<String, Integer> map = new HashMap<>();
                for (String action : sec.getKeys(false)) map.put(action, sec.getInt(action));
                actionValues.put(skillKey.toLowerCase(Locale.ROOT), map);
            }
        }

        loadAttributes(cfg);
    }

    private void loadAttributes(FileConfiguration cfg) {
        attributeTables.clear();
        usedAttributes.clear();
        Registry<Attribute> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE);
        for (Skill s : Skill.all()) {
            TreeMap<Integer, Map<Attribute, Double>> table = new TreeMap<>();
            ConfigurationSection sec = cfg.getConfigurationSection("attributes." + s.key());
            if (sec != null) {
                for (String levelKey : sec.getKeys(false)) {
                    int level;
                    try {
                        level = Integer.parseInt(levelKey);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    ConfigurationSection values = sec.getConfigurationSection(levelKey);
                    if (values == null) continue;
                    Map<Attribute, Double> map = new HashMap<>();
                    for (String attrName : values.getKeys(false)) {
                        Attribute attr = registry.get(NamespacedKey.minecraft(attrName.toLowerCase(Locale.ROOT)
                                .replace("minecraft:", "").replace("generic.", "").replace("player.", "")));
                        if (attr == null) {
                            plugin.getLogger().warning("attributes." + s.key() + "." + levelKey
                                    + ": неизвестный атрибут '" + attrName + "'");
                            continue;
                        }
                        map.put(attr, values.getDouble(attrName));
                        usedAttributes.add(attr);
                    }
                    table.put(level, map);
                }
            }
            attributeTables.add(table);
        }
    }

    // ================================================================ общие проверки

    public int maxLevel() {
        return maxLevel;
    }

    public String skillName(Skill skill) {
        return skillNames[skill.ordinal()];
    }

    public boolean isDisabledWorld(World world) {
        return world != null && disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    private boolean isCreativeLike(Player p) {
        return p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
    }

    /** Действуют ли на игрока ограничения сейчас. */
    public boolean restrictionsApply(Player p) {
        if (isDisabledWorld(p.getWorld())) return false;
        if (p.hasPermission("playerprogress.bypass")) return false;
        return !(bypassCreative && isCreativeLike(p));
    }

    public PlayerData data(Player p) {
        return data.get(p, 1);
    }

    /** Уровень с учётом пермишенов playerprogress.<познание>.* и playerprogress.<познание>.<N>. */
    public int effectiveLevel(Player p, Skill skill) {
        int level = data(p).level(skill);
        String prefix = "playerprogress." + skill.key() + ".";
        if (p.hasPermission(prefix + "*")) return maxLevel;
        for (int n = maxLevel; n > level; n--) {
            if (p.hasPermission(prefix + n)) return n;
        }
        return level;
    }

    /** Первое невыполненное требование или null, если всё в порядке. */
    public Missing check(Player p, int[] requirements) {
        if (requirements == null || !restrictionsApply(p)) return null;
        for (Skill s : Skill.all()) {
            int need = requirements[s.ordinal()];
            if (need <= 0) continue;
            int have = effectiveLevel(p, s);
            if (have < need) return new Missing(s, need, have);
        }
        return null;
    }

    /** Проверка + сообщение. true — действие запрещено. */
    public boolean denied(Player p, int[] requirements) {
        Missing m = check(p, requirements);
        if (m == null) return false;
        messages.sendLimited(p, "insufficient-level",
                "skill", skillName(m.skill()),
                "required", String.valueOf(m.required()),
                "current", String.valueOf(m.current()));
        return true;
    }

    // ================================================================ уровни и очки

    public int requiredPoints(Skill skill, int targetLevel) {
        Integer custom = customPoints.get(skill.ordinal()).get(targetLevel);
        if (custom != null) return custom;
        return pointsPerLevel[skill.ordinal()] * targetLevel;
    }

    public int xpCost(int targetLevel) {
        return Math.max(0, (targetLevel - 1) * xpPerLevel);
    }

    /** Устанавливает уровень (с ограничением 1..max), обновляет атрибуты. Возвращает новый уровень. */
    public int setLevel(PlayerData d, Skill skill, int value) {
        int clamped = Math.max(1, Math.min(maxLevel, value));
        d.level(skill, clamped);
        data.markDirty(d);
        Player online = Bukkit.getPlayer(d.uuid());
        if (online != null) updateAttributes(online);
        return clamped;
    }

    public void setPoints(PlayerData d, Skill skill, int value) {
        d.points(skill, value);
        data.markDirty(d);
    }

    /** Начисляет очки за действие из раздела actions конфига. multiplier — сколько раз действие выполнено. */
    public void award(Player p, Skill skill, String action, int multiplier) {
        if (multiplier <= 0) return;
        Map<String, Integer> map = actionValues.get(skill.key());
        int perAction = map == null ? 0 : map.getOrDefault(action, 0);
        if (perAction <= 0) return;
        if (isDisabledWorld(p.getWorld()) || isCreativeLike(p)) return;
        PlayerData d = data(p);
        int level = d.level(skill);
        if (stopAtMax && level >= maxLevel) return;

        int before = d.points(skill);
        long sum = (long) before + (long) perAction * multiplier;
        int after = (int) Math.min(Integer.MAX_VALUE, sum);
        setPoints(d, skill, after);

        int required = level >= maxLevel ? 0 : requiredPoints(skill, level + 1);
        if (actionbar) {
            messages.actionBar(p, "points-actionbar",
                    "amount", String.valueOf(after - before),
                    "skill", skillName(skill),
                    "points", String.valueOf(after),
                    "required", String.valueOf(required));
        }
        if (level < maxLevel && before < required && after >= required) {
            messages.send(p, "points-ready", "skill", skillName(skill));
        }
    }

    /** Повышение уровня в меню: тратит очки и уровни опыта. */
    public UpgradeResult upgrade(Player p, Skill skill) {
        if (isDisabledWorld(p.getWorld())) return UpgradeResult.DISABLED_WORLD;
        PlayerData d = data(p);
        int level = d.level(skill);
        if (level >= maxLevel) return UpgradeResult.MAX_LEVEL;
        int target = level + 1;
        int needPoints = requiredPoints(skill, target);
        int needXp = xpCost(target);
        if (d.points(skill) < needPoints) return UpgradeResult.NOT_ENOUGH_POINTS;
        if (p.getLevel() < needXp) return UpgradeResult.NOT_ENOUGH_XP;
        p.setLevel(p.getLevel() - needXp);
        setPoints(d, skill, d.points(skill) - needPoints);
        setLevel(d, skill, target);
        return UpgradeResult.SUCCESS;
    }

    // ================================================================ атрибуты

    private NamespacedKey modifierKey(Attribute attribute) {
        return new NamespacedKey(plugin, "bonus_" + attribute.getKey().getKey().replace('.', '_'));
    }

    /** Снимает все бонусы плагина и (если мир не отключён) ставит актуальные. */
    public void updateAttributes(Player p) {
        Registry<Attribute> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE);
        String namespace = plugin.getName().toLowerCase(Locale.ROOT);
        for (Attribute attribute : registry) {
            AttributeInstance inst = p.getAttribute(attribute);
            if (inst == null) continue;
            for (AttributeModifier mod : new ArrayList<>(inst.getModifiers())) {
                if (mod.getKey().getNamespace().equals(namespace)) inst.removeModifier(mod);
            }
        }
        if (!isDisabledWorld(p.getWorld())) {
            PlayerData d = data(p);
            Map<Attribute, Double> totals = new HashMap<>();
            for (Skill s : Skill.all()) {
                Map.Entry<Integer, Map<Attribute, Double>> e = attributeTables.get(s.ordinal()).floorEntry(d.level(s));
                if (e == null) continue;
                for (Map.Entry<Attribute, Double> av : e.getValue().entrySet()) {
                    totals.merge(av.getKey(), av.getValue(), Double::sum);
                }
            }
            for (Map.Entry<Attribute, Double> e : totals.entrySet()) {
                if (e.getValue() == 0.0) continue;
                AttributeInstance inst = p.getAttribute(e.getKey());
                if (inst == null) continue;
                inst.addModifier(new AttributeModifier(modifierKey(e.getKey()), e.getValue(),
                        AttributeModifier.Operation.ADD_NUMBER));
            }
        }
        clampHealth(p, registry);
    }

    private void clampHealth(Player p, Registry<Attribute> registry) {
        Attribute maxHealth = registry.get(NamespacedKey.minecraft("max_health"));
        if (maxHealth == null) return;
        AttributeInstance inst = p.getAttribute(maxHealth);
        if (inst != null && p.getHealth() > inst.getValue()) p.setHealth(inst.getValue());
    }
}
