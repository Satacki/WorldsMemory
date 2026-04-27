package org.azraellykos.worldsmemory.storage;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIntArray;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Converts a WorldChunk to/from a stable byte array for CAS storage.
 * Format: palette-compressed block states + block entity NBT.
 * Does not include lighting, heightmaps or other regenerable data.
 */
public final class WMChunkSerializer {

    private WMChunkSerializer() {}

    public static byte[] serialize(WorldChunk chunk) {
        NbtCompound root = new NbtCompound();
        ChunkPos pos = chunk.getPos();
        root.putInt("cx", pos.x);
        root.putInt("cz", pos.z);

        NbtList sectionsNbt = new NbtList();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSectionY = chunk.getBottomSectionCoord();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section.isEmpty()) continue;
            sectionsNbt.add(serializeSection(section, bottomSectionY + i));
        }
        root.put("sections", sectionsNbt);

        NbtList beList = new NbtList();
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos bePos = entry.getKey();
            BlockEntity be = entry.getValue();
            NbtCompound beNbt = be.createNbt();
            // createNbt() in 1.20.1 does not write x/y/z/id — add them so
            // parseBlockEntities() can reconstruct the correct BlockPos key.
            if (!beNbt.contains("x")) beNbt.putInt("x", bePos.getX());
            if (!beNbt.contains("y")) beNbt.putInt("y", bePos.getY());
            if (!beNbt.contains("z")) beNbt.putInt("z", bePos.getZ());
            if (!beNbt.contains("id")) {
                Identifier typeId = Registries.BLOCK_ENTITY_TYPE.getId(be.getType());
                if (typeId != null) beNbt.putString("id", typeId.toString());
            }
            beList.add(beNbt);
        }
        root.put("block_entities", beList);

        return toBytes(root);
    }

    private static NbtCompound serializeSection(ChunkSection section, int sectionY) {
        NbtCompound sectionNbt = new NbtCompound();
        sectionNbt.putInt("y", sectionY);

        // BlockState objects are singletons — identity comparison avoids NbtHelper
        // and toString() for every block; we only call fromBlockState once per unique state.
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndex = new IdentityHashMap<>();
        int[] indices = new int[4096];

        int idx = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    int blockIndex = paletteIndex.computeIfAbsent(state, s -> {
                        int newIndex = palette.size();
                        palette.add(s);
                        return newIndex;
                    });
                    indices[idx++] = blockIndex;
                }
            }
        }

        NbtList paletteNbt = new NbtList();
        for (BlockState state : palette) paletteNbt.add(NbtHelper.fromBlockState(state));
        sectionNbt.put("palette", paletteNbt);
        sectionNbt.put("data", new NbtIntArray(indices));
        return sectionNbt;
    }

    static byte[] toBytes(NbtCompound nbt) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.write(nbt, new DataOutputStream(baos));
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize chunk NBT", e);
        }
    }

    public static NbtCompound fromBytes(byte[] bytes) {
        try {
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize chunk NBT", e);
        }
    }

    /**
     * Returns a human-readable diff between two snapshots.
     * Reports per-section block count deltas (added/removed block types).
     * Intended for debug use on the I/O thread only.
     */
    public static String diffSummary(byte[] oldBytes, byte[] newBytes) {
        NbtCompound oldNbt = fromBytes(oldBytes);
        NbtCompound newNbt = fromBytes(newBytes);

        Map<Integer, NbtCompound> oldSections = sectionsById(oldNbt);
        Map<Integer, NbtCompound> newSections = sectionsById(newNbt);

        TreeSet<Integer> allYs = new TreeSet<>();
        allYs.addAll(oldSections.keySet());
        allYs.addAll(newSections.keySet());

        StringBuilder sb = new StringBuilder();
        for (int y : allYs) {
            String diff = diffSection(oldSections.get(y), newSections.get(y));
            if (!diff.isEmpty()) sb.append("    y=").append(y).append(": ").append(diff).append("\n");
        }
        return sb.isEmpty() ? "    (sections identiques)" : sb.toString().stripTrailing();
    }

    private static Map<Integer, NbtCompound> sectionsById(NbtCompound root) {
        NbtList sections = root.getList("sections", 10);
        Map<Integer, NbtCompound> map = new HashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            NbtCompound sec = sections.getCompound(i);
            map.put(sec.getInt("y"), sec);
        }
        return map;
    }

    private static String diffSection(NbtCompound oldSec, NbtCompound newSec) {
        Map<String, Integer> oldCounts = blockCounts(oldSec);
        Map<String, Integer> newCounts = blockCounts(newSec);

        TreeSet<String> allBlocks = new TreeSet<>();
        allBlocks.addAll(oldCounts.keySet());
        allBlocks.addAll(newCounts.keySet());

        List<String> changes = new ArrayList<>();
        for (String block : allBlocks) {
            int delta = newCounts.getOrDefault(block, 0) - oldCounts.getOrDefault(block, 0);
            if (delta > 0) changes.add("+" + delta + " " + block);
            else if (delta < 0) changes.add(delta + " " + block);
        }
        return String.join(", ", changes);
    }

    private static Map<String, Integer> blockCounts(NbtCompound section) {
        if (section == null) return Collections.emptyMap();
        NbtList palette = section.getList("palette", 10);
        int[] indices = section.getIntArray("data");
        Map<String, Integer> counts = new TreeMap<>();
        for (int idx : indices) {
            if (idx < palette.size()) {
                String name = palette.getCompound(idx).getString("Name");
                counts.merge(name, 1, Integer::sum);
            }
        }
        return counts;
    }
}
