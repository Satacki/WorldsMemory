package org.azraellykos.worldsmemory.commit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Détecte les crashs serveur via un fichier sentinelle.
 *
 * Protocole :
 *   - À l'init du monde : checkAndArm() écrit le sentinel. Si le fichier existait déjà,
 *     la session précédente n'a pas appelé disarm() → crash détecté.
 *   - Au shutdown propre : disarm() supprime le sentinel.
 */
public final class CrashDetector {

    private final Path sentinel;

    public CrashDetector(Path worldMemoryDir) {
        this.sentinel = worldMemoryDir.resolve("crash_sentinel");
    }

    /**
     * Vérifie si la session précédente a crashé, puis arme le sentinel pour cette session.
     *
     * @return true si un crash a été détecté (sentinel présent sans disarm préalable)
     */
    public boolean checkAndArm() throws IOException {
        boolean crashed = Files.exists(sentinel);
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel, Long.toString(System.currentTimeMillis()));
        return crashed;
    }

    /** Supprime le sentinel — appelé lors d'un shutdown propre. */
    public void disarm() {
        try {
            Files.deleteIfExists(sentinel);
        } catch (IOException ignored) {}
    }
}
