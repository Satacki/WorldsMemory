package org.azraellykos.worldsmemory.storage;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.ChunkPos;
import org.azraellykos.worldsmemory.commit.CauseModification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Stores the ordered list of snapshots for each chunk.
 * One compressed NBT file per chunk: {base}/history/{cx}.{cz}.nbt
 */
public class ChunkHistoryIndex {

    private final Path historyDir;

    public ChunkHistoryIndex(Path worldMemoryDir) {
        this.historyDir = worldMemoryDir.resolve("history");
    }

    public void append(ChunkPos pos, SnapshotEntry entry) throws IOException {
        Files.createDirectories(historyDir);
        Path file = historyFile(pos);

        NbtList list = loadList(file);
        NbtCompound entryNbt = new NbtCompound();
        entryNbt.putLong("ts", entry.timestamp());
        entryNbt.putString("hash", entry.hash());
        entryNbt.putString("cause", entry.cause().name());
        if (entry.playerUuid() != null) {
            entryNbt.putString("uuid", entry.playerUuid().toString());
        }
        list.add(entryNbt);

        NbtCompound root = new NbtCompound();
        root.put("entries", list);
        NbtIo.writeCompressed(root, file.toFile());
    }

    public List<SnapshotEntry> getHistory(ChunkPos pos) throws IOException {
        Path file = historyFile(pos);
        if (!Files.exists(file)) return Collections.emptyList();

        NbtList list = loadList(file);
        List<SnapshotEntry> result = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            NbtCompound e = list.getCompound(i);
            UUID uuid = parseUuid(e.getString("uuid"));
            result.add(new SnapshotEntry(e.getLong("ts"), e.getString("hash"), parseCause(e.getString("cause")), uuid));
        }
        return result;
    }

    public SnapshotEntry getLatest(ChunkPos pos) throws IOException {
        List<SnapshotEntry> history = getHistory(pos);
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }

    private static CauseModification parseCause(String name) {
        if (name == null || name.isEmpty()) return CauseModification.INCONNU;
        try {
            return CauseModification.valueOf(name);
        } catch (IllegalArgumentException e) {
            return CauseModification.INCONNU;
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private NbtList loadList(Path file) throws IOException {
        if (!Files.exists(file)) return new NbtList();
        NbtCompound root = NbtIo.readCompressed(file.toFile());
        return root.getList("entries", 10);
    }

    /**
     * Remplace l'historique d'un chunk par une liste filtrée.
     * Si la liste est vide, le fichier historique est supprimé.
     */
    public void rewrite(ChunkPos pos, List<SnapshotEntry> entries) throws IOException {
        Path file = historyFile(pos);
        if (entries.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(historyDir);
        NbtList list = new NbtList();
        for (SnapshotEntry entry : entries) {
            NbtCompound e = new NbtCompound();
            e.putLong("ts", entry.timestamp());
            e.putString("hash", entry.hash());
            e.putString("cause", entry.cause().name());
            if (entry.playerUuid() != null) e.putString("uuid", entry.playerUuid().toString());
            list.add(e);
        }
        NbtCompound root = new NbtCompound();
        root.put("entries", list);
        NbtIo.writeCompressed(root, file.toFile());
    }

    /**
     * Returns the most-recent snapshot whose timestamp is ≤ {@code targetTimestamp},
     * or {@code null} if no such snapshot exists for this chunk.
     */
    public SnapshotEntry getAtOrBefore(ChunkPos pos, long targetTimestamp) throws IOException {
        List<SnapshotEntry> history = getHistory(pos);
        SnapshotEntry best = null;
        for (SnapshotEntry e : history) {
            if (e.timestamp() <= targetTimestamp) best = e;
            else break; // list is ordered chronologically
        }
        return best;
    }

    /** Returns all tracked chunks that have a history file on disk (used by the purge scan). */
    public List<ChunkPos> getAllTrackedChunks() throws IOException {
        if (!Files.exists(historyDir)) return java.util.Collections.emptyList();
        List<ChunkPos> result = new ArrayList<>();
        try (var stream = Files.list(historyDir)) {
            stream.filter(p -> p.toString().endsWith(".nbt")).forEach(p -> {
                String name = p.getFileName().toString().replace(".nbt", "");
                String[] parts = name.split("\\.");
                if (parts.length == 2) {
                    try {
                        result.add(new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                    } catch (NumberFormatException ignored) {}
                }
            });
        }
        return result;
    }

    private Path historyFile(ChunkPos pos) {
        return historyDir.resolve(pos.x + "." + pos.z + ".nbt");
    }
}
