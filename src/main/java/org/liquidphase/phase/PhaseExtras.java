package org.liquidphase.phase;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;

/**
 * 旁路表的关卡级读写入口（所有逻辑都从这里走，保证"写入即通知"）。
 * <p>
 * 通知分两条：服务端把变化广播给追踪该格的玩家；客户端把该格所在 section 标脏。
 * 客户端逻辑通过 {@link #setClientDirtyHook(Consumer)} 由 client initializer 注入，
 * 避免服务端加载客户端渲染类。
 */
public final class PhaseExtras {

    private static Consumer<BlockPos> clientDirtyHook;
    private static Consumer<BlockPos> clientRefreshHook;
    private static java.util.function.Function<BlockGetter, BlockGetter> storageResolver;

    private PhaseExtras() {
    }

    public static void setClientDirtyHook(Consumer<BlockPos> hook) {
        clientDirtyHook = hook;
    }

    /**
     * 渲染期拿到的 {@code BlockAndTintGetter} 常常是 {@code RenderChunkRegion}（快照），
     * 不是 {@code Level}，拿不到旁路表。客户端在这里注入"从任意 Getter 解析到当前
     * ClientLevel"的兜底（生产代码在 client initializer 里注册；服务端不注册）。
     */
    public static void setStorageResolver(java.util.function.Function<BlockGetter, BlockGetter> resolver) {
        storageResolver = resolver;
    }

    /** 客户端应用完旁路数据后，除了重画，还要让方块实体/名称等刷新（26.3 需要）。 */
    public static void setClientRefreshHook(Consumer<BlockPos> hook) {
        clientRefreshHook = hook;
    }

    public static PhaseStorage storage(BlockGetter level) {
        if (level instanceof PhaseStorageHolder holder) {
            return holder.liquidphase$phaseStorage();
        }
        if (storageResolver != null) {
            BlockGetter resolved = storageResolver.apply(level);
            if (resolved instanceof PhaseStorageHolder holder) {
                return holder.liquidphase$phaseStorage();
            }
        }
        return null;
    }

    public static boolean hasStorage(BlockGetter level) {
        return storage(level) != null;
    }

    /** {@return 该格的旁路记录；无记录/无存储返回 {@link PhaseExtra#EMPTY}} */
    public static int get(BlockGetter level, BlockPos pos) {
        PhaseStorage storage = storage(level);
        return storage == null ? PhaseExtra.EMPTY : storage.get(pos);
    }

    public static int get(BlockGetter level, long packedPos) {
        PhaseStorage storage = storage(level);
        return storage == null ? PhaseExtra.EMPTY : storage.get(packedPos);
    }

    /**
     * 写入一格（{@code extra == EMPTY} 即删除），并触发同步/重画。
     *
     * @return 旧值
     */
    public static int set(LevelAccessor level, BlockPos pos, int extra) {
        PhaseStorage storage = storage(level);
        if (storage == null) {
            return PhaseExtra.EMPTY;
        }
        int previous = storage.put(pos, extra);
        if (previous == extra) {
            return previous;
        }
        fireChanged(level, pos, previous, extra);
        return previous;
    }

    /** 直接写入（存档加载 / 网络应用）：不广播，只标脏客户端。 */
    public static void apply(LevelAccessor level, BlockPos pos, int extra) {
        PhaseStorage storage = storage(level);
        if (storage == null) {
            return;
        }
        storage.put(pos, extra);
        if (clientDirtyHook != null) {
            clientDirtyHook.accept(pos);
        }
    }

    /** 数据变化后的统一出口：客户端重画 + 服务端广播。 */
    public static void fireChanged(LevelAccessor level, BlockPos pos, int previous, int current) {
        if (level.isClientSide()) {
            if (clientDirtyHook != null) {
                clientDirtyHook.accept(pos);
            }
            if (clientRefreshHook != null) {
                clientRefreshHook.accept(pos);
            }
        } else {
            org.liquidphase.sync.PhaseExtraSync.broadcast(level, pos, current);
        }
    }

    /** 方块被替换/移除时清掉旁路条目（由 {@code LevelChunkMixin} 调用）。 */
    public static void clearAt(LevelAccessor level, BlockPos pos) {
        PhaseStorage storage = storage(level);
        if (storage == null) {
            return;
        }
        if (storage.get(pos) != PhaseExtra.EMPTY) {
            set(level, pos, PhaseExtra.EMPTY);
        }
    }
}
