package org.liquidphase.sync;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.phase.PhaseStorage;

/**
 * 客户端半边：接收旁路表数据 + 标脏 section + 请求区块数据。
 * <p>
 * 只在 client initializer 里加载（{@code LevelRenderer}/{@code LevelExtractor} 是客户端类）。
 */
public final class PhaseExtraClientSync {

    private PhaseExtraClientSync() {
    }

    public static void register() {
        //? if 1.19.4 || 1.20.1 {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PhaseIds.id(PhaseExtraSync.CHUNK_PATH),
            (client, handler, buf, sender) -> {
                int chunkX = buf.readVarInt();
                int chunkZ = buf.readVarInt();
                int size = buf.readVarInt();
                long[] positions = new long[size];
                int[] values = new int[size];
                for (int i = 0; i < size; i++) {
                    positions[i] = buf.readLong();
                }
                for (int i = 0; i < size; i++) {
                    values[i] = buf.readVarInt();
                }
                client.execute(() -> applyChunk(chunkX, chunkZ, positions, values));
            });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PhaseIds.id(PhaseExtraSync.UPDATE_PATH),
            (client, handler, buf, sender) -> {
                long pos = buf.readLong();
                int extra = buf.readVarInt();
                client.execute(() -> applyUpdate(pos, extra));
            });
        ClientChunkEvents.CHUNK_LOAD.register((client, chunk) -> request(chunk.getPos().x, chunk.getPos().z));
         *///?} else {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PhaseExtraSync.ChunkPayload.TYPE, (payload, context) ->
                context.client().execute(() -> applyChunk(payload.chunkX(), payload.chunkZ(),
                    payload.positions(), payload.values())));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PhaseExtraSync.UpdatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> applyUpdate(payload.pos(), payload.extra())));
        ClientChunkEvents.CHUNK_LOAD.register((client, chunk) -> request(chunk.getPos().x, chunk.getPos().z));
        //?}

        PhaseExtras.setClientDirtyHook(PhaseExtraClientSync::markDirty);
        PhaseExtras.setClientRefreshHook(PhaseExtraClientSync::markDirty);
        // 渲染期的 BlockAndTintGetter 是区块快照，不是 Level：统一解析到当前 ClientLevel。
        PhaseExtras.setStorageResolver(getter -> Minecraft.getInstance().level);
        // 客户端 Level 会在进入世界/切维度时被整体替换：旁路表挂在 Level 上，旧表的条目随旧
        // Level 一起消失。检测到 Level 变化就按视距重新请求一轮（否则在"世界加载窗口"里
        // 收到、但发给旧 Level 的条目会永久丢失）。
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != lastLevel) {
                lastLevel = client.level;
                if (client.level != null) {
                    requestLoadedNearPlayer();
                }
            }
        });
    }

    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;

    /** 握手到达时已经加载的区块不会再有 CHUNK_LOAD 事件：按视距补一轮请求。 */
    public static void requestLoadedNearPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        int radius = minecraft.options.renderDistance().get() + 1;
        int centerX = minecraft.player.chunkPosition().x;
        int centerZ = minecraft.player.chunkPosition().z;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                request(centerX + dx, centerZ + dz);
            }
        }
    }

    private static void request(int chunkX, int chunkZ) {
        if (!PhaseSyncState.serverSupportsPhases()) {
            return;
        }        //? if 1.19.4 || 1.20.1 {
        /*net.minecraft.network.FriendlyByteBuf buf =
            new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(chunkX);
        buf.writeVarInt(chunkZ);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            PhaseIds.id(PhaseExtraSync.REQUEST_PATH), buf);
         *///?} else {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            new PhaseExtraSync.RequestPayload(chunkX, chunkZ));
        //?}
    }

    private static void applyChunk(int chunkX, int chunkZ, long[] positions, int[] values) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        PhaseStorage storage = PhaseExtras.storage(minecraft.level);
        if (storage == null) {
            return;
        }
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        // **合并**而不是整块替换：区块请求的回包可能比之后的单格更新更晚到达
        // （请求在区块加载时发出，场景/其它玩家在回包到达前又写了表），整块替换会把
        // 新数据抹掉。删除只会通过额外的 extra_update(0) 广播，不会依赖这里的"清空"。
        Long2IntOpenHashMap cells = new Long2IntOpenHashMap(positions.length);
        var existing = storage.chunk(chunkKey);
        if (existing != null) {
            cells.putAll(existing);
        }
        for (int i = 0; i < positions.length; i++) {
            cells.put(positions[i], values[i]);
        }
        storage.replaceChunk(chunkKey, cells);
        for (long position : positions) {
            markDirty(BlockPos.of(position));
        }
    }

    private static void applyUpdate(long packedPos, int extra) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        PhaseStorage storage = PhaseExtras.storage(minecraft.level);
        if (storage == null) {
            return;
        }
        storage.put(pos, extra);
        markDirty(pos);
    }

    /** 让包含该格的 section 重新构建（相/元件的几何从 FRAPI 模型按坐标发射）。 */
    public static void markDirty(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        net.minecraft.world.level.block.state.BlockState state = minecraft.level.getBlockState(pos);
        //? if 26.3 {
        /*minecraft.levelExtractor.setBlockDirty(pos, state, state);
         *///?} else {
        minecraft.levelRenderer.setBlockDirty(pos, state, state);
        //?}
    }
}
