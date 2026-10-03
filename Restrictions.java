package com.steelrework.playerprogress;

import com.steelrework.playerprogress.hook.ItemsAdderHook;
import com.steelrework.playerprogress.util.MaterialPatterns;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Требования к уровням для предметов и блоков.
 * Требование — массив int по познаниям (индекс = Skill.ordinal()), 0 — нет требования.
 */
public final class Restrictions {

    private final ItemsAdderHook itemsAdder;
    private final Map<Material, int[]> items = new EnumMap<>(Material.class);
    private final Map<Material, int[]> blocks = new EnumMap<>(Material.class);
    private final Map<String, int[]> iaItems = new HashMap<>();
    private final Map<String, int[]> iaBlocks = new HashMap<>();

    public Restrictions(ItemsAdderHook itemsAdder) {
        this.itemsAdder = itemsAdder;
    }

    public void load(FileConfiguration config, Logger logger) {
        items.clear();
        blocks.clear();
        iaItems.clear();
        iaBlocks.clear();
        List<String> unknown = new ArrayList<>();
        loadVanilla(config.getConfigurationSection("restrictions.items"), items, unknown);
        loadVanilla(config.getConfigurationSection("restrictions.blocks"), blocks, unknown);
        loadItemsAdder(config.getConfigurationSection("itemsadder.items"), iaItems, logger);
        loadItemsAdder(config.getConfigurationSection("itemsadder.blocks"), iaBlocks, logger);
        if (!unknown.isEmpty()) {
            logger.info("Пропущены несуществующие в этой версии предметы/блоки: " + String.join(", ", unknown));
        }
    }

    private static void loadVanilla(ConfigurationSection root, Map<Material, int[]> target, List<String> unknown) {
        if (root == null) return;
        for (String skillKey : root.getKeys(false)) {
            Skill skill = Skill.byKey(skillKey);
            ConfigurationSection sec = root.getConfigurationSection(skillKey);
            if (skill == null || sec == null) continue;
            for (String pattern : sec.getKeys(false)) {
                int level = sec.getInt(pattern);
                if (level <= 0) continue;
                List<Material> materials = MaterialPatterns.expand(pattern);
                if (materials.isEmpty()) {
                    unknown.add(pattern);
                    continue;
                }
                for (Material m : materials) {
                    int[] req = target.computeIfAbsent(m, k -> new int[Skill.all().length]);
                    req[skill.ordinal()] = Math.max(req[skill.ordinal()], level);
                }
            }
        }
    }

    private static void loadItemsAdder(ConfigurationSection root, Map<String, int[]> target, Logger logger) {
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) continue;
            int[] req = new int[Skill.all().length];
            for (String skillKey : sec.getKeys(false)) {
                Skill skill = Skill.byKey(skillKey);
                if (skill == null) {
                    logger.warning("itemsadder." + id + ": неизвестное познание '" + skillKey + "'");
                    continue;
                }
                req[skill.ordinal()] = Math.max(0, sec.getInt(skillKey));
            }
            target.put(id.toLowerCase(Locale.ROOT), req);
        }
    }

    /** Требования, чтобы пользоваться предметом (бить, ломать им, ПКМ, есть, надевать). */
    public int[] forItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String id = itemsAdder.itemId(item);
        if (id != null) return iaItems.get(id.toLowerCase(Locale.ROOT));
        return items.get(item.getType());
    }

    /** Требования, чтобы поставить предмет как блок/сущность. */
    public int[] forPlacing(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        String id = itemsAdder.itemId(item);
        if (id != null) return iaBlocks.get(id.toLowerCase(Locale.ROOT));
        return blocks.get(item.getType());
    }

    public int[] forMaterialBlock(Material material) {
        return material == null ? null : blocks.get(material);
    }

    public int[] forMaterialItem(Material material) {
        return material == null ? null : items.get(material);
    }

    /** Требования к блоку в мире (ломать, взаимодействовать). */
    public int[] forBlock(Block block) {
        if (block == null) return null;
        String id = itemsAdder.blockId(block);
        if (id != null) return iaBlocks.get(id.toLowerCase(Locale.ROOT));
        return blocks.get(block.getType());
    }

    /** Требования к сущности-"блоку": стойки, рамки, картины, вагонетки, мебель ItemsAdder. */
    public int[] forEntity(Entity entity) {
        if (entity == null) return null;
        String id = itemsAdder.furnitureId(entity);
        if (id != null) return iaBlocks.get(id.toLowerCase(Locale.ROOT));
        Material m = Material.getMaterial(entity.getType().getKey().getKey().toUpperCase(Locale.ROOT));
        return m == null ? null : blocks.get(m);
    }

    public int[] forItemsAdderBlock(String id) {
        return id == null ? null : iaBlocks.get(id.toLowerCase(Locale.ROOT));
    }
}
