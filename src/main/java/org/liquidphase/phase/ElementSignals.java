package org.liquidphase.phase;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * 吸收真实比较器方块时的**方块实体输出值暂存**（0.2.7，根因 C 的实现细节）。
 * <p>
 * 问题：吸收的真实路径是"宿主物品放进比较器所在格"→ {@code Level.setBlock} 把比较器换成宿主
 * （{@code LevelChunk.setBlockState} 在方法中段就删掉了比较器的方块实体）→ 之后才由
 * {@code PhaseAbsorption.absorbCell} 写旁路条目。等到写条目时再问 {@code getBlockEntity(pos)}
 * 已经拿不到 {@code OutputSignal}，条目只能落 0——玩家看到的是"吸收一个正在输出的比较器，
 * 输出瞬间变 0"。
 * <p>
 * 做法：在 {@code LevelChunk.setBlockState} 的 HEAD（方块实体还没被删）把比较器的输出值记下来，
 * 由 {@link org.liquidphase.element.ElementBlocks#absorbBlock} 在写条目时 {@link #take} 取走。
 * 取走即删除；此外任何一次针对该格的表写入（{@link PhaseExtras#set}）都会清掉未消费的记录，
 * 所以不会串到别的相上。条目数上限用于兜底（正常情况最多几格）。
 * <p>
 * 线程模型：写与读都发生在服务端主线程的同一次调用栈里，用 {@link ThreadLocal} 足够且无锁。
 */
public final class ElementSignals {

    /** 兜底上限：超过就整体清空（只在"大量比较器被替换但都没被吸收"的极端世界里才会碰到）。 */
    private static final int MAX_PENDING = 4096;

    private static final ThreadLocal<Long2IntMap> PENDING =
        ThreadLocal.withInitial(Long2IntOpenHashMap::new);

    private ElementSignals() {
    }

    /** 记下某格比较器的方块实体输出值（必须在方块实体被删除之前调用）。 */
    public static void capture(BlockPos pos, int outputSignal) {
        Long2IntMap map = PENDING.get();
        if (map.size() >= MAX_PENDING) {
            map.clear();
        }
        map.put(pos.asLong(), outputSignal);
    }

    /** {@return 取走并删除暂存值；没有记录返回 {@link Integer#MIN_VALUE}} */
    public static int take(BlockPos pos) {
        Long2IntMap map = PENDING.get();
        long key = pos.asLong();
        if (!map.containsKey(key)) {
            return Integer.MIN_VALUE;
        }
        return map.remove(key);
    }

    /** 丢弃某格的暂存值。 */
    public static void discard(BlockPos pos) {
        PENDING.get().remove(pos.asLong());
    }
}
