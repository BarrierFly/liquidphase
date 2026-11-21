package org.liquidphase.phase;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.liquidphase.LiquidPhase;

/**
 * 旁路表持久化（方案 C）：每个维度一个 NBT 文件
 * {@code world/data/liquidphase_<dimension>.dat}。
 * <p>
 * 不走原版区块存档格式：{@code ChunkSerializer}（≤1.21.1）与
 * {@code SerializableChunkData}（1.21.10+）是这条链路上版本差异最大的两个点，
 * 而旁路表本身是按区块分组的稀疏表，独立文件既省掉两条存档 mixin，也不影响原版数据。
 * 代价：崩溃时最多丢一个自动保存周期（30 s）内的改动。
 */
public final class PhasePersistence {

    private static final String FILE_PREFIX = "liquidphase_";
    private static final int AUTOSAVE_TICKS = 600;

    private static int tickCounter;

    private PhasePersistence() {
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(PhasePersistence::loadAll);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(PhasePersistence::saveAll);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> saveAll(server));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tickCounter >= AUTOSAVE_TICKS) {
                tickCounter = 0;
                saveAll(server);
            }
        });
    }

    private static Path fileFor(ServerLevel level) {
        //? if 1.21.11 || 26.3 {
        String dimension = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        //?} else {
        /*String dimension = level.dimension().location().toString().replace(':', '_').replace('/', '_');
         *///?}
        return level.getServer().getWorldPath(LevelResource.ROOT)
            .resolve("data").resolve(FILE_PREFIX + dimension + ".dat");
    }

    public static void loadAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            load(level);
        }
    }

    public static void load(ServerLevel level) {
        Path path = fileFor(level);
        PhaseStorage storage = PhaseExtras.storage(level);
        if (storage == null) {
            return;
        }
        storage.clear();
        storage.markClean();
        if (!Files.exists(path)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            CompoundTag root = NbtIo.read(in);
            if (root == null) {
                return;
            }
            //? if 1.21.10 || 1.21.11 || 26.3 {
            ListTag chunks = root.getList("chunks").orElseGet(ListTag::new);
            //?} else {
            /*ListTag chunks = root.getList("chunks", Tag.TAG_COMPOUND);
             *///?}
            int loaded = 0;
            for (int i = 0; i < chunks.size(); i++) {
                //? if 1.21.10 || 1.21.11 || 26.3 {
                CompoundTag chunkTag = chunks.getCompound(i).orElse(null);
                //?} else {
                /*CompoundTag chunkTag = chunks.getCompound(i);
                 *///?}
                if (chunkTag == null) {
                    continue;
                }
                //? if 1.21.10 || 1.21.11 || 26.3 {
                long chunkKey = chunkTag.getLongOr("key", 0L);
                //?} else {
                /*long chunkKey = chunkTag.getLong("key");
                 *///?}
                //? if 1.21.10 || 1.21.11 || 26.3 {
                long[] positions = chunkTag.getLongArray("positions").orElse(new long[0]);
                int[] values = chunkTag.getIntArray("values").orElse(new int[0]);
                //?} else {
                /*long[] positions = chunkTag.getLongArray("positions");
                int[] values = chunkTag.getIntArray("values");
                 *///?}
                int size = Math.min(positions.length, values.length);
                for (int j = 0; j < size; j++) {
                    storage.putRaw(positions[j], chunkKey, values[j]);
                    loaded++;
                }
            }
            storage.markClean();
            LiquidPhase.LOGGER.info("[Liquid Phase] loaded {} side-table entries for {}", loaded,
                //? if 1.21.11 || 26.3 {
                level.dimension().identifier()
                //?} else {
                /*level.dimension().location()
                 *///?}
            );
        } catch (IOException | RuntimeException e) {
            LiquidPhase.LOGGER.error("[Liquid Phase] failed to read phase side table {}", path, e);
        }
    }

    public static void saveAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            save(level);
        }
    }

    public static void save(ServerLevel level) {
        PhaseStorage storage = PhaseExtras.storage(level);
        if (storage == null || !storage.isDirty()) {
            return;
        }
        Path path = fileFor(level);
        try {
            Files.createDirectories(path.getParent());
            CompoundTag root = new CompoundTag();
            ListTag chunks = new ListTag();
            for (var entry : storage.snapshot().entrySet()) {
                var cells = entry.getValue();
                if (cells.isEmpty()) {
                    continue;
                }
                CompoundTag chunkTag = new CompoundTag();
                chunkTag.putLong("key", entry.getKey());
                long[] positions = new long[cells.size()];
                int[] values = new int[cells.size()];
                int index = 0;
                for (var cell : cells.long2IntEntrySet()) {
                    positions[index] = cell.getLongKey();
                    values[index] = cell.getIntValue();
                    index++;
                }
                chunkTag.putLongArray("positions", positions);
                chunkTag.putIntArray("values", values);
                chunks.add(chunkTag);
            }
            root.put("chunks", chunks);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                NbtIo.write(root, out);
            }
            storage.markClean();
        } catch (IOException | RuntimeException e) {
            LiquidPhase.LOGGER.error("[Liquid Phase] failed to write phase side table {}", path, e);
        }
    }
}
