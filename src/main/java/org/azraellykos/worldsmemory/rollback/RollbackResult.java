package org.azraellykos.worldsmemory.rollback;

public record RollbackResult(
    boolean success,
    boolean degraded,
    boolean cancelled,
    int chunksRestored,
    int blocksRestored,
    int entitiesRestored,
    String reason
) {
    public static RollbackResult success(int chunks, int blocks, int entities) {
        return new RollbackResult(true, false, false, chunks, blocks, entities, null);
    }

    public static RollbackResult degraded(int chunks, int blocks, int entities, String reason) {
        return new RollbackResult(true, true, false, chunks, blocks, entities, reason);
    }

    public static RollbackResult cancelled(String reason) {
        return new RollbackResult(false, false, true, 0, 0, 0, reason);
    }

    public static RollbackResult noData() {
        return new RollbackResult(false, false, false, 0, 0, 0, "Aucune donnée disponible pour ce rollback");
    }

    public String summary() {
        if (cancelled) return "ANNULÉ — " + reason;
        if (!success && !degraded) return "AUCUNE DONNÉE — " + reason;
        String base = chunksRestored + " chunk(s), " + blocksRestored + " bloc(s), " + entitiesRestored + " entité(s)";
        if (degraded) return "PARTIEL — " + base + " (" + reason + ")";
        return "OK — " + base;
    }
}
