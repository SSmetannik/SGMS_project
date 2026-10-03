package com.steelrework.playerprogress;

import com.steelrework.playerprogress.data.PlayerData;
import com.steelrework.playerprogress.hook.ItemsAdderHook;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Усилители познаний — предметы ItemsAdder, применяются ПКМ. */
public final class Boosters implements Listener {

    public static final String RANDOM = "random";

    public record Booster(String key, String itemId, double increaseChance, double decreaseChance) {
    }

    private final ProgressManager progress;
    private final Messages messages;
    private final ItemsAdderHook itemsAdder;

    private final Map<String, Booster> byKey = new LinkedHashMap<>();
    private final Map<String, Booster> byItemId = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public Boosters(ProgressManager progress, Messages messages, ItemsAdderHook itemsAdder) {
        this.progress = progress;
        this.messages = messages;
        this.itemsAdder = itemsAdder;
    }

    public void load(FileConfiguration cfg, PluginManager pm) {
        byKey.clear();
        byItemId.clear();
        ConfigurationSection root = cfg.getConfigurationSection("boosters");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            String k = key.toLowerCase(Locale.ROOT);
            if (!k.equals(RANDOM) && Skill.byKey(k) == null) continue;
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;
            String item = sec.getString("item", "");
            if (item.isBlank()) continue;
            Booster b = new Booster(k, item.toLowerCase(Locale.ROOT),
                    Math.max(0, sec.getDouble("increase-chance", 80)),
                    Math.max(0, sec.getDouble("decrease-chance", 5)));
            byKey.put(k, b);
            byItemId.put(b.itemId(), b);
            String perm = "playerprogress.booster." + k;
            if (pm.getPermission(perm) == null) {
                pm.addPermission(new Permission(perm, "Использование усилителя " + k, PermissionDefault.TRUE));
            }
        }
    }

    public List<String> keys() {
        return new ArrayList<>(byKey.keySet());
    }

    public Booster get(String key) {
        return key == null ? null : byKey.get(key.toLowerCase(Locale.ROOT));
    }

    public ItemStack createItem(Booster booster, int amount) {
        ItemStack item = itemsAdder.create(booster.itemId());
        if (item == null) return null;
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
        return item;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || byItemId.isEmpty()) return;
        String id = itemsAdder.itemId(item);
        if (id == null) return;
        Booster booster = byItemId.get(id.toLowerCase(Locale.ROOT));
        if (booster == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        // защита от двойного срабатывания одного клика
        long now = System.currentTimeMillis();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < 250) return;
        lastUse.put(player.getUniqueId(), now);

        if (!player.hasPermission("playerprogress.booster." + booster.key())) {
            messages.sendLimited(player, "booster-no-permission");
            return;
        }
        if (progress.isDisabledWorld(player.getWorld())) {
            messages.sendLimited(player, "disabled-world");
            return;
        }

        PlayerData d = progress.data(player);
        int max = progress.maxLevel();
        Skill skill;
        if (booster.key().equals(RANDOM)) {
            List<Skill> candidates = new ArrayList<>();
            for (Skill s : Skill.all()) if (d.level(s) < max) candidates.add(s);
            if (candidates.isEmpty()) {
                messages.sendLimited(player, "booster-all-maxed");
                return;
            }
            skill = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        } else {
            skill = Skill.byKey(booster.key());
            if (skill == null) return;
            boolean allMaxed = true;
            for (Skill s : Skill.all()) if (d.level(s) < max) allMaxed = false;
            if (allMaxed) {
                messages.sendLimited(player, "booster-all-maxed");
                return;
            }
            if (d.level(skill) >= max) {
                messages.sendLimited(player, "booster-maxed", "skill", progress.skillName(skill));
                return;
            }
        }

        // забираем один предмет из той руки, которой нажали
        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        ItemStack inHand = player.getInventory().getItem(hand);
        if (inHand.getAmount() <= 1) {
            player.getInventory().setItem(hand, null);
        } else {
            inHand.setAmount(inHand.getAmount() - 1);
            player.getInventory().setItem(hand, inHand);
        }

        double roll = ThreadLocalRandom.current().nextDouble(100.0);
        int level = d.level(skill);
        if (roll < booster.increaseChance()) {
            int now2 = progress.setLevel(d, skill, level + 1);
            messages.send(player, "booster-up", "skill", progress.skillName(skill), "level", String.valueOf(now2));
        } else if (roll < booster.increaseChance() + booster.decreaseChance() && level > 1) {
            int now2 = progress.setLevel(d, skill, level - 1);
            messages.send(player, "booster-down", "skill", progress.skillName(skill), "level", String.valueOf(now2));
        } else {
            messages.send(player, "booster-nothing", "skill", progress.skillName(skill));
        }
    }

    public void forget(UUID uuid) {
        lastUse.remove(uuid);
    }
}
