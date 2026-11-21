package org.liquidphase.sync;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.phase.PhaseStorage;

/**
 * 旁路表同步（方案 C）：
 * <ul>
 *   <li>区块级初始同步：客户端加载区块时发 {@code extra_request}，服务端回
 *       {@code extra_chunk}（含空表——用来清掉客户端残留）。</li>
 *   <li>单格增量：服务端每次写表把 {@code extra_update} 发给追踪该格的玩家。</li>
 * </ul>
 * 1.19.4/1.20.1 用裸 {@code FriendlyByteBuf} 通道；1.21.1+ 用 {@code CustomPacketPayload}。
 */
public final class PhaseExtraSync {

    public static final String CHUNK_PATH = "extra_chunk";
    public static final String UPDATE_PATH = "extra_update";
    public static final String REQUEST_PATH = "extra_request";

    private PhaseExtraSync() {
    }

    // ------------------------------------------------------------------
    // 服务端
    // ------------------------------------------------------------------

    public static void registerServer() {
        //? if 1.19.4 || 1.20.1 {
        /*net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
            PhaseIds.id(REQUEST_PATH), (server, player, handler, buf, sender) -> {
                int chunkX = buf.readVarInt();
                int chunkZ = buf.readVarInt();
                server.execute(() -> sendChunk(player, levelOf(player), chunkX, chunkZ));
            });
         *///?} else {
        //? if 26.3 {
        /*net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay()
            .register(ChunkPayload.TYPE, ChunkPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay()
            .register(UpdatePayload.TYPE, UpdatePayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay()
            .register(RequestPayload.TYPE, RequestPayload.CODEC);
         *///?} else {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
            .register(ChunkPayload.TYPE, ChunkPayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
            .register(UpdatePayload.TYPE, UpdatePayload.CODEC);
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S()
            .register(RequestPayload.TYPE, RequestPayload.CODEC);
        //?}
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
            RequestPayload.TYPE, (payload, context) -> context.server().execute(
                () -> sendChunk(context.player(), levelOf(context.player()),
                    payload.chunkX(), payload.chunkZ())));
        //?}
    }

    /** 单格变化：发给该维度的全部玩家。 */
    public static void broadcast(LevelAccessor level, BlockPos pos, int extra) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // 不用 PlayerLookup.tracking：它只覆盖"玩家已追踪该区块"的情况。放置元件/写红石
        // 可能发生在玩家刚开始追踪、甚至客户端已加载该区块但服务端追踪表尚未更新的窗口里，
        // 那些写入会永久丢失（区块请求发生在加载时，之后不会再问）。单格包很小，
        // 直接发给该维度全部玩家，客户端只写自己有的 level 存储，未加载的格无副作用。
        for (ServerPlayer player : serverLevel.players()) {
            //? if 1.19.4 || 1.20.1 {
            /*if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, PhaseIds.id(UPDATE_PATH))) {
                continue;
            }
            net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            buf.writeLong(pos.asLong());
            buf.writeVarInt(extra);
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, PhaseIds.id(UPDATE_PATH), buf);
             *///?} else {
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, UpdatePayload.TYPE)) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                    player, new UpdatePayload(pos.asLong(), extra));
            }
            //?}
        }
    }

    /** {@return 玩家所在的 ServerLevel}（{@code level()} 是 1.20.5+ 的 API，旧版是 {@code getLevel()}） */
    private static ServerLevel levelOf(ServerPlayer player) {
        //? if 1.19.4 {
        /*return player.getLevel();
         *///?} elif 1.20.1 || 1.21.1 {
        /*return player.serverLevel();
         *///?} else {
        return (ServerLevel) player.level();
        //?}
    }

    private static void sendChunk(ServerPlayer player, ServerLevel level, int chunkX, int chunkZ) {
        PhaseStorage storage = PhaseExtras.storage(level);
        if (storage == null || player == null) {
            return;
        }
        long chunkKey = PhaseStorage.chunkKey(chunkX << 4, chunkZ << 4);
        Long2IntMap cells = storage.chunk(chunkKey);
        int size = cells == null ? 0 : cells.size();
        long[] positions = new long[size];
        int[] values = new int[size];
        if (cells != null) {
            int index = 0;
            for (Long2IntMap.Entry entry : cells.long2IntEntrySet()) {
                positions[index] = entry.getLongKey();
                values[index] = entry.getIntValue();
                index++;
            }
        }
        //? if 1.19.4 || 1.20.1 {
        /*if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, PhaseIds.id(CHUNK_PATH))) {
            return;
        }
        net.minecraft.network.FriendlyByteBuf buf =
            new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(chunkX);
        buf.writeVarInt(chunkZ);
        buf.writeVarInt(positions.length);
        for (long position : positions) {
            buf.writeLong(position);
        }
        for (int value : values) {
            buf.writeVarInt(value);
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, PhaseIds.id(CHUNK_PATH), buf);
         *///?} else {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, ChunkPayload.TYPE)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
                player, new ChunkPayload(chunkX, chunkZ, positions, values));
        }
        //?}
    }

    /** 存档加载后给在线玩家全量重发（可选，当前只在区块请求时按需发）。 */
    public static void sendAll(ServerLevel level, ServerPlayer player) {
        for (Map.Entry<Long, Long2IntMap> entry : PhaseExtras.storage(level).snapshot().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                long key = entry.getKey();
                sendChunk(player, level, (int) (key & 0xFFFFFFFFL), (int) (key >> 32));
            }
        }
    }

    // ------------------------------------------------------------------
    // 1.21.1+ payload 定义
    // ------------------------------------------------------------------

    //? if !(1.19.4 || 1.20.1) {

    public record ChunkPayload(int chunkX, int chunkZ, long[] positions, int[] values)
        implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<ChunkPayload> TYPE =
            new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(PhaseIds.id(CHUNK_PATH));

        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, ChunkPayload> CODEC =
            net.minecraft.network.codec.StreamCodec.of((buf, payload) -> {
                buf.writeVarInt(payload.chunkX());
                buf.writeVarInt(payload.chunkZ());
                buf.writeVarInt(payload.positions().length);
                for (long position : payload.positions()) {
                    buf.writeLong(position);
                }
                for (int value : payload.values()) {
                    buf.writeVarInt(value);
                }
            }, buf -> {
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
                return new ChunkPayload(chunkX, chunkZ, positions, values);
            });

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record UpdatePayload(long pos, int extra)
        implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<UpdatePayload> TYPE =
            new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(PhaseIds.id(UPDATE_PATH));

        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, UpdatePayload> CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                (buf, payload) -> {
                    buf.writeLong(payload.pos());
                    buf.writeVarInt(payload.extra());
                },
                buf -> new UpdatePayload(buf.readLong(), buf.readVarInt()));

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RequestPayload(int chunkX, int chunkZ)
        implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {

        public static final net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<RequestPayload> TYPE =
            new net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type<>(PhaseIds.id(REQUEST_PATH));

        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, RequestPayload> CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                (buf, payload) -> {
                    buf.writeVarInt(payload.chunkX());
                    buf.writeVarInt(payload.chunkZ());
                },
                buf -> new RequestPayload(buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    //?}
}
