package com.steelrework.playerprogress.menu;

import com.steelrework.playerprogress.Boosters;
import com.steelrework.playerprogress.PlayerProgressPlugin;
import com.steelrework.playerprogress.ProgressManager;
import com.steelrework.playerprogress.Skill;
import com.steelrework.playerprogress.data.DataManager;
import com.steelrework.playerprogress.data.PlayerData;
import com.steelrework.playerprogress.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Меню прокачки, топов и админ-меню. */
public final class Menus implements Listener {

    private static final int[] SKILL_SLOTS = {10, 12, 14, 16, 28, 30, 32, 34};
    private static final int TOTAL_SLOT = 22;
    private static final int INFO_SLOT = 4;
    private static final int TOPS_SLOT = 49;
    private static final int ADMIN_SLOT = 45;
    private static final int PLAYERS_PER_PAGE = 45;

    private final PlayerProgressPlugin plugin;

    public Menus(PlayerProgressPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private ProgressManager progress() {
        return plugin.progress();
    }

    private String text(String key) {
        return cfg().getString("menu." + key, "");
    }

    // ================================================================ открытие

    public void openMain(Player viewer) {
        open(viewer, new MenuHolder(MenuHolder.Type.MAIN, viewer.getUniqueId(), 0), text("title"));
    }

    public void openTops(Player viewer) {
        open(viewer, new MenuHolder(MenuHolder.Type.TOPS, viewer.getUniqueId(), 0), text("tops-title"));
    }

    public void openAdminPlayers(Player viewer, int page) {
        open(viewer, new MenuHolder(MenuHolder.Type.ADMIN_PLAYERS, null, page), text("admin-title"));
    }

    public void openAdminEdit(Player viewer, UUID target) {
        PlayerData d = plugin.data().get(target);
        String name = d == null ? "?" : d.name();
        open(viewer, new MenuHolder(MenuHolder.Type.ADMIN_EDIT, target, 0),
                Text.replace(text("admin-edit-title"), "player", name));
    }

    private void open(Player viewer, MenuHolder holder, String title) {
        Inventory inv = Bukkit.createInventory(holder, 54, Text.parse(title));
        holder.inventory(inv);
        render(viewer, holder);
        viewer.openInventory(inv);
    }

    private void render(Player viewer, MenuHolder holder) {
        Inventory inv = holder.getInventory();
        inv.clear();
        holder.actions().clear();
        ItemStack filler = icon(material(text("filler"), Material.GRAY_STAINED_GLASS_PANE), " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        switch (holder.type()) {
            case MAIN -> renderMain(viewer, holder, inv);
            case TOPS -> renderTops(holder, inv);
            case ADMIN_PLAYERS -> renderAdminPlayers(holder, inv);
            case ADMIN_EDIT -> renderAdminEdit(holder, inv);
        }
    }

    // ================================================================ главное меню

    private void renderMain(Player viewer, MenuHolder holder, Inventory inv) {
        PlayerData d = progress().data(viewer);
        int max = progress().maxLevel();
        boolean disabled = progress().isDisabledWorld(viewer.getWorld());

        for (Skill s : Skill.all()) {
            int level = d.level(s);
            int target = Math.min(max, level + 1);
            int required = level >= max ? 0 : progress().requiredPoints(s, target);
            int xp = level >= max ? 0 : progress().xpCost(target);
            String status;
            if (disabled) status = text("status-disabled");
            else if (level >= max) status = text("status-max");
            else if (d.points(s) >= required && viewer.getLevel() >= xp) status = text("status-ready");
            else status = text("status-not-enough");

            List<String> lore = new ArrayList<>();
            for (String line : cfg().getStringList("menu.skill-lore")) {
                lore.add(Text.replace(line,
                        "level", String.valueOf(level),
                        "max", String.valueOf(max),
                        "points", String.valueOf(d.points(s)),
                        "required", level >= max ? "-" : String.valueOf(required),
                        "xp", level >= max ? "-" : String.valueOf(xp),
                        "status", status));
            }
            int slot = SKILL_SLOTS[s.ordinal()];
            ItemStack item = icon(skillIcon(s), progress().skillName(s), lore);
            item.setAmount(Math.max(1, Math.min(64, level)));
            inv.setItem(slot, item);
            holder.actions().put(slot, s.key());
        }

        List<String> info = new ArrayList<>();
        int rank = plugin.data().rank(DataManager.TOTAL, viewer.getUniqueId());
        for (String line : cfg().getStringList("menu.info-lore")) {
            info.add(Text.replace(line, "total", String.valueOf(d.totalLevel()),
                    "rank", rank == 0 ? "-" : String.valueOf(rank)));
        }
        inv.setItem(INFO_SLOT, head(viewer, Text.replace(text("info-name"), "player", viewer.getName()), info));

        if (viewer.hasPermission("playerprogress.top")) {
            inv.setItem(TOPS_SLOT, icon(Material.GOLD_INGOT, text("tops-button"), List.of()));
            holder.actions().put(TOPS_SLOT, "tops");
        }
        if (viewer.hasPermission("playerprogress.admin")) {
            inv.setItem(ADMIN_SLOT, icon(Material.COMMAND_BLOCK, text("admin-button"), List.of()));
            holder.actions().put(ADMIN_SLOT, "admin");
        }
    }

    // ================================================================ топы

    private void renderTops(MenuHolder holder, Inventory inv) {
        for (Skill s : Skill.all()) {
            inv.setItem(SKILL_SLOTS[s.ordinal()], icon(skillIcon(s), progress().skillName(s), topLore(s.key())));
        }
        inv.setItem(TOTAL_SLOT, icon(Material.NETHER_STAR, text("total-name"), topLore(DataManager.TOTAL)));
        inv.setItem(TOPS_SLOT, icon(Material.ARROW, text("back-button"), List.of()));
        holder.actions().put(TOPS_SLOT, "back");
    }

    private List<String> topLore(String key) {
        List<DataManager.TopEntry> top = plugin.data().top(key);
        List<String> lore = new ArrayList<>();
        if (top.isEmpty()) {
            lore.add(text("top-empty"));
            return lore;
        }
        for (int i = 0; i < top.size(); i++) {
            DataManager.TopEntry e = top.get(i);
            lore.add(Text.replace(text("top-line"), "place", String.valueOf(i + 1),
                    "player", e.name(), "value", String.valueOf(e.value())));
        }
        return lore;
    }

    // ================================================================ админ-меню

    private void renderAdminPlayers(MenuHolder holder, Inventory inv) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int pages = Math.max(1, (online.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        int page = Math.max(0, Math.min(holder.page(), pages - 1));
        for (int i = 0; i < PLAYERS_PER_PAGE; i++) inv.setItem(i, null);
        for (int i = 0; i < PLAYERS_PER_PAGE; i++) {
            int index = page * PLAYERS_PER_PAGE + i;
            if (index >= online.size()) break;
            Player p = online.get(index);
            PlayerData d = progress().data(p);
            inv.setItem(i, head(p, "&e" + p.getName(), List.of("&7Сумма уровней: &f" + d.totalLevel())));
            holder.actions().put(i, "player:" + p.getUniqueId());
        }
        if (page > 0) {
            inv.setItem(45, icon(Material.ARROW, "&e<-", List.of()));
            holder.actions().put(45, "page:" + (page - 1));
        }
        if (page < pages - 1) {
            inv.setItem(53, icon(Material.ARROW, "&e->", List.of()));
            holder.actions().put(53, "page:" + (page + 1));
        }
        inv.setItem(49, icon(Material.BARRIER, text("back-button"), List.of()));
        holder.actions().put(49, "back");
    }

    private void renderAdminEdit(MenuHolder holder, Inventory inv) {
        PlayerData d = plugin.data().get(holder.target());
        if (d == null) return;
        OfflinePlayer target = Bukkit.getOfflinePlayer(holder.target());
        inv.setItem(INFO_SLOT, head(target, "&e" + d.name(), List.of("&7Сумма уровней: &f" + d.totalLevel())));
        for (Skill s : Skill.all()) {
            List<String> lore = new ArrayList<>();
            for (String line : cfg().getStringList("menu.admin-skill-lore")) {
                lore.add(Text.replace(line, "level", String.valueOf(d.level(s)), "points", String.valueOf(d.points(s))));
            }
            int slot = SKILL_SLOTS[s.ordinal()];
            ItemStack item = icon(skillIcon(s), progress().skillName(s), lore);
            item.setAmount(Math.max(1, Math.min(64, d.level(s))));
            inv.setItem(slot, item);
            holder.actions().put(slot, s.key());
        }
        int slot = 45;
        for (String key : plugin.boosters().keys()) {
            if (slot > 53) break;
            Boosters.Booster b = plugin.boosters().get(key);
            ItemStack item = plugin.boosters().createItem(b, 1);
            if (item == null) item = icon(Material.BARRIER, "&c" + b.itemId(), List.of());
            ItemMeta meta = item.getItemMeta();
            List<String> lore = cfg().getStringList("menu.admin-booster-lore");
            if (meta != null) {
                meta.lore(Text.itemLines(lore));
                item.setItemMeta(meta);
            }
            inv.setItem(slot, item);
            holder.actions().put(slot, "booster:" + key);
            slot++;
        }
        inv.setItem(0, icon(Material.ARROW, text("back-button"), List.of()));
        holder.actions().put(0, "back");
    }

    // ================================================================ клики

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        String action = holder.actions().get(event.getRawSlot());
        if (action == null) return;

        switch (holder.type()) {
            case MAIN -> clickMain(viewer, holder, action);
            case TOPS -> {
                if (action.equals("back")) openMain(viewer);
            }
            case ADMIN_PLAYERS -> clickAdminPlayers(viewer, action);
            case ADMIN_EDIT -> clickAdminEdit(viewer, holder, action, event.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    private void clickMain(Player viewer, MenuHolder holder, String action) {
        if (action.equals("tops")) {
            openTops(viewer);
            return;
        }
        if (action.equals("admin")) {
            if (viewer.hasPermission("playerprogress.admin")) openAdminPlayers(viewer, 0);
            return;
        }
        Skill skill = Skill.byKey(action);
        if (skill == null) return;
        String name = progress().skillName(skill);
        switch (progress().upgrade(viewer, skill)) {
            case SUCCESS -> {
                int level = progress().data(viewer).level(skill);
                plugin.messages().send(viewer, "upgraded", "skill", name, "level", String.valueOf(level));
                viewer.playSound(viewer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
            case MAX_LEVEL -> plugin.messages().sendLimited(viewer, "upgrade-max", "skill", name);
            case NOT_ENOUGH_POINTS -> {
                PlayerData d = progress().data(viewer);
                int target = d.level(skill) + 1;
                plugin.messages().sendLimited(viewer, "upgrade-no-points",
                        "required", String.valueOf(progress().requiredPoints(skill, target)),
                        "points", String.valueOf(d.points(skill)));
            }
            case NOT_ENOUGH_XP -> {
                int target = progress().data(viewer).level(skill) + 1;
                plugin.messages().sendLimited(viewer, "upgrade-no-xp", "xp", String.valueOf(progress().xpCost(target)));
            }
            case DISABLED_WORLD -> plugin.messages().sendLimited(viewer, "disabled-world");
        }
        render(viewer, holder);
    }

    private void clickAdminPlayers(Player viewer, String action) {
        if (!viewer.hasPermission("playerprogress.admin")) {
            viewer.closeInventory();
            return;
        }
        if (action.equals("back")) {
            openMain(viewer);
        } else if (action.startsWith("page:")) {
            openAdminPlayers(viewer, Integer.parseInt(action.substring(5)));
        } else if (action.startsWith("player:")) {
            openAdminEdit(viewer, UUID.fromString(action.substring(7)));
        }
    }

    private void clickAdminEdit(Player viewer, MenuHolder holder, String action, ClickType click) {
        if (!viewer.hasPermission("playerprogress.admin")) {
            viewer.closeInventory();
            return;
        }
        if (action.equals("back")) {
            openAdminPlayers(viewer, 0);
            return;
        }
        PlayerData d = plugin.data().get(holder.target());
        if (d == null) return;
        if (action.startsWith("booster:")) {
            Player target = Bukkit.getPlayer(holder.target());
            if (target == null) {
                plugin.messages().send(viewer, "player-not-found", "player", d.name());
                return;
            }
            plugin.giveBooster(viewer, target, action.substring(8), 1);
            return;
        }
        Skill skill = Skill.byKey(action);
        if (skill == null) return;
        String name = progress().skillName(skill);
        switch (click) {
            case LEFT, RIGHT -> {
                int old = d.level(skill);
                int now = progress().setLevel(d, skill, old + (click == ClickType.LEFT ? 1 : -1));
                plugin.messages().send(viewer, "level-changed", "skill", name, "player", d.name(),
                        "old", String.valueOf(old), "new", String.valueOf(now));
            }
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                int old = d.points(skill);
                progress().setPoints(d, skill, old + (click == ClickType.SHIFT_LEFT ? 10 : -10));
                plugin.messages().send(viewer, "points-changed", "skill", name, "player", d.name(),
                        "old", String.valueOf(old), "new", String.valueOf(d.points(skill)));
            }
            default -> {
                return;
            }
        }
        render(viewer, holder);
    }

    // ================================================================ иконки

    private Material skillIcon(Skill s) {
        return material(cfg().getString("skills." + s.key() + ".icon"), Material.BOOK);
    }

    private static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return m == null || !m.isItem() || m.isAir() ? fallback : m;
    }

    private static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.item(name));
            meta.lore(Text.itemLines(lore));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack head(OfflinePlayer owner, String name, List<String> lore) {
        ItemStack item = icon(Material.PLAYER_HEAD, name, lore);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            item.setItemMeta(skull);
        }
        return item;
    }
}
