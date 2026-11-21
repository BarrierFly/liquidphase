package org.liquidphase.phase;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * 旁路表（方案 C）：每 {@link net.minecraft.world.level.Level} 一张
 * {@code chunkPos -> (BlockPos.asLong -> PhaseExtra int)} 的稀疏表。
 * <p>
 * <b>线程模型</b>：区块内层表一经发布就**不再原地修改**（写入走"复制 + 原子替换"），
 * 所以渲染线程可以无锁读取 {@link #get(long)}；写入只发生在主线程（服务端/客户端各自）。
 * 这是把"按坐标查表"接进区块网格构建的必要条件（Fluidlogged 用快照复制，这里用不可变内表）。
 * <p>
 * <b>持久化</b>：服务端由 {@code org.liquidphase.phase.PhasePersistence} 写进
 * {@code world/data/liquidphase_<dimension>.dat}；客户端只由网络包填充。
 */
public final class PhaseStorage {

    private final Map<Long, Long2IntMap> chunks = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public PhaseStorage() {
    }

    public static long chunkKey(int x, int z) {
        return ChunkPos.asLong(x >> 4, z >> 4);
    }

    public static long chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** {@return 该格的旁路记录；没有记录返回 {@link PhaseExtra#EMPTY}} */
    public int get(BlockPos pos) {
        return get(pos.asLong(), chunkKey(pos));
    }

    public int get(long packedPos, long chunkKey) {
        Long2IntMap cells = this.chunks.get(chunkKey);
        return cells == null ? PhaseExtra.EMPTY : cells.get(packedPos);
    }

    public int get(long packedPos) {
        int x = BlockPos.getX(packedPos);
        int z = BlockPos.getZ(packedPos);
        return get(packedPos, ChunkPos.asLong(x >> 4, z >> 4));
    }

    /** 写入/删除一格；{@code extra == 0} 等价删除。{@return 旧值} */
    public int put(BlockPos pos, int extra) {
        return putRaw(pos.asLong(), chunkKey(pos), extra);
    }

    public int remove(BlockPos pos) {
        return put(pos, PhaseExtra.EMPTY);
    }

    /**
     * 原始写入（存档加载 / 网络包使用）：不校验方块状态，调用方负责保证合法。
     */
    public int putRaw(long packedPos, long chunkKey, int extra) {
        int[] previous = {PhaseExtra.EMPTY};
        this.chunks.compute(chunkKey, (key, old) -> {
            Long2IntMap copy = old == null ? new Long2IntOpenHashMap() : new Long2IntOpenHashMap(old);
            if (extra == PhaseExtra.EMPTY) {
                previous[0] = copy.remove(packedPos);
            } else {
                previous[0] = copy.put(packedPos, extra);
            }
            return copy.isEmpty() ? null : copy;
        });
        this.dirty = true;
        return previous[0];
    }

    /** 客户端收到区块数据时整块替换，避免残留旧条目。 */
    public void replaceChunk(long chunkKey, Long2IntMap cells) {
        if (cells == null || cells.isEmpty()) {
            this.chunks.remove(chunkKey);
        } else {
            this.chunks.put(chunkKey, new Long2IntOpenHashMap(cells));
        }
    }

    /** {@return 该区块的只读表（可能为 null）} */
    public Long2IntMap chunk(long chunkKey) {
        return this.chunks.get(chunkKey);
    }

    /** {@return 全部区块的不可变快照（内层表不可变，可安全跨线程遍历）} */
    public Map<Long, Long2IntMap> snapshot() {
        return new HashMap<>(this.chunks);
    }

    public int cellCount() {
        int total = 0;
        for (Long2IntMap cells : this.chunks.values()) {
            total += cells.size();
        }
        return total;
    }

    public int chunkCount() {
        return this.chunks.size();
    }

    public void clear() {
        this.chunks.clear();
        this.dirty = true;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void markClean() {
        this.dirty = false;
    }

    /** 测试/诊断用：列出全部条目（{@code posLong -> extra}）。 */
    public Map<Long, Integer> entries() {
        Map<Long, Integer> result = new HashMap<>();
        for (Long2IntMap cells : this.chunks.values()) {
            for (Long2IntMap.Entry entry : cells.long2IntEntrySet()) {
                result.put(entry.getLongKey(), entry.getIntValue());
            }
        }
        return result;
    }
}
