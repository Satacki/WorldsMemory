package org.azraellykos.worldsmemory.storage;

import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    /** Returns all chunk positions that have a seed hash recorded. */
    public List<ChunkPos> getAllTrackedChunks() {
        if (!Files.exists(seedDir)) return Collections.emptyList();
        List<ChunkPos> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(seedDir)) {
            for (Path file : stream) {
                String[] parts = file.getFileName().toString().split("\\.");
                if (parts.length == 2) {
                    try {
                        result.add(new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return result;
    }

    private Path seedFile(ChunkPos pos) {
        return seedDir.resolve(pos.x + "." + pos.z);
    }
}
