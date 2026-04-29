package org.azraellykos.worldsmemory.storage;

import net.minecraft.util.math.ChunkPos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Stores the raw serialized chunk data for the seed baseline of each chunk.
 * Unlike ChunkObjectStore (CAS), this is keyed per-chunk and written exactly once
 * on first observation — it materializes the "commit zéro" for SEED_ORIGINAL rollback.
 *
 * Layout: {worldMemoryDir}/seed_data/{cx}.{cz}  (GZIP-compressed)
 */
public class SeedDataStore {

    private final Path seedDataDir;

    public SeedDataStore(Path worldMemoryDir) {
        this.seedDataDir = worldMemoryDir.resolve("seed_data");
    }

    /** Stores the raw chunk bytes for this chunk. No-op if the file already exists. */
    public void store(ChunkPos pos, byte[] data) throws IOException {
        Path file = dataFile(pos);
        if (Files.exists(file)) return;
        Files.createDirectories(seedDataDir);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(data);
        }
    }

    /** Loads the seed baseline bytes for this chunk. */
    public byte[] load(ChunkPos pos) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(dataFile(pos)))) {
            return in.readAllBytes();
        }
    }

    public boolean exists(ChunkPos pos) {
        return Files.exists(dataFile(pos));
    }

    /** Returns the file creation time (millis) of the seed file, or -1 if absent or unreadable. */
    public long getLastModifiedMillis(ChunkPos pos) {
        try {
            Path file = dataFile(pos);
            if (!Files.exists(file)) return -1L;
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    private Path dataFile(ChunkPos pos) {
        return seedDataDir.resolve(pos.x + "." + pos.z);
    }
}
