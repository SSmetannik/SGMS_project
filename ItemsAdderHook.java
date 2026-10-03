package com.steelrework.playerprogress.hook;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Связь с ItemsAdder через рефлексию: плагин собирается и работает без ItemsAdder,
 * а если он установлен — распознаёт кастомные предметы, блоки и мебель.
 */
public final class ItemsAdderHook {

    private final Logger logger;
    private boolean enabled;

    private Method stackByItem;      // CustomStack.byItemStack(ItemStack)
    private Method stackGetInstance; // CustomStack.getInstance(String)
    private Method stackGetItem;     // CustomStack#getItemStack()
    private Class<?> stackClass;
    private Method stackGetId;       // CustomStack#getNamespacedID()
    private Method blockByPlaced;    // CustomBlock.byAlreadyPlaced(Block)
    private Method furnitureBySpawned; // CustomFurniture.byAlreadySpawned(Entity)

    public ItemsAdderHook(Logger logger) {
        this.logger = logger;
    }

    public void init() {
        enabled = false;
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return;
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            stackByItem = customStack.getMethod("byItemStack", ItemStack.class);
            stackGetInstance = customStack.getMethod("getInstance", String.class);
            stackGetItem = customStack.getMethod("getItemStack");
            stackGetId = customStack.getMethod("getNamespacedID");
            stackClass = customStack;
            enabled = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.log(Level.WARNING, "ItemsAdder найден, но его API не подошло. Поддержка ItemsAdder выключена.", e);
            return;
        }
        try {
            Class<?> customBlock = Class.forName("dev.lone.itemsadder.api.CustomBlock");
            blockByPlaced = customBlock.getMethod("byAlreadyPlaced", Block.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.warning("ItemsAdder: не удалось подключить CustomBlock — кастомные блоки не будут распознаваться.");
        }
        try {
            Class<?> furniture = Class.forName("dev.lone.itemsadder.api.CustomFurniture");
            furnitureBySpawned = furniture.getMethod("byAlreadySpawned", Entity.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            logger.warning("ItemsAdder: не удалось подключить CustomFurniture — мебель не будет распознаваться.");
        }
        logger.info("Поддержка ItemsAdder включена.");
    }

    public boolean enabled() {
        return enabled;
    }

    /** ID предмета ItemsAdder ("namespace:id") или null, если это обычный предмет. */
    public String itemId(ItemStack item) {
        if (!enabled || item == null || item.getType().isAir()) return null;
        return idOf(invokeStatic(stackByItem, item));
    }

    public String blockId(Block block) {
        if (!enabled || blockByPlaced == null || block == null) return null;
        return idOf(invokeStatic(blockByPlaced, block));
    }

    public String furnitureId(Entity entity) {
        if (!enabled || furnitureBySpawned == null || entity == null) return null;
        return idOf(invokeStatic(furnitureBySpawned, entity));
    }

    /** Создаёт предмет ItemsAdder по ID, null — если такого нет. */
    public ItemStack create(String id) {
        if (!enabled || id == null) return null;
        Object stack = invokeStatic(stackGetInstance, id);
        if (stack == null) return null;
        try {
            Object item = stackGetItem.invoke(stack);
            return item instanceof ItemStack is ? is.clone() : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Object invokeStatic(Method method, Object arg) {
        if (method == null) return null;
        try {
            return method.invoke(null, arg);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private String idOf(Object customObject) {
        if (customObject == null) return null;
        try {
            Method m = stackClass.isInstance(customObject)
                    ? stackGetId
                    : customObject.getClass().getMethod("getNamespacedID");
            Object id = m.invoke(customObject);
            return id == null ? null : id.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Подписка на события ItemsAdder (установка/поломка/использование кастомных блоков и мебели).
     * blocked(игрок, id) возвращает true, если действие нужно отменить.
     */
    public void registerEvents(Plugin plugin, BiPredicate<Player, String> blocked) {
        if (!enabled) return;
        String[] names = {
                "CustomBlockPlaceEvent", "CustomBlockBreakEvent", "CustomBlockInteractEvent",
                "FurniturePlaceEvent", "FurnitureBreakEvent", "FurnitureInteractEvent"
        };
        Listener holder = new Listener() {
        };
        int registered = 0;
        for (String name : names) {
            try {
                Class<?> raw = Class.forName("dev.lone.itemsadder.api.Events." + name);
                if (!Event.class.isAssignableFrom(raw) || !Cancellable.class.isAssignableFrom(raw)) continue;
                Class<? extends Event> eventClass = raw.asSubclass(Event.class);
                Method getId = raw.getMethod("getNamespacedID");
                Method getPlayer = raw.getMethod("getPlayer");
                EventExecutor executor = (listener, event) -> {
                    if (!eventClass.isInstance(event)) return;
                    Cancellable cancellable = (Cancellable) event;
                    if (cancellable.isCancelled()) return;
                    try {
                        Object p = getPlayer.invoke(event);
                        Object id = getId.invoke(event);
                        if (p instanceof Player player && id != null && blocked.test(player, id.toString())) {
                            cancellable.setCancelled(true);
                        }
                    } catch (ReflectiveOperationException ignored) {
                    }
                };
                Bukkit.getPluginManager().registerEvent(eventClass, holder, EventPriority.LOW, executor, plugin, false);
                registered++;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // такого события нет в этой версии ItemsAdder
            }
        }
        logger.info("ItemsAdder: подключено событий блоков/мебели: " + registered);
    }
}
