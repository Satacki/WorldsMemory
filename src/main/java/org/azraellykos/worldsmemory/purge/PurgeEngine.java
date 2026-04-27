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
 * Moteur de purge Phase 4.
 *
 * Invariants :
 *   - Le seed (première entrée / hash seed) est toujours conservé.
 *   - L'entrée la plus récente par chunk est toujours conservée.
 *   - Une purge ADMINISTRATIVE écrit obligatoirement dans l'audit log.
 *   - Après suppression des entrées d'historique, les objets CAS orphelins sont nettoyés.
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
     * Exécute une purge.
     *
     * @param type          Type de purge (AUCUNE/ADMINISTRATIVE/CONDITIONNELLE)
     * @param policy        Policy applicable (null = purge totale hors invariants)
     * @param operatorNote  Note opérateur pour l'audit log (requise pour ADMINISTRATIVE)
     * @return              Résumé de l'opération
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

        // Collecte de tous les hashes encore référencés après purge (pour détection orphelins)
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
                Worldsmemory.LOGGER.debug("[WM] [PURGE] {} : {} entrée(s) supprimée(s), {} conservée(s)",
                    pos, toPurge.size(), toKeep.size());
            }

            for (SnapshotEntry e : toKeep) referencedHashes.add(e.hash());
        }

        // Nettoyage des objets CAS orphelins
        int orphansRemoved = removeOrphanObjects(referencedHashes);

        // Audit log (obligatoire pour ADMINISTRATIVE, optionnel pour CONDITIONNELLE)
        writeAuditLog(type, operatorNote, chunksAffected, entriesPurged, orphansRemoved);

        Worldsmemory.LOGGER.info("[WM] [PURGE] {} terminée : {} chunk(s), {} entrée(s), {} orphelin(s)",
            type, chunksAffected, entriesPurged, orphansRemoved);

        return new PurgeResult(chunksAffected, entriesPurged, orphansRemoved);
    }

    private int removeOrphanObjects(Set<String> referencedHashes) {
        // Note : le seed data est stocké dans SeedDataStore (dossier séparé), pas dans ChunkObjectStore.
        // referencedHashes couvre exactement les objets CAS à conserver.
        int removed = 0;
        try {
            removed = state.getObjectStore().removeOrphans(referencedHashes);
        } catch (IOException e) {
            Worldsmemory.LOGGER.warn("[WM] [PURGE] Erreur nettoyage objets orphelins", e);
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
            Worldsmemory.LOGGER.warn("[WM] [PURGE] Impossible d'écrire l'audit log", e);
        }
    }
}
