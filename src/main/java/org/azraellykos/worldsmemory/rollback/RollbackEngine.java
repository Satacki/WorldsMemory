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
import net.minecraft.entity.Tameable;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameMode;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.azraellykos.worldsmemory.Worldsmemory;
import org.azraellykos.worldsmemory.api.RollbackContext;
import org.azraellykos.worldsmemory.api.WMEvents;
import org.azraellykos.worldsmemory.commit.DirtyChunkTracker;
import org.azraellykos.worldsmemory.commit.WorldMemoryState;
import org.azraellykos.worldsmemory.storage.SnapshotEntry;
import org.azraellykos.worldsmemory.storage.SpawnpointStore;
import org.azraellykos.worldsmemory.storage.WMChunkSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

        // --- BEFORE_ROLLBACK — annulable ---
        RollbackContext ctx = RollbackContext.from(world, request);
        if (!WMEvents.BEFORE_ROLLBACK.invoker().beforeRollback(ctx)) {
            Worldsmemory.LOGGER.info("[WM] [ROLLBACK] Annulé par un listener BEFORE_ROLLBACK");
            return RollbackResult.cancelled("Annulé par un listener externe");
        }

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

        RollbackResult result;
        if (degraded) {
            result = RollbackResult.degraded(restoredChunks, totalBlocks, restoredEntities,
                "Certains chunks n'avaient pas de snapshot disponible");
        } else {
            result = RollbackResult.success(restoredChunks, totalBlocks, restoredEntities);
        }

        // --- AFTER_ROLLBACK ---
        WMEvents.AFTER_ROLLBACK.invoker().afterRollback(ctx, result);
        if (result.degraded()) {
            WMEvents.ON_ROLLBACK_DEGRADED.invoker().onDegraded(ctx, result);
        }

        // Restore any hardcore player who died in the rolled-back area
        checkAndRestoreDeadPlayers(chunks);

        return result;
    }

    // -------------------------------------------------------------------------
    // Hardcore player restoration
    // -------------------------------------------------------------------------

    /**
     * After a rollback, restores any online player whose recorded death chunk
     * falls within the rolled-back area and who is currently in spectator mode.
     */
    private void checkAndRestoreDeadPlayers(Set<ChunkPos> rolledBackChunks) {
        var deathStore = state.getPlayerDeathStore();
        for (PlayerEntity p : world.getPlayers()) {
            if (!(p instanceof ServerPlayerEntity player)) continue;
            if (player.interactionManager.getGameMode() != GameMode.SPECTATOR) continue;
            if (!deathStore.hasDeath(player.getUuid())) continue;
            try {
                NbtCompound deathNbt = deathStore.load(player.getUuid());
                if (deathNbt == null) continue;
                ChunkPos deathChunk = new ChunkPos(
                    deathNbt.getInt("WM_DeathChunkX"),
                    deathNbt.getInt("WM_DeathChunkZ")
                );
                if (!rolledBackChunks.contains(deathChunk)) continue;
                applyPlayerDeathRecord(player, deathNbt);
                deathStore.delete(player.getUuid());
                Worldsmemory.LOGGER.info("[WM] [HARDCORE] Joueur {} restauré après rollback", player.getUuid());
            } catch (IOException e) {
                Worldsmemory.LOGGER.error("[WM] Erreur restauration joueur {}", p.getUuid(), e);
            }
        }
    }

    /** Restores a player's game mode, health, inventory, and position from a death record. */
    public static void applyPlayerDeathRecord(ServerPlayerEntity player, NbtCompound nbt) {
        player.changeGameMode(GameMode.SURVIVAL);
        // Health saved in NBT is 0 — ALLOW_DEATH fires after the fatal hit is applied.
        // Restore to full health so the player doesn't die again on restoration.
        player.setHealth(player.getMaxHealth());

        player.getInventory().clear();
        player.getInventory().readNbt(nbt.getList("Inventory", 10));

        if (nbt.contains("Pos", 9)) {
            NbtList pos = nbt.getList("Pos", 6);
            float yaw   = nbt.contains("Rotation", 9) ? nbt.getList("Rotation", 5).getFloat(0) : player.getYaw();
            float pitch = nbt.contains("Rotation", 9) ? nbt.getList("Rotation", 5).getFloat(1) : player.getPitch();
            player.teleport(player.getServerWorld(),
                pos.getDouble(0), pos.getDouble(1), pos.getDouble(2),
                java.util.Set.of(), yaw, pitch);
        }
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

            // --- Collect entity NBTs from snapshot (empty if no snapshot or SEED) ---
            List<NbtCompound> entityNbts = Collections.emptyList();
            if (!isSeed) {
                List<Long> timestamps = state.getEntityStore().getTimestamps(pos);
                if (!timestamps.isEmpty()) {
                    long targetTs = -1;
                    for (Long ts : timestamps) {
                        if (ts <= request.beforeTimestamp) targetTs = ts;
                    }
                    if (targetTs >= 0) {
                        entityNbts = state.getEntityStore().get(pos, targetTs);
                    }
                }
            }

            // --- Kill all WM-spawned entities for this chunk from any previous rollback ---
            // This prevents the temporal paradox (two entities from different snapshots coexisting).
            for (UUID wmUuid : state.getWmLiveEntities(pos)) {
                Entity wmEntity = world.getEntity(wmUuid);
                if (wmEntity != null && !wmEntity.isRemoved()) wmEntity.discard();
            }
            state.clearWmLiveEntities(pos);

            // --- Build teleport set: live entities already in this chunk that we will restore in-place ---
            // Natural tamed pets (liveUuid == snapUuid, entity never died) are left alone.
            // WM-spawned entities (liveUuid != snapUuid, assigned a random UUID on spawn) are always tracked.
            Set<UUID> teleportSet = new HashSet<>();
            for (NbtCompound nbt : entityNbts) {
                if (!nbt.containsUuid("UUID")) continue;
                UUID snapUuid = nbt.getUuid("UUID");
                UUID liveUuid = state.getLiveEntityUuid(snapUuid);
                Entity live = world.getEntity(liveUuid);
                if (live == null || live.isRemoved()) continue;
                boolean wmSpawned = !liveUuid.equals(snapUuid);
                if (wmSpawned || !isTamedAndAlive(live)) {
                    if (new ChunkPos(live.getBlockPos()).equals(pos)) {
                        teleportSet.add(liveUuid);
                    }
                }
            }

            // --- Remove chunk entities, sparing teleport targets and natural tamed pets ---
            boolean removeItems = (request.entityMode == EntityMode.RESTAURER_TOUT);
            world.getEntitiesByClass(Entity.class, box, e -> {
                if (e instanceof PlayerEntity) return false;
                if (!removeItems && e instanceof ItemEntity) return false;
                if (teleportSet.contains(e.getUuid())) return false;
                // Natural tamed pets not in the teleport set are left alone
                return !isTamedAndAlive(e);
            }).forEach(Entity::discard);

            // --- Kill WM-tracked entities that wandered to other chunks ---
            // Never kill natural tamed pets (liveUuid == snapUuid means no prior WM spawn).
            for (NbtCompound nbt : entityNbts) {
                if (!nbt.containsUuid("UUID")) continue;
                UUID snapUuid = nbt.getUuid("UUID");
                UUID liveUuid = state.getLiveEntityUuid(snapUuid);
                Entity live = world.getEntity(liveUuid);
                if (live == null || live.isRemoved()) continue;
                boolean wmSpawned = !liveUuid.equals(snapUuid);
                if (wmSpawned || !isTamedAndAlive(live)) {
                    if (!new ChunkPos(live.getBlockPos()).equals(pos)) live.discard();
                }
            }

            if (isSeed) continue;

            // --- Restore entities: teleport in-place or spawn new ---
            for (NbtCompound nbt : entityNbts) {
                String typeId = nbt.getString("id");
                if (typeId.isEmpty() || SKIP_ENTITY_TYPES.contains(typeId)) continue;

                UUID snapUuid = nbt.containsUuid("UUID") ? nbt.getUuid("UUID") : null;

                if (snapUuid != null) {
                    UUID liveUuid = state.getLiveEntityUuid(snapUuid);
                    Entity live = world.getEntity(liveUuid);
                    if (live != null && !live.isRemoved()) {
                        boolean wmSpawned = !liveUuid.equals(snapUuid);
                        // Leave natural tamed pets alone — only skip if not WM-spawned
                        if (!wmSpawned && isTamedAndAlive(live)) continue;
                        // Restore NBT in-place, keep live UUID
                        UUID savedUuid = live.getUuid();
                        live.readNbt(nbt);
                        live.setUuid(savedUuid);
                        state.recordWmSpawn(pos, savedUuid);
                        count++;
                        continue;
                    }
                }

                // Entity is dead or has no tracked UUID → spawn new
                Entity spawned = spawnEntityFromNbt(nbt);
                if (spawned != null) {
                    if (snapUuid != null) state.setLiveEntityUuid(snapUuid, spawned.getUuid());
                    state.recordWmSpawn(pos, spawned.getUuid());
                    count++;
                }
            }
        }

        return count;
    }

    /** Returns true if the entity is a tamed pet that is currently alive — these are never reset or killed by rollback. */
    private static boolean isTamedAndAlive(Entity entity) {
        return entity instanceof TameableEntity tameable && tameable.isTamed() && !entity.isRemoved();
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
    // Undo support
    // -------------------------------------------------------------------------

    /**
     * Captures the current state of each chunk into the CAS without touching the history index.
     * Call this before {@link #execute} to enable {@link #applyUndo}.
     */
    public UndoSnapshot captureUndo(Set<ChunkPos> chunks) {
        Map<ChunkPos, String> hashes = new HashMap<>();
        for (ChunkPos pos : chunks) {
            WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
            if (chunk == null) continue;
            try {
                byte[] data = WMChunkSerializer.serialize(chunk);
                String hash = state.getObjectStore().store(data);
                hashes.put(pos, hash);
            } catch (IOException e) {
                Worldsmemory.LOGGER.warn("[WM] [UNDO] Impossible de capturer chunk {} pour undo", pos, e);
            }
        }
        return new UndoSnapshot(chunks, hashes);
    }

    /**
     * Restores chunks from an {@link UndoSnapshot}. Does not fire BEFORE/AFTER_ROLLBACK events.
     *
     * @return number of chunks actually restored
     */
    public int applyUndo(UndoSnapshot undo) {
        Set<ChunkPos> affectedChunks = undo.hashByChunk().keySet();
        FrozenZoneManager.freeze(world.getRegistryKey(), affectedChunks);
        DirtyChunkTracker.beginRollback();
        int restored = 0;
        try {
            for (Map.Entry<ChunkPos, String> entry : undo.hashByChunk().entrySet()) {
                try {
                    byte[] data = state.getObjectStore().load(entry.getValue());
                    NbtCompound snapshot = WMChunkSerializer.fromBytes(data);
                    int blocks = applySnapshotToChunk(entry.getKey(), snapshot,
                        ItemMode.RESTAURER_CONTENEURS, NbtMode.COMPLET);
                    if (blocks >= 0) restored++;
                } catch (IOException e) {
                    Worldsmemory.LOGGER.error("[WM] [UNDO] Erreur hash={} chunk={}",
                        entry.getValue(), entry.getKey(), e);
                }
            }
        } finally {
            DirtyChunkTracker.endRollback();
            FrozenZoneManager.unfreeze(world.getRegistryKey(), affectedChunks);
        }
        return restored;
    }

    /**
     * Restores only entities for the given chunks — blocks are not touched.
     * Used by the interactive entity-apply command.
     */
    public RollbackResult executeEntityOnly(RollbackRequest request) {
        Set<ChunkPos> chunks = request.affectedChunks();
        DirtyChunkTracker.beginRollback();
        int restoredEntities = 0;
        boolean degraded = false;
        try {
            restoredEntities = restoreEntities(chunks, request);
        } catch (IOException e) {
            Worldsmemory.LOGGER.error("[WM] [ROLLBACK] Erreur I/O entités (entity-only)", e);
            degraded = true;
        } finally {
            DirtyChunkTracker.endRollback();
        }
        if (degraded) {
            return RollbackResult.degraded(0, 0, restoredEntities, "Erreur I/O entités");
        }
        // Also restore any hardcore player who died in the rolled-back area
        checkAndRestoreDeadPlayers(chunks);
        return RollbackResult.success(0, 0, restoredEntities);
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