package org.azraellykos.worldsmemory.storage;

import org.azraellykos.worldsmemory.util.HashUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Content Addressable Storage: stores raw byte blobs identified by their SHA-1 hash.
 * Identical content is stored only once (deduplication).
 * Each object is GZIP-compressed on disk.
 *
 * Layout: {base}/objects/{ab}/{remaining38}
 */
public class ChunkObjectStore {

    private final Path objectsDir;

    public ChunkObjectStore(Path worldMemoryDir) {
        this.objectsDir = worldMemoryDir.resolve("objects");
    }

    /**
     * Stores data and returns its SHA-1 hash. Idempotent: if the object already
     * exists the bytes are not written again.
     */
    public String store(byte[] data) throws IOException {
        String hash = HashUtil.sha1(data);
        storeWithHash(data, hash);
        return hash;
    }

    /**
     * Stores data using a pre-computed SHA-1 hash. Used when the hash was already
     * computed on the server thread so we avoid recomputing it here.
     */
    public void storeWithHash(byte[] data, String hash) throws IOException {
        Path path = objectPath(hash);
        if (Files.exists(path)) return;
        Files.createDirectories(path.getParent());
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
            out.write(data);
        }
    }

    public byte[] load(String hash) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(objectPath(hash)))) {
            return in.readAllBytes();
        }
    }

    public boolean exists(String hash) {
        return Files.exists(objectPath(hash));
    }

    /**
     * Supprime les objets CAS dont le hash n'est pas dans {@code referencedHashes}.
     *
     * @return nombre d'objets supprimés
     */
    public int removeOrphans(Set<String> referencedHashes) throws IOException {
        if (!Files.exists(objectsDir)) return 0;
        int removed = 0;
        List<Path> toDelete = new ArrayList<>();
        // Parcours : objects/{prefix}/{suffix}
        try (var prefixStream = Files.list(objectsDir)) {
            for (Path prefixDir : prefixStream.toList()) {
                if (!Files.isDirectory(prefixDir)) continue;
                String prefix = prefixDir.getFileName().toString();
                try (var suffixStream = Files.list(prefixDir)) {
                    for (Path obj : suffixStream.toList()) {
                        String hash = prefix + obj.getFileName().toString();
                        if (!referencedHashes.contains(hash)) {
                            toDelete.add(obj);
                        }
                    }
                }
            }
        }
        for (Path p : toDelete) {
            Files.deleteIfExists(p);
            removed++;
        }
        return removed;
    }

    private Path objectPath(String hash) {
        return objectsDir.resolve(hash.substring(0, 2)).resolve(hash.substring(2));
    }
}
