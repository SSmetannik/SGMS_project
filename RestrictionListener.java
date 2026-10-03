package com.steelrework.playerprogress.listener;

import com.steelrework.playerprogress.Messages;
import com.steelrework.playerprogress.ProgressManager;
import com.steelrework.playerprogress.Restrictions;
import com.steelrework.playerprogress.util.Text;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;

/** Запрет предметов и блоков, если уровня познания не хватает. */
public final class RestrictionListener implements Listener {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final Plugin plugin;
    private final ProgressManager progress;
    private final Restrictions restrictions;
    private final Messages messages;

    private boolean physical = true;
    private BukkitTask armorTask;

    public RestrictionListener(Plugin plugin, ProgressManager progress, Restrictions restrictions, Messages messages) {
        this.plugin = plugin;
        this.progress = progress;
        this.restrictions = restrictions;
        this.messages = messages;
    }

    public void load(org.bukkit.configuration.file.FileConfiguration cfg) {
        physical = cfg.getBoolean("settings.restrict-physical-interaction", true);
        if (armorTask != null) armorTask.cancel();
        armorTask = null;
        if (cfg.getBoolean("settings.armor-check", true)) {
            armorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkAllArmor, 20L, 20L);
        }
    }

    public void stop() {
        if (armorTask != null) armorTask.cancel();
    }

    private boolean deniedItem(Player p, ItemStack item) {
        return progress.denied(p, restrictions.forItem(item));
    }

    private boolean deniedBlock(Player p, Block block) {
        return progress.denied(p, restrictions.forBlock(block));
    }

    private boolean deniedEntity(Player p, Entity entity) {
        return progress.denied(p, restrictions.forEntity(entity));
    }

    private static ItemStack handItem(Player p, EquipmentSlot hand) {
        PlayerInventory inv = p.getInventory();
        return hand == EquipmentSlot.OFF_HAND ? inv.getItemInOffHand() : inv.getItemInMainHand();
    }

    // ================================================================ клики

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        Action action = event.getAction();
        Block block = event.getClickedBlock();

        if (action == Action.PHYSICAL) {
            if (physical && block != null && progress.check(p, restrictions.forBlock(block)) != null) {
                event.setCancelled(true);
            }
            return;
        }

        if (action == Action.RIGHT_CLICK_BLOCK && block != null && event.useInteractedBlock() != Event.Result.DENY) {
            // Shift + ПКМ с предметом в руке не открывает блок — тогда проверяем только предмет
            boolean opensBlock = !(p.isSneaking() && event.getItem() != null);
            if (opensBlock && deniedBlock(p, block)) {
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
                return;
            }
        }

        if ((action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) && event.getItem() != null) {
            ItemStack item = event.getItem();
            boolean placing = action == Action.RIGHT_CLICK_BLOCK
                    && progress.check(p, restrictions.forPlacing(item)) != null;
            if (placing) {
                progress.denied(p, restrictions.forPlacing(item));
                event.setUseItemInHand(Event.Result.DENY);
                return;
            }
            if (deniedItem(p, item)) {
                event.setUseItemInHand(Event.Result.DENY);
                if (action == Action.RIGHT_CLICK_AIR) event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        ItemStack item = handItem(p, event.getHand());
        if (deniedEntity(p, event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        if (!item.getType().isAir() && deniedItem(p, item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (deniedEntity(event.getPlayer(), event.getRightClicked())) event.setCancelled(true);
    }

    // ================================================================ блоки

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Player p = event.getPlayer();
        if (deniedItem(p, event.getItemInHand()) || deniedBlock(p, event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (deniedItem(p, p.getInventory().getItemInMainHand()) || deniedBlock(p, event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (progress.denied(p, restrictions.forPlacing(event.getItemInHand()))
                || progress.denied(p, restrictions.forMaterialBlock(event.getBlockPlaced().getType()))) {
            event.setCancelled(true);
        }
    }

    // ================================================================ сущности-"блоки"

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player p = event.getPlayer();
        if (p != null && deniedEntity(p, event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player p = event.getPlayer();
        if (p != null && deniedEntity(p, event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player p && deniedEntity(p, event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getAttacker() instanceof Player p && deniedEntity(p, event.getVehicle())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player p && deniedEntity(p, event.getVehicle())) event.setCancelled(true);
    }

    // ================================================================ бой и использование предметов

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (deniedItem(p, p.getInventory().getItemInMainHand()) || deniedEntity(p, event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player p && deniedItem(p, event.getBow())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (deniedItem(event.getPlayer(), event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.FISHING) return;
        Player p = event.getPlayer();
        ItemStack rod = p.getInventory().getItemInMainHand();
        if (rod.getType() != Material.FISHING_ROD) rod = p.getInventory().getItemInOffHand();
        if (deniedItem(p, rod)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (progress.denied(event.getPlayer(), restrictions.forMaterialItem(event.getBucket()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (progress.denied(event.getPlayer(), restrictions.forMaterialItem(event.getBucket()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (deniedItem(event.getPlayer(), event.getOriginalBucket())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (deniedItem(event.getPlayer(), event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !(event.getEntity() instanceof Player p)) return;
        if (deniedItem(p, p.getInventory().getChestplate())) event.setCancelled(true);
    }

    // ================================================================ броня

    private void checkAllArmor() {
        for (Player p : plugin.getServer().getOnlinePlayers()) checkArmor(p);
    }

    /** Снимает броню, которую игрок носить не может, и кладёт её в инвентарь (или под ноги). */
    public void checkArmor(Player p) {
        if (!progress.restrictionsApply(p)) return;
        PlayerInventory inv = p.getInventory();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            ProgressManager.Missing m = progress.check(p, restrictions.forItem(item));
            if (m == null) continue;
            inv.setItem(slot, null);
            Map<Integer, ItemStack> left = inv.addItem(item);
            for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
            messages.sendLimited(p, "armor-removed",
                    "item", itemName(item),
                    "skill", progress.skillName(m.skill()),
                    "required", String.valueOf(m.required()),
                    "current", String.valueOf(m.current()));
        }
    }

    private static String itemName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                    .serialize(item.getItemMeta().displayName());
        }
        return Text.plain(item.getType().getKey().getKey().replace('_', ' '));
    }
}
