package com.steelrework.playerprogress.listener;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Запоминает блоки, поставленные игроками, чтобы за них не давали очков.
 * Данные хранятся прямо в чанках (переживают перезапуск), в памяти — только загруженные чанки.
 */
public final class PlacedBlockTracker implements Listener {

    private record ChunkId(UUID world, int x, int z) {
        static ChunkId of(Chunk c) {
            return new ChunkId(c.getWorld().getUID(), c.getX(), c.getZ());
        }
    }

    private static final class ChunkData {
        final Set<Integer> positions;
        boolean dirty;

        ChunkData(Set<Integer> positions) {
            this.positions = positions;
        }
    }

    private final NamespacedKey key;
    private final Map<ChunkId, ChunkData> cache = new HashMap<>();

    public PlacedBlockTracker(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "placed_blocks");
    }

    private static int pack(Block b) {
        return (b.getX() & 15) | ((b.getZ() & 15) << 4) | ((b.getY() + 4096) << 8);
    }

    private ChunkData data(Chunk chunk) {
        return cache.computeIfAbsent(ChunkId.of(chunk), id -> {
            Set<Integer> set = new HashSet<>();
            int[] stored = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
            if (stored != null) for (int v : stored) set.add(v);
            return new ChunkData(set);
        });
    }

    public boolean isPlaced(Block block) {
        return data(block.getChunk()).positions.contains(pack(block));
    }

    public void mark(Block block) {
        ChunkData d = data(block.getChunk());
        if (d.positions.add(pack(block))) d.dirty = true;
    }

    public void unmark(Block block) {
        ChunkData d = data(block.getChunk());
        if (d.positions.remove(pack(block))) d.dirty = true;
    }

    private void write(Chunk chunk, ChunkData d) {
        if (!d.dirty) return;
        if (d.positions.isEmpty()) {
            chunk.getPersistentDataContainer().remove(key);
        } else {
            int[] arr = new int[d.positions.size()];
            int i = 0;
            for (int v : d.positions) arr[i++] = v;
            chunk.getPersistentDataContainer().set(key, PersistentDataType.INTEGER_ARRAY, arr);
        }
        d.dirty = false;
    }

    /** Записывает изменения во все загруженные чанки (при сохранении мира и выключении). */
    public void flushAll(boolean clear) {
        Iterator<Map.Entry<ChunkId, ChunkData>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkId, ChunkData> e = it.next();
            ChunkId id = e.getKey();
            World world = Bukkit.getWorld(id.world());
            if (world != null && world.isChunkLoaded(id.x(), id.z())) {
                write(world.getChunkAt(id.x(), id.z()), e.getValue());
            }
            if (clear || world == null || !world.isChunkLoaded(id.x(), id.z())) it.remove();
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        ChunkData d = cache.remove(ChunkId.of(event.getChunk()));
        if (d != null) write(event.getChunk(), d);
    }

    @EventHandler
    public void onWorldSave(WorldSaveEvent event) {
        UUID worldId = event.getWorld().getUID();
        for (Map.Entry<ChunkId, ChunkData> e : cache.entrySet()) {
            ChunkId id = e.getKey();
            if (!id.world().equals(worldId) || !event.getWorld().isChunkLoaded(id.x(), id.z())) continue;
            write(event.getWorld().getChunkAt(id.x(), id.z()), e.getValue());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), facing(event.getBlock(), event.getDirection()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), facing(event.getBlock(), event.getDirection()).getOppositeFace());
    }

    private static BlockFace facing(Block piston, BlockFace fallback) {
        BlockData data = piston.getBlockData();
        return data instanceof Directional dir ? dir.getFacing() : fallback;
    }

    private void move(List<Block> blocks, BlockFace direction) {
        List<Block> placed = new ArrayList<>();
        for (Block b : blocks) {
            if (isPlaced(b)) placed.add(b);
        }
        for (Block b : placed) unmark(b);
        for (Block b : placed) mark(b.getRelative(direction));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) unmark(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) unmark(b);
    }
}
