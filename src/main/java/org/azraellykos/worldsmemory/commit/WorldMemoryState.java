package org.azraellykos.worldsmemory.commit;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.azraellykos.worldsmemory.WMConfig;
import org.azraellykos.worldsmemory.Worldsmemory;
import org.azraellykos.worldsmemory.api.SnapshotContext;
import org.azraellykos.worldsmemory.api.WMEvents;
import org.azraellykos.worldsmemory.storage.ChunkHistoryIndex;
import org.azraellykos.worldsmemory.storage.ChunkObjectStore;
import org.azraellykos.worldsmemory.storage.EntitySnapshotStore;
import org.azraellykos.worldsmemory.storage.PlayerDeathStore;
import org.azraellykos.worldsmemory.storage.SeedBaselineStore;
import org.azraellykos.worldsmemory.storage.SeedDataStore;
import org.azraellykos.worldsmemory.storage.SnapshotEntry;
import org.azraellykos.worldsmemory.storage.SpawnpointStore;
import org.azraellykos.worldsmemory.storage.WMChunkSerializer;
import org.azraellykos.worldsmemory.util.HashUtil;

import java.util.ArrayList;
import java.util.List;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Per-world state: owns the object store, history index, dirty tracker and scheduler.
 * Accessed via the static registry keyed on world registry key.
 */
public class WorldMemoryState {

    private static final Map<RegistryKey<World>, WorldMemoryState> REGISTRY = new ConcurrentHashMap<>();

    private final RegistryKey<World> worldKey;
    private final Path worldMemoryDir;
    private final ChunkObjectStore objectStore;
    private final ChunkHistoryIndex historyIndex;
    private final SeedBaselineStore seedStore;
    private final SeedDataStore seedDataStore;
    private final EntitySnapshotStore entityStore;
    private final SpawnpointStore spawnpointStore;
    private final PlayerDeathStore playerDeathStore;
    final DirtyChunkTracker dirtyTracker;
    private final CommitScheduler scheduler;
    private final ConcurrentHashMap<ChunkPos, CauseModification> immediateQueue = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, UUID> queueUuids = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Long> lastCommitTimes = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor;
    private final TntChainTracker tntChainTracker;
    private final CrashDetector crashDetector;
    /** Chunks whose pre-modification seed has been captured (or is in-flight) this session. */
    private final Set<ChunkPos> seedCaptured = ConcurrentHashMap.newKeySet();
    /** True si la session précédente s'est terminée en crash (état potentiellement incohérent). */
    private volatile boolean estFiable = true;

    private WorldMemoryState(RegistryKey<World> worldKey, Path worldMemoryDir) {
        this.worldKey = worldKey;
        this.worldMemoryDir = worldMemoryDir;
        this.objectStore = new ChunkObjectStore(worldMemoryDir);
        this.historyIndex = new ChunkHistoryIndex(worldMemoryDir);
        this.seedStore = new SeedBaselineStore(worldMemoryDir);
        this.seedDataStore = new SeedDataStore(worldMemoryDir);
        this.entityStore = new EntitySnapshotStore(worldMemoryDir);
        this.spawnpointStore = new SpawnpointStore(worldMemoryDir);
        this.playerDeathStore = new PlayerDeathStore(worldMemoryDir);
        try {
            this.spawnpointStore.load();
        } catch (IOException e) {
            Worldsmemory.LOGGER.warn("[WM] Failed to load spawnpoints for {}", worldKey.getValue(), e);
        }
        this.dirtyTracker = new DirtyChunkTracker(this);
        this.scheduler = new CommitScheduler(this);
        this.tntChainTracker = new TntChainTracker(this);
        this.crashDetector = new CrashDetector(worldMemoryDir);
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wm-io-" + worldKey.getValue().getPath());
            t.setDaemon(true);
            return t;
        });
        try {
            boolean crashed = crashDetector.checkAndArm();
            if (crashed) {
                this.estFiable = false;
                Worldsmemory.LOGGER.warn("[WM] [CRASH] Session précédente interrompue pour {} — état potentiellement incohérent, snapshot POST_CRASH planifié", worldKey.getValue());
            }
        } catch (IOException e) {
            Worldsmemory.LOGGER.warn("[WM] Impossible de vérifier le sentinel crash pour {}", worldKey.getValue(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Static registry helpers
    // -------------------------------------------------------------------------

    public static WorldMemoryState create(RegistryKey<World> key, Path worldMemoryDir) {
        WorldMemoryState state = new WorldMemoryState(key, worldMemoryDir);
        REGISTRY.put(key, state);
        return state;
    }

    public static WorldMemoryState get(RegistryKey<World> key) {
        return REGISTRY.get(key);
    }

    public static void remove(RegistryKey<World> key) {
        REGISTRY.remove(key);
    }

    /** Drains the I/O executor gracefully, waiting up to 10 seconds for pending writes. */
    public void shutdown() {
        crashDetector.disarm();
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                Worldsmemory.LOGGER.warn("[WM] I/O thread for {} did not finish in 10s, forcing shutdown", worldKey.getValue());
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // Commit logic
    // -------------------------------------------------------------------------

    void scheduleImmediateCommit(ChunkPos pos, CauseModification cause, UUID playerUuid) {
        if (immediateQueue.size() >= WMConfig.get().maxSnapshotsEnMemoire && !immediateQueue.containsKey(pos)) {
            Worldsmemory.LOGGER.debug("[WM] Queue pleine ({}) — commit {} cause={} ignoré",
                WMConfig.get().maxSnapshotsEnMemoire, pos, cause);
            return;
        }
        immediateQueue.merge(pos, cause, (existing, incoming) ->
            incoming.getPriority() > existing.getPriority() ? incoming : existing
        );
        if (playerUuid != null) {
            queueUuids.put(pos, playerUuid);
        } else {
            // No UUID owner for this cause — clear any stale UUID from a previous cause
            // so it doesn't get misattributed in the SnapshotEntry.
            queueUuids.remove(pos);
        }
    }

    void scheduleImmediateCommit(ChunkPos pos, CauseModification cause) {
        scheduleImmediateCommit(pos, cause, null);
    }

    /**
     * Adds all currently dirty chunks to the commit queue with their tracked cause.
     * Called by the temporal trigger — I/O is spread over subsequent ticks.
     */
    public void queueDirtyChunks() {
        Map<ChunkPos, CauseModification> dirty = dirtyTracker.consumeDirtyWithCauses();
        Map<ChunkPos, UUID> uuids = dirtyTracker.consumeUuids();
        if (dirty.isEmpty()) return;
        dirty.forEach((pos, cause) -> scheduleImmediateCommit(pos, cause, uuids.get(pos)));
        Worldsmemory.LOGGER.debug("[WM] Temporal trigger: queued {} dirty chunks in {}", dirty.size(), worldKey.getValue());
    }

    /**
     * Commits up to MAX_COMMITS_PER_TICK chunks from the queue per server tick.
     * Chunks that were committed within SCAN_COOLDOWN_MS are skipped this tick and retried next.
     * Serialization runs on the server thread; GZIP + disk write are offloaded.
     */
    public void drainImmediateQueue(MinecraftServer server) {
        if (immediateQueue.isEmpty()) return;
        ServerWorld world = server.getWorld(worldKey);
        if (world == null) return;

        long now = System.currentTimeMillis();
        int committed = 0;
        Iterator<Map.Entry<ChunkPos, CauseModification>> it = immediateQueue.entrySet().iterator();
        while (it.hasNext() && committed < WMConfig.get().maxCommitsPerTick) {
            Map.Entry<ChunkPos, CauseModification> entry = it.next();
            ChunkPos pos = entry.getKey();

            Long last = lastCommitTimes.get(pos);
            if (last != null && now - last < WMConfig.get().scanCooldownMs) continue;

            it.remove();
            UUID uuid = queueUuids.remove(pos);
            commitChunk(world, pos, entry.getValue(), uuid);
            committed++;
        }
    }

    /**
     * Immediately captures the current state of the given chunks before an explosion
     * destroys them. Bypasses the rate-limit queue and cooldown — pre-snapshots are urgent.
     */
    public void preSnapshotForExplosion(ServerWorld world, Set<ChunkPos> chunks, CauseModification cause) {
        for (ChunkPos pos : chunks) {
            captureEntitiesForExplosion(world, pos, cause);
            commitChunk(world, pos, cause, null);
            dirtyTracker.upgradeCause(pos, cause);
        }
    }

    /**
     * Snapshots immédiat d'un ensemble de chunks (API publique — {@code WorldMemory.snapshot()}).
     * Bypasse la queue de rate-limit, sans upgrade de cause dans le dirty tracker.
     */
    public void snapshotNow(ServerWorld world, Set<ChunkPos> chunks, CauseModification cause) {
        for (ChunkPos pos : chunks) {
            commitChunk(world, pos, cause, null);
        }
    }

    /**
     * Captures the full NBT of all non-player entities in a chunk before an explosion
     * destroys them. Called on the server thread; disk write is offloaded to ioExecutor.
     */
    /**
     * Returns true if the entity carries NBT state worth preserving beyond its vanilla baseline:
     * equipment, custom name, potion effects, tamed status, or passengers.
     */
    public static boolean isInteresting(NbtCompound nbt) {
        // Mob armor (zombies, skeletons…)
        if (nbt.contains("ArmorItems")) {
            NbtList armor = nbt.getList("ArmorItems", 10);
            for (int i = 0; i < armor.size(); i++) {
                String id = armor.getCompound(i).getString("id");
                if (!id.isEmpty() && !id.equals("minecraft:air")) return true;
            }
        }
        // Mob holding an item
        if (nbt.contains("HandItems")) {
            NbtList hand = nbt.getList("HandItems", 10);
            for (int i = 0; i < hand.size(); i++) {
                String id = hand.getCompound(i).getString("id");
                if (!id.isEmpty() && !id.equals("minecraft:air")) return true;
            }
        }
        if (nbt.contains("CustomName")) return true;
        if (nbt.contains("ActiveEffects") && !nbt.getList("ActiveEffects", 10).isEmpty()) return true;
        // Wolf / cat / parrot — tamed and owned
        if (nbt.contains("Owner")) return true;
        // Horse / donkey / mule / pig / strider — tamed flag
        if (nbt.getBoolean("Tame")) return true;
        // Horse with saddle or armor
        if (nbt.contains("SaddleItem") && !nbt.getCompound("SaddleItem").isEmpty()) return true;
        if (nbt.contains("ArmorItem") && !nbt.getCompound("ArmorItem").isEmpty()) return true;
        // Donkey / mule with chest items
        if (nbt.getBoolean("ChestedHorse") && nbt.contains("Items") && !nbt.getList("Items", 10).isEmpty()) return true;
        if (nbt.contains("Passengers") && !nbt.getList("Passengers", 10).isEmpty()) return true;
        // Villager with active trades
        if (nbt.contains("Offers")) return true;
        // Villager with any real profession (even level 1 before trades generate)
        if (nbt.contains("VillagerData")) {
            String profession = nbt.getCompound("VillagerData").getString("profession");
            if (!profession.isEmpty() && !profession.equals("minecraft:none")) return true;
        }
        return false;
    }

    private void captureEntitiesForExplosion(ServerWorld world, ChunkPos pos, CauseModification cause) {
        Box box = new Box(pos.getStartX(), world.getBottomY(), pos.getStartZ(),
                          pos.getStartX() + 16, world.getTopY(), pos.getStartZ() + 16);

        List<Entity> entities = world.getEntitiesByClass(Entity.class, box,
                e -> !(e instanceof PlayerEntity) && e.isAlive());

        if (entities.isEmpty()) return;

        long ts = System.currentTimeMillis();
        List<NbtCompound> snapshots = new ArrayList<>(entities.size());

        // Build all snapshots — save everything for complete rollback (Phase 3).
        for (Entity entity : entities) {
            NbtCompound nbt = new NbtCompound();
            entity.writeNbt(nbt);
            nbt.putString("id", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            snapshots.add(nbt);
        }

        // Log only interesting entities to avoid bat/fish spam.
        if (Worldsmemory.DEBUG_TIMING) {
            int interestingCount = 0;
            for (NbtCompound nbt : snapshots) if (isInteresting(nbt)) interestingCount++;

            if (interestingCount > 0) {
                Worldsmemory.LOGGER.info("[WM] [ENTITE] ===== Capture {} cause={} — {}/{} intéressante(s) =====",
                        pos, cause, interestingCount, entities.size());
                for (int i = 0; i < entities.size(); i++) {
                    if (isInteresting(snapshots.get(i))) logEntityCapture(entities.get(i), snapshots.get(i));
                }
                Worldsmemory.LOGGER.info("[WM] [ENTITE] ===== Fin capture {} ts={} =====", pos, ts);
            }
        }

        ioExecutor.submit(() -> {
            try {
                entityStore.store(pos, ts, snapshots);
            } catch (IOException e) {
                Worldsmemory.LOGGER.error("[WM] Échec sauvegarde entités pour {}", pos, e);
            }
        });
    }

    /**
     * Captures the full entity state of the chunk when a player kills an interesting mob.
     * Saves ALL entities in the chunk (not just the killed one) so a rollback can restore
     * the complete chunk state — including the dying entity, which is still alive at this point.
     * No-op if the killed entity has no notable NBT (vanilla baseline).
     */
    public void capturePlayerKillEntity(ServerWorld world, Entity killedEntity, UUID playerUuid) {
        NbtCompound killedNbt = new NbtCompound();
        killedEntity.writeNbt(killedNbt);
        killedNbt.putString("id", Registries.ENTITY_TYPE.getId(killedEntity.getType()).toString());

        ChunkPos pos = new ChunkPos(killedEntity.getBlockPos());
        long ts = System.currentTimeMillis();

        // Capture ALL entities in the chunk so the rollback restores the full state.
        // Use !isRemoved() instead of isAlive() — ALLOW_DEATH fires after health drops to 0
        // but before the entity is removed, so LivingEntity.isAlive() already returns false.
        Box box = new Box(pos.getStartX(), world.getBottomY(), pos.getStartZ(),
                          pos.getStartX() + 16, world.getTopY(), pos.getStartZ() + 16);
        List<Entity> entities = world.getEntitiesByClass(Entity.class, box,
                e -> !(e instanceof PlayerEntity) && !e.isRemoved());

        List<NbtCompound> snapshots = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            NbtCompound nbt = new NbtCompound();
            entity.writeNbt(nbt);
            nbt.putString("id", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            // Entity may have health=0 at this point (lethal hit already applied) —
            // clamp to 1 so it doesn't die again on restoration.
            if (nbt.contains("Health") && nbt.getFloat("Health") <= 0f) {
                nbt.putFloat("Health", 1.0f);
            }
            snapshots.add(nbt);
        }

        // Save if the killed entity OR any entity present in the chunk has notable NBT
        // (e.g. killing a plain zombie near a villager with trades must still save the villager).
        boolean anyInteresting = isInteresting(killedNbt)
                || snapshots.stream().anyMatch(WorldMemoryState::isInteresting);
        if (!anyInteresting) return;

        if (Worldsmemory.DEBUG_TIMING) {
            Worldsmemory.LOGGER.info("[WM] [ENTITE] Kill joueur {} → snapshot {} entité(s) pour {}", playerUuid, snapshots.size(), pos);
            logEntityCapture(killedEntity, killedNbt);
        }

        // Register the timestamp immediately so getTimestamps() returns it before the async write finishes.
        entityStore.notifyPending(pos, ts);

        ioExecutor.submit(() -> {
            try {
                entityStore.store(pos, ts, snapshots);
            } catch (IOException e) {
                Worldsmemory.LOGGER.error("[WM] Échec sauvegarde entités (kill joueur) pour {}", pos, e);
            }
        });
    }

    /**
     * Captures the full NBT of a player just before they die.
     * Persisted to disk so the player can be restored after a chunk rollback,
     * even if they reconnected in the meantime (hardcore mode).
     */
    public void capturePlayerDeath(ServerPlayerEntity player) {
        NbtCompound nbt = new NbtCompound();
        player.writeNbt(nbt);
        ChunkPos chunk = new ChunkPos(player.getBlockPos());
        nbt.putInt("WM_DeathChunkX", chunk.x);
        nbt.putInt("WM_DeathChunkZ", chunk.z);
        nbt.putLong("WM_DeathTimestamp", System.currentTimeMillis());

        UUID uuid = player.getUuid();
        ioExecutor.submit(() -> {
            try {
                playerDeathStore.save(uuid, nbt);
                Worldsmemory.LOGGER.info("[WM] [HARDCORE] Mort joueur {} sauvegardée (chunk {})", uuid, chunk);
            } catch (IOException e) {
                Worldsmemory.LOGGER.error("[WM] Impossible de sauvegarder la mort du joueur {}", uuid, e);
            }
        });
    }

    private static void logEntityCapture(Entity entity, NbtCompound nbt) {
        StringBuilder sb = new StringBuilder();
        sb.append("  → ").append(nbt.getString("id"))
          .append("  uuid=").append(entity.getUuidAsString())
          .append("  pos=(").append(String.format("%.2f", entity.getX()))
          .append(", ").append(String.format("%.2f", entity.getY()))
          .append(", ").append(String.format("%.2f", entity.getZ())).append(")");

        if (nbt.contains("ArmorItems")) {
            NbtList armor = nbt.getList("ArmorItems", 10);
            String[] slotNames = {"boots", "leggings", "chestplate", "helmet"};
            List<String> pieces = new ArrayList<>();
            for (int i = 0; i < armor.size(); i++) {
                String id = armor.getCompound(i).getString("id");
                if (!id.isEmpty() && !id.equals("minecraft:air")) {
                    pieces.add(slotNames[i] + "=" + id.replace("minecraft:", ""));
                }
            }
            if (!pieces.isEmpty()) sb.append("\n    Armure: ").append(pieces);
        }
        if (nbt.contains("HandItems")) {
            NbtList hand = nbt.getList("HandItems", 10);
            String mainHand = hand.size() > 0 ? hand.getCompound(0).getString("id") : "";
            if (!mainHand.isEmpty() && !mainHand.equals("minecraft:air")) {
                sb.append("\n    Main: ").append(mainHand.replace("minecraft:", ""));
            }
        }

        Worldsmemory.LOGGER.info(sb.toString());
    }

    /**
     * Pre-snapshots only the chunks that TntChainTracker missed for a given TNT entity
     * (e.g. chunks near a boundary that the radius estimate didn't cover).
     * The chunks are claimed from the chain so they will be included in the post-snapshot.
     */
    public void preSnapshotMissedChunks(ServerWorld world, UUID tntUuid,
                                         Set<ChunkPos> candidates, CauseModification cause) {
        Set<ChunkPos> missed = tntChainTracker.claimUnsnapshottedChunks(tntUuid, candidates);
        if (!missed.isEmpty()) {
            preSnapshotForExplosion(world, missed, cause);
        }
    }

    private void commitChunk(ServerWorld world, ChunkPos pos, CauseModification cause, UUID playerUuid) {
        WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
        if (chunk == null) return;

        lastCommitTimes.put(pos, System.currentTimeMillis());

        long serializeStart = System.nanoTime();
        final byte[] data = WMChunkSerializer.serialize(chunk);
        final String hash = HashUtil.sha1(data);
        final long ts = System.currentTimeMillis();
        if (Worldsmemory.DEBUG_TIMING && !(Worldsmemory.DEBUG_SKIP_FLUIDE && cause == CauseModification.FLUIDE)) {
            long serializeMs = (System.nanoTime() - serializeStart) / 1_000_000;
            String uuidStr = playerUuid != null ? " uuid=" + playerUuid : "";
            Worldsmemory.LOGGER.info("[WM] [SERVER] {} cause={}{} → {}B hash={} in {}ms",
                    pos, cause, uuidStr, data.length, hash.substring(0, 8), serializeMs);
        }

        final UUID finalUuid = playerUuid;
        final boolean logThisChunk = Worldsmemory.DEBUG_TIMING
                && !(Worldsmemory.DEBUG_SKIP_FLUIDE && cause == CauseModification.FLUIDE);

        ioExecutor.submit(() -> {
            try {
                // --- Seed baseline filter (Phase 1) ---
                String seedHash = seedStore.getSeedHash(pos);
                if (seedHash == null) {
                    // First observation — record hash + store raw bytes for SEED_ORIGINAL rollback.
                    seedStore.setSeedHash(pos, hash);
                    seedDataStore.store(pos, data);
                    if (logThisChunk) {
                        Worldsmemory.LOGGER.info("[WM] [IO] {} cause={} SEED_BASELINE enregistré hash={}",
                                pos, cause, hash.substring(0, 8));
                    }
                    return;
                }
                if (hash.equals(seedHash)) {
                    // Chunk returned to its seed state — nothing to store.
                    if (logThisChunk) {
                        Worldsmemory.LOGGER.info("[WM] [IO] {} cause={} RETOUR_SEED skip", pos, cause);
                    }
                    return;
                }

                // --- Standard dedup: same hash as last committed snapshot ---
                SnapshotEntry latest = historyIndex.getLatest(pos);
                if (latest != null && latest.hash().equals(hash)) {
                    if (logThisChunk) {
                        Worldsmemory.LOGGER.info("[WM] [IO] {} cause={} INCHANGÉ (même hash, skip)", pos, cause);
                    }
                    return;
                }

                long ioStart = System.nanoTime();
                objectStore.storeWithHash(data, hash);
                historyIndex.append(pos, new SnapshotEntry(ts, hash, cause, finalUuid));
                long ioMs = (System.nanoTime() - ioStart) / 1_000_000;

                if (logThisChunk) {
                    if (latest == null) {
                        Worldsmemory.LOGGER.info("[WM] [IO] {} cause={} PREMIER COMMIT in {}ms", pos, cause, ioMs);
                    } else {
                        try {
                            byte[] prevData = objectStore.load(latest.hash());
                            String diff = WMChunkSerializer.diffSummary(prevData, data);
                            Worldsmemory.LOGGER.info("[WM] [IO] {} cause={} MODIFIÉ in {}ms:\n{}",
                                    pos, cause, ioMs, diff);
                        } catch (Exception e) {
                            Worldsmemory.LOGGER.info("[WM] [IO] {} cause={} MODIFIÉ in {}ms (diff indisponible)", pos, cause, ioMs);
                        }
                    }
                }

                // Déclencher AFTER_SNAPSHOT sur le thread serveur (tick suivant).
                SnapshotContext snapCtx = new SnapshotContext(worldKey, pos, hash, cause, ts);
                world.getServer().execute(() ->
                    WMEvents.AFTER_SNAPSHOT.invoker().afterSnapshot(snapCtx)
                );

            } catch (IOException e) {
                Worldsmemory.LOGGER.error("[WM] Failed to commit chunk {}", pos, e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Spawnpoint tracking
    // -------------------------------------------------------------------------

    /**
     * Records a player's spawnpoint in this world. Called from PlayerSpawnpointMixin
     * and from the JOIN event for players who already had a spawn before loading.
     */
    public void setPlayerSpawnpoint(UUID uuid, BlockPos pos, float angle, boolean forced) {
        spawnpointStore.set(uuid, pos, angle, forced);
        ioExecutor.submit(() -> {
            try {
                spawnpointStore.save();
            } catch (IOException e) {
                Worldsmemory.LOGGER.error("[WM] Failed to save spawnpoints for {}", worldKey.getValue(), e);
            }
        });
        if (Worldsmemory.DEBUG_TIMING) {
            Worldsmemory.LOGGER.info("[WM] [SPAWN] Player {} → spawn @ ({},{},{}) forced={} in {}",
                    uuid, pos.getX(), pos.getY(), pos.getZ(), forced, worldKey.getValue());
        }
    }

    /** Removes a player's spawnpoint from this world's store (called when spawn changes world or is cleared). */
    public void clearPlayerSpawnpoint(UUID uuid) {
        if (spawnpointStore.get(uuid) == null) return;
        spawnpointStore.remove(uuid);
        ioExecutor.submit(() -> {
            try {
                spawnpointStore.save();
            } catch (IOException e) {
                Worldsmemory.LOGGER.error("[WM] Failed to save spawnpoints for {}", worldKey.getValue(), e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Block entity pre-commit — commit chunk before a BE is destroyed
    // -------------------------------------------------------------------------

    /**
     * Si le chunk a des changements de block entity non encore commités (ex: items dans un coffre),
     * force un snapshot AVANT que le bloc ne soit remplacé.
     * Appelé depuis le HEAD injection de setBlockState quand l'état actuel a un BlockEntity.
     */
    public void preCommitIfBeDirty(ServerWorld world, ChunkPos pos) {
        if (dirtyTracker.consumeBeDirty(pos)) {
            commitChunk(world, pos, CauseModification.JOUEUR, null);
        }
    }

    // -------------------------------------------------------------------------
    // Seed pre-capture — called from mixin HEAD injection BEFORE a block changes
    // -------------------------------------------------------------------------

    /**
     * Captures the current chunk state as the seed baseline if this is the first
     * time this chunk is about to be modified this session.
     * Must be called on the server thread, before the block change is applied.
     */
    public void preCaptureSeedBeforeFirstChange(ServerWorld world, ChunkPos pos) {
        if (!seedCaptured.add(pos)) return; // Already handled this session
        // Post-crash : snapshot immédiat du chunk avant toute modification pour figer l'état réel.
        if (!estFiable) {
            scheduleImmediateCommit(pos, CauseModification.POST_CRASH);
        }
        WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
        if (chunk == null) {
            seedCaptured.remove(pos); // Retry on next change when chunk is loaded
            return;
        }
        final byte[] data = WMChunkSerializer.serialize(chunk);
        final String hash = HashUtil.sha1(data);
        ioExecutor.submit(() -> {
            try {
                if (seedStore.getSeedHash(pos) == null) {
                    // First ever observation — store hash + raw data
                    seedStore.setSeedHash(pos, hash);
                    seedDataStore.store(pos, data);
                    Worldsmemory.LOGGER.info("[WM] [SEED] Pre-capture {} hash={}", pos, hash.substring(0, 8));
                } else if (!seedDataStore.exists(pos)) {
                    // Migration: hash exists from an old session that pre-dates seedDataStore.
                    // Store current pre-modification state so SEED_ORIGINAL can function.
                    seedDataStore.store(pos, data);
                    Worldsmemory.LOGGER.info("[WM] [SEED] Migration seed_data {} (état pré-modif)", pos);
                }
            } catch (IOException e) {
                Worldsmemory.LOGGER.error("[WM] Échec pre-capture seed pour {}", pos, e);
                seedCaptured.remove(pos); // Allow retry
            }
        });
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public CommitScheduler getScheduler() { return scheduler; }
    public DirtyChunkTracker getDirtyTracker() { return dirtyTracker; }
    public TntChainTracker getTntChainTracker() { return tntChainTracker; }
    public ChunkObjectStore getObjectStore() { return objectStore; }
    public ChunkHistoryIndex getHistoryIndex() { return historyIndex; }
    public SeedBaselineStore getSeedStore() { return seedStore; }
    public SeedDataStore getSeedDataStore() { return seedDataStore; }
    public EntitySnapshotStore getEntityStore() { return entityStore; }
    public SpawnpointStore getSpawnpointStore() { return spawnpointStore; }
    public PlayerDeathStore getPlayerDeathStore() { return playerDeathStore; }

    // --- Entity UUID tracking (snapshot UUID → live UUID after rollback) ---

    private final ConcurrentHashMap<UUID, UUID> entityUuidMapping = new ConcurrentHashMap<>();

    /** Returns the live UUID currently representing the entity originally saved under snapshotUuid. */
    public UUID getLiveEntityUuid(UUID snapshotUuid) {
        return entityUuidMapping.getOrDefault(snapshotUuid, snapshotUuid);
    }

    /** Records that the entity originally saved under snapshotUuid is now alive as liveUuid. */
    public void setLiveEntityUuid(UUID snapshotUuid, UUID liveUuid) {
        entityUuidMapping.put(snapshotUuid, liveUuid);
    }

    // --- Per-chunk WM-spawned entity tracking (prevents temporal paradox) ---

    private final ConcurrentHashMap<ChunkPos, Set<UUID>> wmLiveEntitiesPerChunk = new ConcurrentHashMap<>();

    /** Returns all UUIDs currently alive that were spawned by WM rollback for this chunk. */
    public Set<UUID> getWmLiveEntities(ChunkPos pos) {
        return wmLiveEntitiesPerChunk.computeIfAbsent(pos, k -> ConcurrentHashMap.newKeySet());
    }

    /** Records that a WM rollback spawned (or restored in-place) an entity for this chunk. */
    public void recordWmSpawn(ChunkPos pos, UUID uuid) {
        getWmLiveEntities(pos).add(uuid);
    }

    /** Clears the WM-spawned entity set for this chunk (call before restoring a new snapshot). */
    public void clearWmLiveEntities(ChunkPos pos) {
        wmLiveEntitiesPerChunk.remove(pos);
    }
    public RegistryKey<World> getWorldKey() { return worldKey; }
    public Path getWorldMemoryDir() { return worldMemoryDir; }
    /** False si la session précédente s'est terminée en crash. */
    public boolean isEstFiable() { return estFiable; }
    /** Marque la session comme fiable (après validation manuelle ou redémarrage propre). */
    public void markFiable() { this.estFiable = true; }
}
