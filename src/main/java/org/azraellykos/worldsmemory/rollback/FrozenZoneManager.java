package org.azraellykos.worldsmemory.rollback;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre des zones gelées pendant un rollback actif.
 * Une zone gelée refuse toutes les modifications extérieures (hors moteur de rollback lui-même).
 * Thread-safe : ConcurrentHashMap, utilisé depuis le thread serveur et potentiellement d'autres.
 */
public final class FrozenZoneManager {

    private static final Map<RegistryKey<World>, Set<ChunkPos>> FROZEN = new ConcurrentHashMap<>();

    private FrozenZoneManager() {}

    public static void freeze(RegistryKey<World> world, Set<ChunkPos> chunks) {
        FROZEN.computeIfAbsent(world, k -> ConcurrentHashMap.newKeySet()).addAll(chunks);
    }

    public static void unfreeze(RegistryKey<World> world, Set<ChunkPos> chunks) {
        Set<ChunkPos> frozenChunks = FROZEN.get(world);
        if (frozenChunks == null) return;
        frozenChunks.removeAll(chunks);
        if (frozenChunks.isEmpty()) FROZEN.remove(world);
    }

    public static boolean isFrozen(RegistryKey<World> world, ChunkPos chunk) {
        Set<ChunkPos> frozenChunks = FROZEN.get(world);
        return frozenChunks != null && frozenChunks.contains(chunk);
    }
}
