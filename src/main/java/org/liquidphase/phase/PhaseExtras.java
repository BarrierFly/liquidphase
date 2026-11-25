package org.liquidphase.phase;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;

/**
 * 旁路表的关卡级读写入口（所有逻辑都从这里走，保证"写入即通知"）。
 * <p>
 * 通知分两层：
 * <ul>
 *   <li><b>同步层</b>：服务端把变化广播给该维度的玩家；客户端把该格所在 section 标脏
 *       （客户端逻辑通过 {@link #setClientDirtyHook(Consumer)} 由 client initializer 注入）；</li>
 *   <li><b>世界层（0.2.7）</b>：服务端按"原版 {@code Level.setBlock} 在同样事件下会发的通知"
 *       补 ①邻居变化 ②形状更新，统一交给 {@link PhaseNotifications#entryChanged}。
 *       散落在各调用点的 {@code updateNeighborsAt} 已全部删除。</li>
 * </ul>
 * <b>不做任何去重</b>：每一次"表值真的变了"的写入都对应原版一次 {@code setBlock}，
 * 重复写同值本来就不会触发（{@code previous == extra} 直接返回，与原版
 * {@code LevelChunk.setBlockState} 对同一状态返回 null 口径一致）。
 */
public final class PhaseExtras {

    /**
     * 这次写表相对"原版一次 {@code setBlock}"还需要补哪些通知。
     * <p>
     * 取值由**调用方对当时方块写入流程的认识**决定，判断标准只有一条：
     * "如果这一格在同样状态下由原版元件方块/红石线承担，原版已经替我们发过什么？"
     */
    public enum Notify {
        /** 正常写表：补完整的一套（邻居变化 + 形状更新 + 该事件的额外通道）。 */
        WORLD,
        /**
         * 写表发生在一次真实的方块写入流程里，且那次 {@code setBlock} 已经把
         * "这一格新旧状态的邻居变化 + 形状更新"和旧方块的 {@code affectNeighborsAfterRemoval}
         * 都发过了（宿主物品落进真实元件/红石线格、下落方块落位、区块内方块被替换）。
         * <p>
         * 这种写入只需要补**元件特有、且原版那次 {@code setBlock} 不会重复**的部分
         * （{@code onPlace}/{@code affectNeighborsAfterRemoval} 的前方通知），红石相不需要补任何东西。
         */
        INSIDE_BLOCK_WRITE,
        /** 完全静默（存档加载、网络应用、以及"原版已经完整发过全过程"的吸收路径）。 */
        NONE
    }

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
     * 写入一格（{@code extra == EMPTY} 即删除），并按"原版一次 {@code setBlock}"发通知。
     *
     * @return 旧值
     */
    public static int set(LevelAccessor level, BlockPos pos, int extra) {
        return write(level, pos, extra, Notify.WORLD);
    }

    /**
     * 写入一格，并声明需要补的通知范围（见 {@link Notify}）。
     *
     * @return 旧值
     */
    public static int set(LevelAccessor level, BlockPos pos, int extra, Notify notify) {
        return write(level, pos, extra, notify);
    }

    private static int write(LevelAccessor level, BlockPos pos, int extra, Notify notify) {
        PhaseStorage storage = storage(level);
        if (storage == null) {
            return PhaseExtra.EMPTY;
        }
        int previous = storage.put(pos, extra);
        if (previous == extra) {
            return previous;
        }
        // 任何一次真实的表写入都会消费掉这一格的比较器暂存值：要么被 absorbBlock 先取走，
        // 要么说明这条路径不打算继承它，避免后续写入读到过期值。
        org.liquidphase.phase.ElementSignals.discard(pos);
        fireChanged(level, pos, previous, extra, notify);
        return previous;
    }

    /** 直接写入（存档加载 / 网络应用）：不广播、不通知邻居，只标脏客户端。 */
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

    /** 数据变化后的统一出口：客户端重画 + 服务端广播 + 服务端邻居通知。 */
    public static void fireChanged(LevelAccessor level, BlockPos pos, int previous, int current) {
        fireChanged(level, pos, previous, current, Notify.WORLD);
    }

    public static void fireChanged(LevelAccessor level, BlockPos pos, int previous, int current, Notify notify) {
        if (level.isClientSide()) {
            if (clientDirtyHook != null) {
                clientDirtyHook.accept(pos);
            }
            if (clientRefreshHook != null) {
                clientRefreshHook.accept(pos);
            }
            return;
        }
        org.liquidphase.sync.PhaseExtraSync.broadcast(level, pos, current);
        if (notify != Notify.NONE && level instanceof ServerLevel serverLevel) {
            PhaseNotifications.entryChanged(serverLevel, pos, previous, current, notify == Notify.INSIDE_BLOCK_WRITE);
        }
    }

    /**
     * 方块被替换/移除时清掉旁路条目（由 {@code LevelChunkExtraMixin} 调用）。
     * <p>
     * 清理发生在 {@code LevelChunk.setBlockState} HEAD：随后那次 {@code setBlock} 自己会发
     * {@code updateNeighborsAt(pos, 旧方块)} 与形状更新，所以这里只补元件特有的前方通知。
     */
    public static void clearAt(LevelAccessor level, BlockPos pos) {
        PhaseStorage storage = storage(level);
        if (storage == null) {
            return;
        }
        if (storage.get(pos) != PhaseExtra.EMPTY) {
            set(level, pos, PhaseExtra.EMPTY, Notify.INSIDE_BLOCK_WRITE);
        }
    }
}
