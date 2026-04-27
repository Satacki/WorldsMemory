package org.azraellykos.worldsmemory.purge;

public record PurgeResult(int chunksAffected, int entriesPurged, int orphanObjectsRemoved) {

    public String summary() {
        return String.format("%d chunk(s), %d entrée(s), %d objet(s) orphelins supprimés",
            chunksAffected, entriesPurged, orphanObjectsRemoved);
    }
}
