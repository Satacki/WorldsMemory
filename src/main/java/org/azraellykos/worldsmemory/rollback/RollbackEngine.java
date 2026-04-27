package org.azraellykos.worldsmemory.rollback;

import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.azraellykos.worldsmemory.Worldsmemory;
import org.azraellykos.worldsmemory.commit.DirtyChunkTracker;
import org.azraellykos.worldsmemory.commit.WorldMemoryState;
import org.azraellykos.worldsmemory.storage.SnapshotEntry;
import org.azraellykos.worldsmemory.storage.SpawnpointStore;
import org.azraellykos.worldsmemory.storage.WMChunkSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moteur de rollback Phase 3 — exécute les opérations de restauration sur le thread serveur.
 *
 * Granularité : Chunk → Bloc → Entité → Item → NBT
 * Modes       : SEED_ORIGINAL | ETAT_PRECEDENT | DELTA_EXPLOSIONS_ONLY
 */
public class RollbackEngine {

    private final WorldMemoryState state;
    private final ServerWorld world;

    public RollbackEngine(WorldMemoryState state, ServerWorld world) {
        this.state = state;
        this.world = world;
    }

    // -------------------------------------------------------------------------
    // Point d'entrée public
    // -------------------------------------------------------------------------

    public RollbackResult execute(RollbackRequest request) {
        Set<ChunkPos> chunks = request.affectedChunks();

        boolean doFreeze  = request.freezeMode == ZoneFreezeMode.FREEZE_ZONE;
        boolean doWatch   = request.freezeMode == ZoneFreezeMode.CANCEL_IF_MODIFIED;
        if (doFreeze) FrozenZoneManager.freeze(world.getRegistryKey(), chunks);
        if (doWatch)  DirtyChunkTracker.beginCancelWatch(chunks);
        else          DirtyChunkTracker.beginRollback();

        // Remove all dropped items (explosion debris) from the zone before restoring blocks.
        // Done inside the rollback guard so the entity removal doesn't trigger dirty tracking.
        for (ChunkPos chunkPos : chunks) {
            Box itemBox = new Box(
                chunkPos.getStartX(), world.getBottomY(), chunkPos.getStartZ(),
                chunkPos.getStartX() + 16, world.getTopY(), chunkPos.getStartZ() + 16
            );
            world.getEntitiesByClass(ItemEntity.class, itemBox, e -> true).forEach(Entity::discard);
        }

        int totalBlocks = 0, restoredChunks = 0, restoredEntities = 0;
        boolean degraded = false;

        try {
            for (ChunkPos chunkPos : chunks) {
                // CANCEL_IF_MODIFIED : annuler si une modification externe a été détectée.
                if (doWatch && DirtyChunkTracker.isRollbackCancelled()) {
                    Worldsmemory.LOGGER.warn("[WM] [ROLLBACK] CANCEL_IF_MODIFIED — modification externe détectée, rollback annulé après {}/{} chunks",
                        restoredChunks, chunks.size());
                    return RollbackResult.degraded(restoredChunks, totalBlocks, restoredEntities,
                        "Annulé — modification externe détectée pendant le rollback");
                }
                try {
                    int blocks = restoreBlocks(chunkPos, request);
                    if (blocks >= 0) {
                        totalBlocks += blocks;
                        restoredChunks++;
                    } else {
                        degraded = true;
                    }
                } catch (IOException e) {
                    Worldsmemory.LOGGER.error("[WM] [ROLLBACK] Erreur I/O pour chunk {}", chunkPos, e);
                    degraded = true;
                }
            }

            if (request.entityMode != EntityMode.IGNORER) {
                try {
                    restoredEntities = restoreEntities(chunks, request);
                } catch (IOException e) {
                    Worldsmemory.LOGGER.error("[WM] [ROLLBACK] Erreur I/O entités", e);
                    degraded = true;
                }
            }

        } finally {
            if (doWatch)  DirtyChunkTracker.endCancelWatch();
            else          DirtyChunkTracker.endRollback();
            if (doFreeze) FrozenZoneManager.unfreeze(world.getRegistryKey(), chunks);
        }

        if (degraded) {
            return RollbackResult.degraded(restoredChunks, totalBlocks, restoredEntities,
                "Certains chunks n'avaient pas de snapshot disponible");
        }
        return RollbackResult.success(restoredChunks, totalBlocks, restoredEntities);
    }

    // -------------------------------------------------------------------------
    // Dispatch selon le mode
    // -------------------------------------------------------------------------

    private int restoreBlocks(ChunkPos pos, RollbackRequest request) throws IOException {
        return switch (request.chunkMode) {
            case ETAT_PRECEDENT      -> restoreEtatPrecedent(pos, request.beforeTimestamp, request.steps, request.itemMode, request.nbtMode);
            case SEED_ORIGINAL       -> restoreSeedOriginal(pos, request.itemMode, request.nbtMode);
            case DELTA_EXPLOSIONS_ONLY -> restoreDelta(pos, request.beforeTimestamp);
        };
    }

    // -------------------------------------------------------------------------
    // ETAT_PRECEDENT — dernier snapshot avant le timestamp
    // -------------------------------------------------------------------------

    private int restoreEtatPrecedent(ChunkPos pos, long beforeTs, int steps, ItemMode itemMode, NbtMode nbtMode) throws IOException {
        List<SnapshotEntry> history = state.getHistoryIndex().getHistory(pos);
        SnapshotEntry entry;

        if (beforeTs == Long.MAX_VALUE) {
            // Reculer de `steps` crans depuis la tête de l'historique.
            // La tête (last) = état actuel committé.
            // steps=1 → avant-dernière, steps=2 → avant-avant-dernière, etc.
            int targetIndex = history.size() - 1 - steps;
            if (targetIndex >= 0) {
                entry = history.get(targetIndex);
            } else {
                // Pas assez d'entrées pour le nombre de crans demandé — fallback seed.
                Worldsmemory.LOGGER.info("[WM] [ROLLBACK] Historique insuffisant pour {} (steps={}, dispo={}) — fallback SEED_ORIGINAL",
                    pos, steps, history.size());
                return restoreSeedOriginal(pos, itemMode, nbtMode);
            }
        } else {
            // Timestamp explicite (ex: explosion) : snapshot juste avant ce moment.
            entry = findSnapshotBefore(pos, beforeTs);
            if (entry == null) {
                Worldsmemory.LOGGER.warn("[WM] [ROLLBACK] Aucun snapshot avant {} pour {}", beforeTs, pos);
                return -1;
            }
        }

        byte[] data = state.getObjectStore().load(entry.hash());
        NbtCompound snapshot = WMChunkSerializer.fromBytes(data);
        Worldsmemory.LOGGER.info("[WM] [ROLLBACK] ETAT_PRECEDENT {} ts={} hash={}", pos, entry.timestamp(), entry.hash().substring(0, 8));
        return applySnapshotToChunk(pos, snapshot, itemMode, nbtMode);
    }

    // -------------------------------------------------------------------------
    // SEED_ORIGINAL — état de génération du monde
    // -------------------------------------------------------------------------

    private int restoreSeedOriginal(ChunkPos pos, ItemMode itemMode, NbtMode nbtMode) throws IOException {
        if (!state.getSeedDataStore().exists(pos)) {
            Worldsmemory.LOGGER.warn("[WM] [ROLLBACK] Aucune donnée seed pour {}", pos);
            return -1;
        }
        byte[] data = state.getSeedDataStore().load(pos);
        NbtCompound snapshot = WMChunkSerializer.fromBytes(data);
        Worldsmemory.LOGGER.info("[WM] [ROLLBACK] SEED_ORIGINAL {}", pos);
        return applySnapshotToChunk(pos, snapshot, itemMode, nbtMode);
    }

    // -------------------------------------------------------------------------
    // DELTA_EXPLOSIONS_ONLY — restaure uniquement les blocs modifiés par l'explosion
    // -------------------------------------------------------------------------

    private int restoreDelta(ChunkPos pos, long beforeTs) throws IOException {
        List<SnapshotEntry> history = state.getHistoryIndex().getHistory(pos);
        if (history.isEmpty()) return 0;

        SnapshotEntry entry;
        if (beforeTs == Long.MAX_VALUE) {
            // Le dernier snapshot = état post-explosion. Le pré-snapshot = avant-dernier.
            // S'il n'y a qu'une seule entrée on la prend quand même pour la comparer à l'état actuel.
            entry = history.size() >= 2 ? history.get(history.size() - 2) : history.get(0);
        } else {
            entry = findSnapshotBefore(pos, beforeTs);
            if (entry == null) return 0;
        }

        byte[] preData = state.getObjectStore().load(entry.hash());
        NbtCompound preSnapshot = WMChunkSerializer.fromBytes(preData);

        WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
        if (chunk == null) return 0;

        NbtCompound currentSnapshot = WMChunkSerializer.fromBytes(WMChunkSerializer.serialize(chunk));
        Worldsmemory.LOGGER.info("[WM] [ROLLBACK] DELTA {} ts={}", pos, entry.timestamp());
        return applyDeltaToChunk(pos, preSnapshot, currentSnapshot);
    }

    // -------------------------------------------------------------------------
    // Application complète d'un snapshot au chunk (ETAT_PRECEDENT / SEED_ORIGINAL)
    // -------------------------------------------------------------------------

    private int applySnapshotToChunk(ChunkPos pos, NbtCompound snapshot, ItemMode itemMode, NbtMode nbtMode) {
        WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
        if (chunk == null) return 0;

        RegistryEntryLookup<net.minecraft.block.Block> blockLookup =
            world.getServer().getRegistryManager().getWrapperOrThrow(RegistryKeys.BLOCK);
        int restoredBlocks = 0;

        Map<Integer, SectionData> snapshotSections = parseSections(snapshot.getList("sections", 10), blockLookup);
        Map<BlockPos, NbtCompound> snapshotBEs = parseBlockEntities(snapshot.getList("block_entities", 10));

        int bottomSection = chunk.getBottomSectionCoord();
        int sectionCount  = chunk.getSectionArray().length;

        for (int i = 0; i < sectionCount; i++) {
            int sectionY    = bottomSection + i;
            int worldStartY = sectionY << 4;
            SectionData sd  = snapshotSections.get(sectionY);

            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState target  = sd != null ? sd.getState(lx, ly, lz) : Blocks.AIR.getDefaultState();
                        BlockPos blockPos  = new BlockPos(pos.getStartX() + lx, worldStartY + ly, pos.getStartZ() + lz);
                        BlockState current = world.getBlockState(blockPos);

                        if (!current.equals(target)) {
                            world.setBlockState(blockPos, target, Block.NOTIFY_LISTENERS);
                            restoredBlocks++;
                        }
                    }
                }
            }
        }

        // Restauration NBT des block entities + sync client
        if (nbtMode != NbtMode.AUCUN) {
            Worldsmemory.LOGGER.info("[WM] [DBG] snapshotBEs size={} for chunk {}", snapshotBEs.size(), pos);
            for (Map.Entry<BlockPos, NbtCompound> entry : snapshotBEs.entrySet()) {
                BlockEntity be = world.getBlockEntity(entry.getKey());
                NbtCompound rawNbt = entry.getValue();
                Worldsmemory.LOGGER.info("[WM] [DBG]  BE @ {} type={} hasItems={} itemsSize={}",
                    entry.getKey(),
                    rawNbt.getString("id"),
                    rawNbt.contains("Items"),
                    rawNbt.contains("Items") ? rawNbt.getList("Items", 10).size() : 0);
                if (be == null) {
                    Worldsmemory.LOGGER.info("[WM] [DBG]   → BE null dans le monde, skip");
                    continue;
                }
                NbtCompound nbt = resolveBeNbt(rawNbt, itemMode, nbtMode);
                be.readNbt(nbt);
                be.markDirty();
                syncBeToClients(be, entry.getKey());
            }
        }

        handleSpawnpoints(pos);
        return restoredBlocks;
    }

    // -------------------------------------------------------------------------
    // Application delta (DELTA_EXPLOSIONS_ONLY)
    // -------------------------------------------------------------------------

    private int applyDeltaToChunk(ChunkPos pos, NbtCompound preSnapshot, NbtCompound currentSnapshot) {
        RegistryEntryLookup<net.minecraft.block.Block> blockLookup =
            world.getServer().getRegistryManager().getWrapperOrThrow(RegistryKeys.BLOCK);

        Map<Integer, SectionData> preSections = parseSections(preSnapshot.getList("sections", 10), blockLookup);
        Map<Integer, SectionData> curSections = parseSections(currentSnapshot.getList("sections", 10), blockLookup);
        Map<BlockPos, NbtCompound> preBeNbts  = parseBlockEntities(preSnapshot.getList("block_entities", 10));

        WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
        if (chunk == null) return 0;

        int bottomSection = chunk.getBottomSectionCoord();
        int sectionCount  = chunk.getSectionArray().length;
        int restoredBlocks = 0;

        for (int i = 0; i < sectionCount; i++) {
            int sectionY   = bottomSection + i;
            SectionData pre = preSections.get(sectionY);
            SectionData cur = curSections.get(sectionY);

            // Si les deux sections sont absentes, rien à faire
            if (pre == null && cur == null) continue;

            int worldStartY = sectionY << 4;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState preState = pre != null ? pre.getState(lx, ly, lz) : Blocks.AIR.getDefaultState();
                        BlockState curState = cur != null ? cur.getState(lx, ly, lz) : Blocks.AIR.getDefaultState();

                        if (!preState.equals(curState)) {
                            // Ce bloc a changé depuis le pré-snapshot → l'explosion l'a détruit
                            BlockPos blockPos = new BlockPos(pos.getStartX() + lx, worldStartY + ly, pos.getStartZ() + lz);
                            world.setBlockState(blockPos, preState, Block.NOTIFY_LISTENERS);
                            restoredBlocks++;
                        }
                    }
                }
            }
        }

        // Restauration NBT des block entities du pré-snapshot qui ont changé
        for (Map.Entry<BlockPos, NbtCompound> entry : preBeNbts.entrySet()) {
            BlockEntity be = world.getBlockEntity(entry.getKey());
            if (be != null) {
                be.readNbt(resolveBeNbt(entry.getValue(), ItemMode.RESTAURER_CONTENEURS, NbtMode.COMPLET));
                be.markDirty();
                syncBeToClients(be, entry.getKey());
            }
        }

        handleSpawnpoints(pos);
        return restoredBlocks;
    }

    // -------------------------------------------------------------------------
    // Restauration des entités
    // -------------------------------------------------------------------------

    private int restoreEntities(Set<ChunkPos> chunks, RollbackRequest request) throws IOException {
        int count = 0;
        boolean isSeed = (request.chunkMode == RollbackMode.SEED_ORIGINAL);

        for (ChunkPos pos : chunks) {
            Box box = new Box(
                pos.getStartX(), world.getBottomY(), pos.getStartZ(),
                pos.getStartX() + 16, world.getTopY(), pos.getStartZ() + 16
            );
            // Toujours supprimer les entités non-joueur, qu'il y ait un snapshot ou non
            boolean removeItems = (request.entityMode == EntityMode.RESTAURER_TOUT);
            world.getEntitiesByClass(Entity.class, box, e -> {
                if (e instanceof PlayerEntity) return false;
                if (!removeItems && e instanceof ItemEntity) return false;
                return true;
            }).forEach(Entity::discard);

            // SEED_ORIGINAL = état de génération, pas d'entités à restaurer
            if (isSeed) continue;

            List<Long> timestamps = state.getEntityStore().getTimestamps(pos);
            if (timestamps.isEmpty()) continue;

            // Snapshot entités le plus récent avant le timestamp de référence
            long targetTs = -1;
            for (Long ts : timestamps) {
                if (ts <= request.beforeTimestamp) targetTs = ts;
            }
            if (targetTs < 0) continue;

            List<NbtCompound> entityNbts = state.getEntityStore().get(pos, targetTs);
            for (NbtCompound nbt : entityNbts) {
                Entity spawned = spawnEntityFromNbt(nbt);
                if (spawned != null) count++;
            }
        }

        return count;
    }

    /** Types d'entités transitoires/dangereuses à ne jamais restaurer. */
    private static final java.util.Set<String> SKIP_ENTITY_TYPES = java.util.Set.of(
        "minecraft:tnt",           // TNT amorcée — exploserait immédiatement
        "minecraft:falling_block", // Blocs en chute — comportement imprévisible
        "minecraft:item",          // Items au sol — déjà gérés séparément
        "minecraft:experience_orb",
        "minecraft:fireball",
        "minecraft:small_fireball",
        "minecraft:wither_skull",
        "minecraft:dragon_fireball",
        "minecraft:arrow",
        "minecraft:spectral_arrow",
        "minecraft:trident",
        "minecraft:area_effect_cloud"
    );

    /**
     * Crée et spawne une entité depuis son NBT complet.
     * Attribue un nouvel UUID pour éviter les conflits avec des entités existantes.
     */
    private Entity spawnEntityFromNbt(NbtCompound nbt) {
        String typeId = nbt.getString("id");
        if (typeId.isEmpty()) return null;
        if (SKIP_ENTITY_TYPES.contains(typeId)) return null;

        EntityType<?> entityType = Registries.ENTITY_TYPE.get(new Identifier(typeId));
        if (entityType == null) return null;

        Entity entity = entityType.create(world);
        if (entity == null) return null;

        entity.readNbt(nbt);
        entity.setUuid(UUID.randomUUID()); // Éviter conflits UUID

        if (world.spawnEntity(entity)) return entity;
        return null;
    }

    // -------------------------------------------------------------------------
    // Spawnpoints — réactivation si le bloc de spawn a été restauré
    // -------------------------------------------------------------------------

    private void handleSpawnpoints(ChunkPos pos) {
        List<SpawnpointStore.Entry> spawnpoints = state.getSpawnpointStore().getForChunk(pos);
        for (SpawnpointStore.Entry spawn : spawnpoints) {
            BlockState blockState = world.getBlockState(spawn.pos());
            boolean isValidSpawn = blockState.getBlock() instanceof BedBlock
                || blockState.getBlock() instanceof RespawnAnchorBlock;

            if (isValidSpawn) {
                Worldsmemory.LOGGER.info("[WM] [ROLLBACK] Spawnpoint restauré pour {} @ {}",
                    spawn.playerUuid(), spawn.pos());
                // Si le joueur est en ligne, son spawnpoint est automatiquement valide
                // car le bloc de spawn a été restauré
                ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(spawn.playerUuid());
                if (player != null) {
                    player.setSpawnPoint(world.getRegistryKey(), spawn.pos(), spawn.angle(), spawn.forced(), false);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Envoie le BlockEntityUpdatePacket aux joueurs proches.
     * world.updateListeners envoie seulement le block state — les données BE
     * (texte d'un panneau, crâne, bannière...) nécessitent un packet séparé.
     */
    private void syncBeToClients(BlockEntity be, BlockPos pos) {
        var packet = be.toUpdatePacket();
        if (packet == null) return;
        double sqRange = 64.0 * 64.0;
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        for (PlayerEntity p : world.getPlayers()) {
            if (p instanceof ServerPlayerEntity sp && sp.squaredDistanceTo(cx, cy, cz) < sqRange) {
                sp.networkHandler.sendPacket(packet);
            }
        }
    }

    /**
     * Résout le NBT à appliquer à un block entity selon les modes demandés.
     *
     * Règles :
     *  - Coffre Schrödinger (LootTable présent, Items absent) → restitue uniquement la LootTable.
     *  - NbtMode.PARTIEL ou ItemMode.IGNORER_CONTENEURS → strip la clé Items.
     *  - Sinon → NBT complet.
     */
    private NbtCompound resolveBeNbt(NbtCompound storedNbt, ItemMode itemMode, NbtMode nbtMode) {
        // Coffre jamais ouvert → on ne remet que la LootTable (les Items seront générés à l'ouverture)
        if (storedNbt.contains("LootTable") && !storedNbt.contains("Items")) {
            NbtCompound lootOnly = new NbtCompound();
            lootOnly.putString("id", storedNbt.getString("id"));
            lootOnly.putInt("x", storedNbt.getInt("x"));
            lootOnly.putInt("y", storedNbt.getInt("y"));
            lootOnly.putInt("z", storedNbt.getInt("z"));
            lootOnly.putString("LootTable", storedNbt.getString("LootTable"));
            if (storedNbt.contains("LootTableSeed")) {
                lootOnly.putLong("LootTableSeed", storedNbt.getLong("LootTableSeed"));
            }
            return lootOnly;
        }

        // NbtMode.PARTIEL ou ItemMode.IGNORER_CONTENEURS → NBT sans inventaire
        boolean stripItems = (nbtMode == NbtMode.PARTIEL) || (itemMode == ItemMode.IGNORER_CONTENEURS);
        if (stripItems && storedNbt.contains("Items")) {
            NbtCompound stripped = storedNbt.copy();
            stripped.remove("Items");
            return stripped;
        }

        return storedNbt;
    }

    private SnapshotEntry findSnapshotBefore(ChunkPos pos, long beforeTs) throws IOException {
        List<SnapshotEntry> history = state.getHistoryIndex().getHistory(pos);
        SnapshotEntry found = null;
        for (SnapshotEntry entry : history) {
            if (entry.timestamp() < beforeTs) found = entry;
        }
        return found;
    }

    /** Parse les sections NBT d'un snapshot en une map sectionY → SectionData. */
    private Map<Integer, SectionData> parseSections(NbtList sectionsNbt, RegistryEntryLookup<net.minecraft.block.Block> blockLookup) {
        Map<Integer, SectionData> map = new HashMap<>();
        for (int i = 0; i < sectionsNbt.size(); i++) {
            NbtCompound sectionNbt = sectionsNbt.getCompound(i);
            int sectionY = sectionNbt.getInt("y");
            NbtList paletteNbt = sectionNbt.getList("palette", 10);
            int[] data = sectionNbt.getIntArray("data");

            BlockState[] palette = new BlockState[paletteNbt.size()];
            for (int j = 0; j < paletteNbt.size(); j++) {
                try {
                    palette[j] = NbtHelper.toBlockState(blockLookup, paletteNbt.getCompound(j));
                } catch (Exception e) {
                    palette[j] = Blocks.AIR.getDefaultState();
                }
            }
            map.put(sectionY, new SectionData(palette, data));
        }
        return map;
    }

    /** Parse les block entities d'un snapshot en une map BlockPos → NbtCompound. */
    private Map<BlockPos, NbtCompound> parseBlockEntities(NbtList beList) {
        Map<BlockPos, NbtCompound> map = new HashMap<>();
        for (int i = 0; i < beList.size(); i++) {
            NbtCompound beNbt = beList.getCompound(i);
            map.put(new BlockPos(beNbt.getInt("x"), beNbt.getInt("y"), beNbt.getInt("z")), beNbt);
        }
        return map;
    }

    /** Données d'une section : palette de BlockState + indices 4096. */
    private record SectionData(BlockState[] palette, int[] data) {
        BlockState getState(int lx, int ly, int lz) {
            int idx = ly * 256 + lz * 16 + lx;
            if (idx >= data.length || data[idx] >= palette.length) return Blocks.AIR.getDefaultState();
            return palette[data[idx]];
        }
    }
}
