package com.steelrework.playerprogress.listener;

import com.steelrework.playerprogress.ProgressManager;
import com.steelrework.playerprogress.Skill;
import com.steelrework.playerprogress.util.MaterialPatterns;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Начисление очков за действия игроков. */
public final class ActionListener implements Listener {

    private static final int MAX_REMEMBERED_PLACEMENTS = 100_000;

    private final Plugin plugin;
    private final ProgressManager progress;
    private final PlacedBlockTracker placed;

    private MaterialPatterns stone, ore, logs, flowers, mushrooms, crops, naturalHarvest;
    private Set<CreatureSpawnEvent.SpawnReason> excludedReasons = EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
    private double armorStep = 5;
    private long distanceStepCm = 10_000;
    private final List<Statistic> distanceStats = new ArrayList<>();

    private final Map<UUID, Double> armorDamage = new HashMap<>();
    private final Map<UUID, Long> lastDistance = new HashMap<>();
    private final Map<UUID, Long> distanceAcc = new HashMap<>();
    private final Map<String, UUID> brewers = new HashMap<>();
    /** Места, за установку блока в которых уже давали очки (против "поставил-сломал-поставил"). */
    private final Map<String, Boolean> rewardedPlacements = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_REMEMBERED_PLACEMENTS;
        }
    };

    private BukkitTask distanceTask;

    public ActionListener(Plugin plugin, ProgressManager progress, PlacedBlockTracker placed) {
        this.plugin = plugin;
        this.progress = progress;
        this.placed = placed;
    }

    public void load(FileConfiguration cfg) {
        String base = "action-settings.";
        stone = MaterialPatterns.of(cfg.getStringList(base + "stone-blocks"));
        ore = MaterialPatterns.of(cfg.getStringList(base + "ore-blocks"));
        logs = MaterialPatterns.of(cfg.getStringList(base + "log-blocks"));
        flowers = MaterialPatterns.of(cfg.getStringList(base + "flower-blocks"));
        mushrooms = MaterialPatterns.of(cfg.getStringList(base + "mushroom-blocks"));
        crops = MaterialPatterns.of(cfg.getStringList(base + "crop-blocks"));
        naturalHarvest = MaterialPatterns.of(cfg.getStringList(base + "natural-harvest-blocks"));
        armorStep = Math.max(0.5, cfg.getDouble(base + "armor-damage-step", 5));
        distanceStepCm = Math.max(1, cfg.getLong(base + "distance-step", 100)) * 100L;

        excludedReasons = EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
        for (String r : cfg.getStringList(base + "excluded-spawn-reasons")) {
            try {
                excludedReasons.add(CreatureSpawnEvent.SpawnReason.valueOf(r.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Неизвестная причина появления моба: " + r);
            }
        }

        distanceStats.clear();
        for (String name : cfg.getStringList(base + "distance-statistics")) {
            try {
                distanceStats.add(Statistic.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().info("Статистика " + name + " отсутствует в этой версии — пропущена.");
            }
        }
        lastDistance.clear();

        if (distanceTask != null) distanceTask.cancel();
        long period = Math.max(1, cfg.getLong(base + "distance-check-seconds", 5)) * 20L;
        distanceTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkDistance, period, period);
    }

    public void stop() {
        if (distanceTask != null) distanceTask.cancel();
    }

    public void forget(UUID uuid) {
        armorDamage.remove(uuid);
        lastDistance.remove(uuid);
        distanceAcc.remove(uuid);
    }

    // ---------------------------------------------------------------- Выносливость

    private long distance(Player p) {
        long sum = 0;
        for (Statistic s : distanceStats) {
            try {
                sum += p.getStatistic(s);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return sum;
    }

    private void checkDistance() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            long total = distance(p);
            Long previous = lastDistance.put(p.getUniqueId(), total);
            if (previous == null) continue;
            long diff = total - previous;
            if (diff <= 0) continue;
            long acc = distanceAcc.getOrDefault(p.getUniqueId(), 0L) + diff;
            int steps = (int) Math.min(Integer.MAX_VALUE, acc / distanceStepCm);
            distanceAcc.put(p.getUniqueId(), acc % distanceStepCm);
            if (steps > 0) progress.award(p, Skill.AGILITY, "distance", steps);
        }
    }

    // ---------------------------------------------------------------- Атака

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || !(entity instanceof Enemy)) return;
        if (excludedReasons.contains(entity.getEntitySpawnReason())) return;
        progress.award(killer, Skill.ATTACK, "kill-hostile", 1);
    }

    // ---------------------------------------------------------------- Защита

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Entity damager = event.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() == victim) return;
        if (damager == victim) return;

        if (victim.isBlocking()) {
            progress.award(victim, Skill.DEFENSE, "shield-block", 1);
            return;
        }
        if (!wearsArmor(victim)) return;
        double dmg = event.getFinalDamage();
        if (dmg <= 0) return;
        double acc = armorDamage.getOrDefault(victim.getUniqueId(), 0.0) + dmg;
        int steps = (int) (acc / armorStep);
        armorDamage.put(victim.getUniqueId(), acc - steps * armorStep);
        if (steps > 0) progress.award(victim, Skill.DEFENSE, "armor-damage", steps);
    }

    private static boolean wearsArmor(Player p) {
        EntityEquipment eq = p.getEquipment();
        for (ItemStack it : eq.getArmorContents()) {
            if (it != null && !it.getType().isAir()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Магия

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        progress.award(event.getEnchanter(), Skill.MAGIC, "enchant", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() != InventoryType.BREWING) return;
        Location loc = event.getInventory().getLocation();
        if (loc != null && event.getPlayer() instanceof Player p) brewers.put(key(loc.getBlock()), p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        UUID uuid = brewers.get(key(event.getBlock()));
        if (uuid == null) return;
        Player p = plugin.getServer().getPlayer(uuid);
        if (p == null) return;
        BrewerInventory inv = event.getContents();
        int count = 0;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it != null && !it.getType().isAir()) count++;
        }
        progress.award(p, Skill.MAGIC, "brew", count);
    }

    // ---------------------------------------------------------------- Строительство

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        placed.mark(block);
        String k = key(block);
        if (rewardedPlacements.containsKey(k)) return;
        rewardedPlacements.put(k, Boolean.TRUE);
        progress.award(event.getPlayer(), Skill.BUILDING, "place", 1);
    }

    // ---------------------------------------------------------------- Ломание блоков

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player p = event.getPlayer();
        Material type = block.getType();
        boolean byPlayer = placed.isPlaced(block);
        placed.unmark(block);

        if (crops.contains(type)) {
            BlockData data = block.getBlockData();
            if (data instanceof Ageable age && age.getAge() >= age.getMaximumAge()) {
                progress.award(p, Skill.FARMING, "harvest", 1);
            }
            return;
        }
        if (byPlayer) return;

        if (naturalHarvest.contains(type)) progress.award(p, Skill.FARMING, "harvest", 1);
        else if (ore.contains(type)) progress.award(p, Skill.MINING, "ore", 1);
        else if (stone.contains(type)) progress.award(p, Skill.MINING, "stone", 1);
        else if (logs.contains(type)) progress.award(p, Skill.GATHERING, "log", 1);
        else if (flowers.contains(type)) progress.award(p, Skill.GATHERING, "flower", 1);
        else if (mushrooms.contains(type)) progress.award(p, Skill.GATHERING, "mushroom", 1);
    }

    // ---------------------------------------------------------------- Собирательство и Земледелие

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        Material type = event.getHarvestedBlock().getType();
        if (type == Material.SWEET_BERRY_BUSH || type == Material.CAVE_VINES || type == Material.CAVE_VINES_PLANT) {
            progress.award(event.getPlayer(), Skill.GATHERING, "berry", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item) {
            progress.award(event.getPlayer(), Skill.GATHERING, "fish", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player p) progress.award(p, Skill.FARMING, "breed", 1);
    }

    // ----------------------------------------------------------------

    private static String key(Block b) {
        return b.getWorld().getName() + ':' + b.getX() + ':' + b.getY() + ':' + b.getZ();
    }
}
