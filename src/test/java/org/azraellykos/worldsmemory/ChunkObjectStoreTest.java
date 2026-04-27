package org.azraellykos.worldsmemory;

import org.azraellykos.worldsmemory.storage.ChunkObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class ChunkObjectStoreTest {

    private Path tempDir;
    private ChunkObjectStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("wm-cas-test");
        store = new ChunkObjectStore(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Delete temp dir recursively
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    @Test
    void storeReturnsHash() throws IOException {
        String hash = store.store("test data".getBytes(StandardCharsets.UTF_8));
        assertNotNull(hash);
        assertEquals(40, hash.length());
    }

    @Test
    void loadReturnsSameData() throws IOException {
        byte[] data = "chunk payload".getBytes(StandardCharsets.UTF_8);
        String hash = store.store(data);
        assertArrayEquals(data, store.load(hash));
    }

    @Test
    void existsReturnsTrueAfterStore() throws IOException {
        String hash = store.store("exists test".getBytes());
        assertTrue(store.exists(hash));
    }

    @Test
    void existsReturnsFalseForUnknownHash() {
        assertFalse(store.exists("0000000000000000000000000000000000000000"));
    }

    @Test
    void deduplication_sameDataStoredOnce() throws IOException {
        byte[] data = "duplicate data".getBytes(StandardCharsets.UTF_8);
        String hash1 = store.store(data);
        String hash2 = store.store(data);

        assertEquals(hash1, hash2, "Same data must produce the same hash");

        // Count object files — only 1 should exist
        long fileCount;
        try (var walk = Files.walk(tempDir.resolve("objects"))) {
            fileCount = walk.filter(Files::isRegularFile).count();
        }
        assertEquals(1, fileCount, "Identical data should be stored only once");
    }

    @Test
    void differentDataProduceDifferentHashes() throws IOException {
        String h1 = store.store("data A".getBytes(StandardCharsets.UTF_8));
        String h2 = store.store("data B".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(h1, h2);
    }

    @Test
    void objectPathFollowsGitLayout() throws IOException {
        String hash = store.store("layout test".getBytes(StandardCharsets.UTF_8));
        // Expects: objects/{2-char prefix}/{38-char suffix}
        Path expected = tempDir.resolve("objects")
                .resolve(hash.substring(0, 2))
                .resolve(hash.substring(2));
        assertTrue(Files.exists(expected), "Object should be stored at " + expected);
    }

    @Test
    void largePayloadRoundTrip() throws IOException {
        // Simulates a moderately large chunk (~50 KB uncompressed)
        byte[] large = new byte[50_000];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i % 127);
        String hash = store.store(large);
        assertArrayEquals(large, store.load(hash));
    }
}
