package org.azraellykos.worldsmemory.storage;

import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stores the "seed baseline" hash for each chunk — the SHA-1 of the chunk's state
 * the very first time it was observed. This hash is never written to the CAS object
 * store; it is the implicit commit-zero referenced by the spec ("Seed = commit zéro
 * implicit baseline, never stored").
 *
 * Layout: {worldMemoryDir}/seed/{cx}.{cz}  (plain UTF-8 text, 40-char hex SHA-1)
 *
 * Idempotent: once set, the file is never overwritten. All access is from the
 * single-threaded IO executor, so no additional locking is needed.
 */
public class SeedBaselineStore {

    private final Path seedDir;

    public SeedBaselineStore(Path worldMemoryDir) {
        this.seedDir = worldMemoryDir.resolve("seed");
    }

    /** Returns the seed hash for this chunk, or null if never recorded. */
    public String getSeedHash(ChunkPos pos) throws IOException {
        Path file = seedFile(pos);
        if (!Files.exists(file)) return null;
        return Files.readString(file).trim();
    }

    /**
     * Records the seed hash for this chunk. No-op if already set — the seed
     * baseline is fixed at the first observation and never updated.
     */
    public void setSeedHash(ChunkPos pos, String hash) throws IOException {
        Path file = seedFile(pos);
        if (Files.exists(file)) return;
        Files.createDirectories(seedDir);
        Files.writeString(file, hash);
    }

    public boolean hasSeed(ChunkPos pos) {
        return Files.exists(seedFile(pos));
    }

    private Path seedFile(ChunkPos pos) {
        return seedDir.resolve(pos.x + "." + pos.z);
    }
}
