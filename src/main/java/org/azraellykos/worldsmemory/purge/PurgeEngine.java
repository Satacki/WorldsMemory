package org.azraellykos.worldsmemory.purge;

import net.minecraft.util.math.ChunkPos;
import org.azraellykos.worldsmemory.Worldsmemory;
import org.azraellykos.worldsmemory.commit.WorldMemoryState;
import org.azraellykos.worldsmemory.storage.SnapshotEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 4 purge engine.
 *
 * Invariants:
 *   - The seed entry (first entry / seed hash) is always kept.
 *   - The most recent entry per chunk is always kept.
 *   - An ADMINISTRATIVE purge always writes to the audit log.
 *   - After history entries are removed, orphaned CAS objects are cleaned up.
 */
public class PurgeEngine {

    private static final DateTimeFormatter AUDIT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final WorldMemoryState state;
    private final Path auditLogPath;

    public PurgeEngine(WorldMemoryState state, Path worldMemoryDir) {
        this.state = state;
        this.auditLogPath = worldMemoryDir.resolve("purge_audit.log");
    }

    /**
     * Executes a purge.
     *
     * @param type          Purge type (AUCUNE / ADMINISTRATIVE / CONDITIONNELLE)
     * @param policy        Filter policy (null = purge everything except invariants)
     * @param operatorNote  Operator note written to the audit log (required for ADMINISTRATIVE)
     * @return              Summary of the operation
     */
    public PurgeResult execute(PurgeType type, PurgePolicy policy, String operatorNote) throws IOException {
        if (type == PurgeType.AUCUNE) {
            return new PurgeResult(0, 0, 0);
        }
        if (type == PurgeType.ADMINISTRATIVE && (operatorNote == null || operatorNote.isBlank())) {
            throw new IllegalArgumentException("Une note opérateur est obligatoire pour une purge ADMINISTRATIVE");
        }

        List<ChunkPos> allChunks = state.getHistoryIndex().getAllTrackedChunks();
        int chunksAffected = 0;
        int entriesPurged  = 0;

        // Collect all hashes still referenced after the purge (used to detect orphaned CAS objects)
        Set<String> referencedHashes = new HashSet<>();

        for (ChunkPos pos : allChunks) {
            List<SnapshotEntry> history = state.getHistoryIndex().getHistory(pos);
            if (history.isEmpty()) continue;

            String seedHash = state.getSeedStore().getSeedHash(pos);

            List<SnapshotEntry> toKeep   = new ArrayList<>();
            List<SnapshotEntry> toPurge  = new ArrayList<>();
            SnapshotEntry       latest   = history.get(history.size() - 1);

            for (SnapshotEntry entry : history) {
                boolean isSeed   = entry.hash().equals(seedHash);
                boolean isLatest = entry == latest;
                boolean policyWants = (policy == null) || policy.shouldPurge(entry, pos, history);

                if (isSeed || isLatest || !policyWants) {
                    toKeep.add(entry);
                } else {
                    toPurge.add(entry);
                }
            }

            if (!toPurge.isEmpty()) {
                state.getHistoryIndex().rewrite(pos, toKeep);
                entriesPurged += toPurge.size();
                chunksAffected++;
                Worldsmemory.LOGGER.debug("[WM] [PURGE] {} : {} entry/entries removed, {} kept",
                    pos, toPurge.size(), toKeep.size());
            }

            for (SnapshotEntry e : toKeep) referencedHashes.add(e.hash());
        }

        // Remove orphaned CAS objects no longer referenced by any history entry
        int orphansRemoved = removeOrphanObjects(referencedHashes);

        // Audit log (required for ADMINISTRATIVE, optional for CONDITIONNELLE)
        writeAuditLog(type, operatorNote, chunksAffected, entriesPurged, orphansRemoved);

        Worldsmemory.LOGGER.info("[WM] [PURGE] {} done: {} chunk(s), {} entry/entries, {} orphan(s)",
            type, chunksAffected, entriesPurged, orphansRemoved);

        return new PurgeResult(chunksAffected, entriesPurged, orphansRemoved);
    }

    private int removeOrphanObjects(Set<String> referencedHashes) {
        // Seed data is stored in SeedDataStore (separate directory), not in ChunkObjectStore.
        // referencedHashes covers exactly the CAS objects to keep.
        int removed = 0;
        try {
            removed = state.getObjectStore().removeOrphans(referencedHashes);
        } catch (IOException e) {
            Worldsmemory.LOGGER.warn("[WM] [PURGE] Error cleaning orphaned CAS objects", e);
        }
        return removed;
    }

    private void writeAuditLog(PurgeType type, String note, int chunks, int entries, int orphans) {
        String line = String.format("[%s] type=%s chunks=%d entries=%d orphans=%d note=%s%n",
            AUDIT_FMT.format(Instant.now()), type, chunks, entries, orphans,
            note != null ? note.replace("\n", " ") : "-");
        try {
            Files.createDirectories(auditLogPath.getParent());
            Files.writeString(auditLogPath, line,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Worldsmemory.LOGGER.warn("[WM] [PURGE] Failed to write audit log", e);
        }
    }
}
