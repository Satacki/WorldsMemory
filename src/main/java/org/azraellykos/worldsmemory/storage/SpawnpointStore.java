package org.azraellykos.worldsmemory.storage;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player spawnpoints per world. Used by Phase 3 rollback to re-link
 * bed/anchor spawnpoints when a chunk is restored.
 *
 * Persisted to {worldMemoryDir}/spawnpoints.nbt.
 * Thread-safe: ConcurrentHashMap, file I/O offloaded to WorldMemoryState's ioExecutor.
 */
public class SpawnpointStore {

    public record Entry(UUID playerUuid, BlockPos pos, float angle, boolean forced) {}

    private final Path file;
    private final ConcurrentHashMap<UUID, Entry> byPlayer = new ConcurrentHashMap<>();

    public SpawnpointStore(Path worldMemoryDir) {
        this.file = worldMemoryDir.resolve("spawnpoints.nbt");
    }

    public void set(UUID uuid, BlockPos pos, float angle, boolean forced) {
        byPlayer.put(uuid, new Entry(uuid, pos, angle, forced));
    }

    public void remove(UUID uuid) {
        byPlayer.remove(uuid);
    }

    public Entry get(UUID uuid) {
        return byPlayer.get(uuid);
    }

    /** Returns all tracked spawnpoints whose block position falls inside the given chunk. */
    public List<Entry> getForChunk(ChunkPos chunk) {
        List<Entry> result = null;
        for (Entry e : byPlayer.values()) {
            if (new ChunkPos(e.pos()).equals(chunk)) {
                if (result == null) result = new ArrayList<>();
                result.add(e);
            }
        }
        return result != null ? result : Collections.emptyList();
    }

    public boolean isEmpty() {
        return byPlayer.isEmpty();
    }

    public void save() throws IOException {
        NbtList list = new NbtList();
        for (Entry e : byPlayer.values()) {
            NbtCompound c = new NbtCompound();
            c.putUuid("uuid", e.playerUuid());
            c.putInt("x", e.pos().getX());
            c.putInt("y", e.pos().getY());
            c.putInt("z", e.pos().getZ());
            c.putFloat("angle", e.angle());
            c.putBoolean("forced", e.forced());
            list.add(c);
        }
        NbtCompound root = new NbtCompound();
        root.put("spawnpoints", list);
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file.toFile());
    }

    public void load() throws IOException {
        if (!Files.exists(file)) return;
        NbtCompound root = NbtIo.readCompressed(file.toFile());
        NbtList list = root.getList("spawnpoints", 10);
        byPlayer.clear();
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.getCompound(i);
            UUID uuid = c.getUuid("uuid");
            BlockPos pos = new BlockPos(c.getInt("x"), c.getInt("y"), c.getInt("z"));
            float angle = c.getFloat("angle");
            boolean forced = c.getBoolean("forced");
            byPlayer.put(uuid, new Entry(uuid, pos, angle, forced));
        }
    }
}
