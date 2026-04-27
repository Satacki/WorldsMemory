package org.azraellykos.worldsmemory.commit;

/**
 * Thread-local cause hint propagated from entity/fluid mixins to ServerWorldMixin.
 *
 * Supports nested push/pop: the outermost push sets the active cause; inner
 * push/pop pairs are counted but do not overwrite or clear it prematurely.
 * This handles the case where tickMovement (outer) calls mobTick (inner) —
 * the inner pop does not erase the context set by the outer frame.
 */
public final class CauseContext {

    private static final ThreadLocal<CauseModification> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private CauseContext() {}

    public static void push(CauseModification cause) {
        int d = DEPTH.get();
        if (d == 0) ACTIVE.set(cause);
        DEPTH.set(d + 1);
    }

    public static void pop() {
        int d = DEPTH.get() - 1;
        DEPTH.set(Math.max(0, d));
        if (d <= 0) ACTIVE.remove();
    }

    public static CauseModification get() {
        CauseModification c = ACTIVE.get();
        return c != null ? c : CauseModification.INCONNU;
    }
}
