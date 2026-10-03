package com.steelrework.playerprogress;

import com.steelrework.playerprogress.data.DataManager;
import com.steelrework.playerprogress.data.PlayerData;
import com.steelrework.playerprogress.data.Storage;
import com.steelrework.playerprogress.hook.ItemsAdderHook;
import com.steelrework.playerprogress.listener.ActionListener;
import com.steelrework.playerprogress.listener.PlacedBlockTracker;
import com.steelrework.playerprogress.listener.PlayerListener;
import com.steelrework.playerprogress.listener.RestrictionListener;
import com.steelrework.playerprogress.menu.Menus;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class PlayerProgressPlugin extends JavaPlugin {

    private Messages messages;
    private DataManager data;
    private ProgressManager progress;
    private ItemsAdderHook itemsAdder;
    private Restrictions restrictions;
    private Boosters boosters;
    private PlacedBlockTracker placedTracker;
    private ActionListener actionListener;
    private RestrictionListener restrictionListener;
    private Menus menus;

    private BukkitTask autosaveTask;
    private BukkitTask topTask;

    @Override
    public void onEnable() {
        replaceOutdatedConfig();
        saveDefaultConfig();

        messages = new Messages();
        messages.load(getConfig());

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Не удалось создать папку плагина");
        }
        int maxLevel = Math.max(1, getConfig().getInt("settings.max-level", 32));
        Storage storage = new Storage(getDataFolder(), getLogger());
        data = new DataManager(storage);
        List<PlayerData> migrated = storage.migrateLegacy(getDataFolder(), maxLevel);
        if (!migrated.isEmpty()) {
            data.loadAll(migrated, true);
            getLogger().info("Перенесены уровни " + migrated.size() + " игроков из старой базы data.db.");
        } else {
            data.loadAll(storage.loadAll(1, maxLevel), false);
        }

        itemsAdder = new ItemsAdderHook(getLogger());
        itemsAdder.init();

        progress = new ProgressManager(this, data, messages);
        restrictions = new Restrictions(itemsAdder);
        boosters = new Boosters(progress, messages, itemsAdder);
        placedTracker = new PlacedBlockTracker(this);
        actionListener = new ActionListener(this, progress, placedTracker);
        restrictionListener = new RestrictionListener(this, progress, restrictions, messages);
        menus = new Menus(this);

        registerLevelPermissions();
        loadSettings();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(restrictionListener, this);
        pm.registerEvents(actionListener, this);
        pm.registerEvents(placedTracker, this);
        pm.registerEvents(boosters, this);
        pm.registerEvents(menus, this);
        itemsAdder.registerEvents(this, (player, id) -> progress.denied(player, restrictions.forItemsAdderBlock(id)));

        PluginCommand command = getCommand("playerprogress");
        if (command != null) {
            PlayerProgressCommand executor = new PlayerProgressCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        if (pm.getPlugin("PlaceholderAPI") != null) {
            try {
                new PapiExpansion(this).register();
                getLogger().info("Плейсхолдеры PlaceholderAPI зарегистрированы.");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Не удалось зарегистрировать плейсхолдеры", t);
            }
        }

        for (Player p : getServer().getOnlinePlayers()) {
            progress.data(p);
            progress.updateAttributes(p);
        }
        data.recalculateTops(10);
        getLogger().info("PlayerProgress включён.");
    }

    @Override
    public void onDisable() {
        if (autosaveTask != null) autosaveTask.cancel();
        if (topTask != null) topTask.cancel();
        if (actionListener != null) actionListener.stop();
        if (restrictionListener != null) restrictionListener.stop();
        if (placedTracker != null) placedTracker.flushAll(true);
        if (data != null) data.close();
    }

    /** Конфиг от старой версии несовместим — переименовываем его в config-old.yml и создаём новый. */
    private void replaceOutdatedConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.isConfigurationSection("leveling")) return;
        File old = new File(getDataFolder(), "config-old.yml");
        if (old.exists() && !old.delete()) return;
        if (file.renameTo(old)) {
            getLogger().warning("Найден конфиг старой версии — он переименован в config-old.yml, создан новый config.yml.");
        }
    }

    /** Права playerprogress.<познание>.* и .1 ... .max — по умолчанию ни у кого (даже у операторов). */
    private void registerLevelPermissions() {
        PluginManager pm = getServer().getPluginManager();
        int max = Math.max(1, getConfig().getInt("settings.max-level", 32));
        for (Skill s : Skill.all()) {
            String prefix = "playerprogress." + s.key() + ".";
            addPermission(pm, prefix + "*");
            for (int i = 1; i <= max; i++) addPermission(pm, prefix + i);
        }
    }

    private static void addPermission(PluginManager pm, String name) {
        if (pm.getPermission(name) == null) pm.addPermission(new Permission(name, PermissionDefault.FALSE));
    }

    private void loadSettings() {
        messages.load(getConfig());
        progress.load(getConfig());
        restrictions.load(getConfig(), getLogger());
        boosters.load(getConfig(), getServer().getPluginManager());
        actionListener.load(getConfig());
        restrictionListener.load(getConfig());

        if (autosaveTask != null) autosaveTask.cancel();
        long save = Math.max(10, getConfig().getLong("settings.autosave-seconds", 60)) * 20L;
        autosaveTask = getServer().getScheduler().runTaskTimer(this, data::saveDirtyAsync, save, save);

        if (topTask != null) topTask.cancel();
        long tops = Math.max(1, getConfig().getLong("settings.top-update-minutes", 5)) * 60L * 20L;
        topTask = getServer().getScheduler().runTaskTimer(this, () -> data.recalculateTops(10), tops, tops);
    }

    public void reload() {
        reloadConfig();
        registerLevelPermissions();
        loadSettings();
        for (Player p : getServer().getOnlinePlayers()) progress.updateAttributes(p);
        data.recalculateTops(10);
    }

    /** Выдать усилитель (команда и админ-меню). */
    public void giveBooster(CommandSender sender, Player target, String key, int amount) {
        Boosters.Booster booster = boosters.get(key);
        if (booster == null) {
            messages.send(sender, "invalid-booster", "list", String.join(", ", boosters.keys()));
            return;
        }
        if (!itemsAdder.enabled()) {
            messages.send(sender, "itemsadder-missing");
            return;
        }
        int left = amount;
        while (left > 0) {
            ItemStack item = boosters.createItem(booster, left);
            if (item == null) {
                messages.send(sender, "booster-not-loaded", "item", booster.itemId());
                return;
            }
            left -= item.getAmount();
            Map<Integer, ItemStack> rest = target.getInventory().addItem(item);
            for (ItemStack r : rest.values()) target.getWorld().dropItemNaturally(target.getLocation(), r);
        }
        messages.send(sender, "booster-given", "amount", String.valueOf(amount),
                "booster", booster.key(), "player", target.getName());
    }

    public void forgetPlayer(UUID uuid) {
        messages.forget(uuid);
        boosters.forget(uuid);
        actionListener.forget(uuid);
    }

    public Messages messages() {
        return messages;
    }

    public DataManager data() {
        return data;
    }

    public ProgressManager progress() {
        return progress;
    }

    public Boosters boosters() {
        return boosters;
    }

    public Menus menus() {
        return menus;
    }
}
