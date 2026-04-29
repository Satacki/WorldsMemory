package org.azraellykos.worldsmemory.commit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects server crashes via a sentinel file.
 *
 * Protocol:
 *   - On world init: checkAndArm() writes the sentinel. If the file already existed,
 *     the previous session never called disarm() → crash detected.
 *   - On clean shutdown: disarm() deletes the sentinel.
 */
public final class CrashDetector {

    private final Path sentinel;

    public CrashDetector(Path worldMemoryDir) {
        this.sentinel = worldMemoryDir.resolve("crash_sentinel");
    }

    /**
     * Checks whether the previous session crashed, then arms the sentinel for this session.
     *
     * @return true if a crash was detected (sentinel present without a prior disarm)
     */
    public boolean checkAndArm() throws IOException {
        boolean crashed = Files.exists(sentinel);
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel, Long.toString(System.currentTimeMillis()));
        return crashed;
    }

    /** Deletes the sentinel — called on a clean shutdown. */
    public void disarm() {
        try {
            Files.deleteIfExists(sentinel);
        } catch (IOException ignored) {}
    }
}
