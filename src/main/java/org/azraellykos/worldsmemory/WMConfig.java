package org.azraellykos.worldsmemory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Singleton config loaded from config/worldsmemory.json at startup.
 * Created with default values if the file is absent.
 */
public final class WMConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile WMConfig INSTANCE;

    // --- Temporal commit (CommitScheduler) ---
    public int commitIntervalTicks = 10 * 60 * 20;   // 10 minutes
    public int maxCommitsPerTick   = 4;
    public long scanCooldownMs     = 200;

    // --- Threshold trigger (DirtyChunkTracker) ---
    public int dirtyThreshold = 50;

    // --- TNT chain tracking (TntChainTracker) ---
    public int chainMergeRadius = 16;
    public int postBufferTicks  = 5;
    public int explosionRadius  = 6;

    // --- Throttling ---
    public int  maxExplosionsParSeconde = 10;
    public int  maxBlocsParSnapshot     = 10_000;
    public int  maxSnapshotsEnMemoire   = 50;
    public long fenetreGroupageMs       = 500;

    // --- Purge ---
    public boolean purgeEnabled = true;

    // --- Motion Capture (soft dependency — redémarrage requis pour changer de mode) ---
    @SerializedName("motion_capture")
    public MotionCaptureConfig motionCapture = new MotionCaptureConfig();

    // -------------------------------------------------------------------------

    public enum RecordingMode { BUFFER, CONTINUOUS }

    public static class MotionCaptureConfig {
        public boolean enabled = true;
        public RecordingMode mode = RecordingMode.BUFFER;

        @SerializedName("buffer_before_minutes")
        public int bufferBeforeMinutes = 5;

        @SerializedName("buffer_after_minutes")
        public int bufferAfterMinutes = 2;

        @SerializedName("clip_triggers")
        public List<String> clipTriggers = new ArrayList<>(List.of(
            "EXPLOSION_TNT", "EXPLOSION_CREEPER", "EXPLOSION_CRYSTAL",
            "EXPLOSION_WITHER", "PLAYER_DEATH", "PLAYER_KILL"
        ));

        @SerializedName("adaptive_tick_rate")
        public boolean adaptiveTickRate = true;

        @SerializedName("compress_on_save")
        public boolean compressOnSave = true;

        void validate() {
            if (bufferBeforeMinutes < 1) {
                Worldsmemory.LOGGER.warn("[WM] [CONFIG] motion_capture.buffer_before_minutes={} invalide — forcé à 1", bufferBeforeMinutes);
                bufferBeforeMinutes = 1;
            }
            if (bufferBeforeMinutes > 60) {
                Worldsmemory.LOGGER.warn("[WM] [CONFIG] motion_capture.buffer_before_minutes={} dépasse 60 — forcé à 60 (protection RAM)", bufferBeforeMinutes);
                bufferBeforeMinutes = 60;
            }
            if (bufferAfterMinutes > bufferBeforeMinutes) {
                Worldsmemory.LOGGER.warn("[WM] [CONFIG] motion_capture.buffer_after_minutes ({}) > buffer_before_minutes ({}) — inhabituel mais permis",
                    bufferAfterMinutes, bufferBeforeMinutes);
            }
            if (clipTriggers == null) clipTriggers = new ArrayList<>();
        }
    }

    private WMConfig() {}

    public static WMConfig get() {
        if (INSTANCE == null) {
            synchronized (WMConfig.class) {
                if (INSTANCE == null) INSTANCE = load();
            }
        }
        return INSTANCE;
    }

    /** Reloads the config from disk (useful after a manual edit). */
    public static void reload() {
        synchronized (WMConfig.class) {
            INSTANCE = load();
        }
    }

    private static WMConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                WMConfig cfg = GSON.fromJson(r, WMConfig.class);
                if (cfg != null) {
                    if (cfg.motionCapture == null) cfg.motionCapture = new MotionCaptureConfig();
                    cfg.motionCapture.validate();
                    Worldsmemory.LOGGER.info("[WM] Config loaded from {}", path);
                    return cfg;
                }
            } catch (IOException e) {
                Worldsmemory.LOGGER.warn("[WM] Error reading config — using defaults", e);
            }
        }
        WMConfig defaults = new WMConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(this, w);
            }
            Worldsmemory.LOGGER.info("[WM] Config written: {}", path);
        } catch (IOException e) {
            Worldsmemory.LOGGER.warn("[WM] Failed to write config", e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("worldsmemory.json");
    }
}
