package org.azraellykos.worldsmemory.storage;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stores full entity NBT snapshots per chunk, keyed by timestamp.
 * Only populated during explosion pre-snapshots (Phase 2).
 * Used by Phase 3 rollback to restore mobs killed by an explosion.
 *
 * Layout: {worldMemoryDir}/entities/{cx}.{cz}/{timestamp}.nbt
 * Each file: NbtCompound { "ts": long, "entities": NbtList<NbtCompound> }
 * Each entity NbtCompound is standard Minecraft entity NBT + "id" type identifier.
 */
public class EntitySnapshotStore {

    private final Path entitiesDir;
    /** In-memory index updated immediately on capture, before the async disk write completes. */
    private final ConcurrentHashMap<ChunkPos, CopyOnWriteArrayList<Long>> memIndex = new ConcurrentHashMap<>();

    public EntitySnapshotStore(Path worldMemoryDir) {
        this.entitiesDir = worldMemoryDir.resolve("entities");
    }

    /**
     * Records the timestamp immediately (on the server thread) so that {@link #getTimestamps}
     * returns it even before the async disk write in {@link #store} has finished.
     */
    public void notifyPending(ChunkPos pos, long timestamp) {
        memIndex.computeIfAbsent(pos, k -> new CopyOnWriteArrayList<>()).add(timestamp);
    }

    public void store(ChunkPos pos, long timestamp, List<NbtCompound> entities) throws IOException {
        if (entities.isEmpty()) return;
        Path dir = chunkDir(pos);
        Files.createDirectories(dir);

        NbtList list = new NbtList();
        for (NbtCompound e : entities) list.add(e);

        NbtCompound root = new NbtCompound();
        root.putLong("ts", timestamp);
        root.put("entities", list);

        NbtIo.writeCompressed(root, dir.resolve(timestamp + ".nbt").toFile());
    }

    /** Returns the entity list from the snapshot at the given timestamp, or empty if none. */
    public List<NbtCompound> get(ChunkPos pos, long timestamp) throws IOException {
        Path file = chunkDir(pos).resolve(timestamp + ".nbt");
        if (!Files.exists(file)) return Collections.emptyList();

        NbtList list = NbtIo.readCompressed(file.toFile()).getList("entities", 10);
        List<NbtCompound> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) result.add(list.getCompound(i));
        return result;
    }

    /**
     * Returns all snapshot timestamps for a chunk, sorted ascending.
     * Merges on-disk timestamps with the in-memory index so that entries captured
     * moments ago (async write still in-flight) are immediately visible.
     */
    public List<Long> getTimestamps(ChunkPos pos) throws IOException {
        List<Long> timestamps = new ArrayList<>();

        Path dir = chunkDir(pos);
        if (Files.exists(dir)) {
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".nbt"))
                      .map(p -> {
                          String name = p.getFileName().toString();
                          return Long.parseLong(name.substring(0, name.length() - 4));
                      })
                      .forEach(timestamps::add);
            }
        }

        // Add in-memory entries not yet on disk
        CopyOnWriteArrayList<Long> mem = memIndex.get(pos);
        if (mem != null) {
            for (Long ts : mem) {
                if (!timestamps.contains(ts)) timestamps.add(ts);
            }
        }

        Collections.sort(timestamps);
        return timestamps;
    }

    private Path chunkDir(ChunkPos pos) {
        return entitiesDir.resolve(pos.x + "." + pos.z);
    }
}
