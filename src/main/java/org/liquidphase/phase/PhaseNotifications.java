package org.liquidphase.phase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.element.ElementBlocks;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 旁路表写入后的**服务端通知出口**（0.2.7）。
 * <p>
 * 原版一次状态变化通过 {@code Level.setBlock} 做三件事：
 * <ol>
 *   <li>{@code updateNeighborsAt(pos, 旧方块)} → 邻居的 {@code handleNeighborChanged}；</li>
 *   <li>{@code newState.updateNeighbourShapes(...)} → 邻居的 {@code updateShape}
 *       （红石线连接态、中继器 {@code LOCKED}、侦测器唯一的触发点都在这里）；</li>
 *   <li>{@code updateNeighbourForOutputSignal(pos, 方块)} → 相邻比较器。原版只在
 *       {@code blockState.hasAnalogOutputSignal()} 时发；元件/红石相都没有模拟量输出，
 *       所以这里不发。**反方向**（容器内容变化 → 相邻比较器相）由
 *       {@code LevelUpdateNeighbourForOutputSignalMixin} 桥接，与这里无关。</li>
 * </ol>
 * 旁路表方案里"写表"等价于原版的一次 {@code setBlock}，但三条通道一条都不会自己发生。
 * 这里按**原版在同样事件下做的那一套**复刻，逐事件对齐调用种类与顺序：
 * <table border="1">
 *   <tr><th>事件</th><th>原版路径</th><th>本类发出的通道</th></tr>
 *   <tr><td>元件装配</td><td>{@code BlockItem.place → setBlock(pos, 元件, 11)}</td>
 *       <td>{@code onPlace} 前方通知 → {@code updateNeighborsAt(pos, 旧方块)} → 形状</td></tr>
 *   <tr><td>元件拆除</td><td>{@code removeBlock → setBlock(pos, 空气, 3)}</td>
 *       <td>{@code affectNeighborsAfterRemoval} 前方通知 → {@code updateNeighborsAt(pos, 元件方块)} → 形状</td></tr>
 *   <tr><td>中继器 POWERED / LOCKED</td><td>{@code setBlock(..., 2)}</td><td>前方通知 → 形状</td></tr>
 *   <tr><td>中继器 DELAY</td><td>{@code setBlock(..., 3)}</td>
 *       <td>前方通知 → {@code updateNeighborsAt(pos, 元件方块)} → 形状</td></tr>
 *   <tr><td>比较器 POWERED / MODE</td><td>{@code setBlock(..., 2)} + {@code refreshOutputState}</td>
 *       <td>前方通知 → 形状 → 前方通知</td></tr>
 *   <tr><td>比较器 power</td><td>{@code refreshOutputState} 只改方块实体</td><td>前方通知</td></tr>
 *   <tr><td>侦测器 POWERED</td><td>{@code setBlock(..., 2)} + {@code updateNeighborsInFront}</td>
 *       <td>形状 → 前方通知</td></tr>
 *   <tr><td>红石相装配</td><td>红石线 {@code setBlock(..., 11)} + {@code onPlace}</td>
 *       <td>邻居 + 形状 + 上下邻居 + 邻线拐角</td></tr>
 *   <tr><td>红石相功率</td><td>{@code DefaultRedstoneWireEvaluator.updatePowerStrength}</td>
 *       <td>形状 + 本格与 6 邻居</td></tr>
 *   <tr><td>红石相移除</td><td>红石线 {@code affectNeighborsAfterRemoval}</td>
 *       <td>邻居 + 形状 + 6 邻居 + 邻线拐角</td></tr>
 * </table>
 * <b>不做任何去重</b>：每一次真实的表值变化都对应一次原版 {@code setBlock}；同值写入在
 * {@link PhaseExtras#set} 就被挡掉了（原版 {@code LevelChunk.setBlockState} 对同一状态同样返回 null）。
 *
 * @param insideBlockWrite 本次写表发生在一个**真实的方块写入流程内部**（那一格刚被 / 即将被
 *                         {@code Level.setBlock} 换掉）：那次 {@code setBlock} 自己已经发过
 *                         {@code updateNeighborsAt(pos, 旧方块)} 与形状更新，所以本类跳过这两条
 *                         基础通道，只补该事件下**额外**的那部分（元件的 {@code onPlace}/
 *                         {@code affectNeighborsAfterRemoval} 前方通知、红石相的邻线拐角等），
 *                         使总数与"原版一次 {@code setBlock}"一致。
 */
public final class PhaseNotifications {

    /**
     * 原版 {@code Level.setBlock} 形状更新段的第三参：{@code flags & -34}。
     * 元件装配 flags=11、拆除 flags=3、状态变化 flags=2、调档 flags=3，
     * 四者 {@code & -34} 之后都是 {@code UPDATE_CLIENTS}。
     */
    private static final int SHAPE_FLAGS = Block.UPDATE_CLIENTS;

    private PhaseNotifications() {
    }

    public static void entryChanged(ServerLevel level, BlockPos pos, int previous, int current, boolean insideBlockWrite) {
        boolean previousElement = PhaseExtra.isElement(previous);
        boolean currentElement = PhaseExtra.isElement(current);
        boolean previousRedstone = PhaseExtra.isRedstone(previous);
        boolean currentRedstone = PhaseExtra.isRedstone(current);

        // 一次写入可能同时"拆掉旧相 + 装上另一类相"（元件 ↔ 红石 ↔ 无）。
        // 每一种旧相都按原版"移除"口径发通道，每一种新相都按原版"放置"口径发通道；
        // 这正是原版"先 removeBlock 再 setBlock"两次写入的通道总量。
        if (previousElement && !currentElement) {
            elementRemoved(level, pos, previous, insideBlockWrite);
        }
        if (previousRedstone && !currentRedstone) {
            redstoneRemoved(level, pos, insideBlockWrite);
        }
        if (currentElement && !previousElement) {
            elementPlaced(level, pos, current, insideBlockWrite);
        }
        if (currentRedstone && !previousRedstone) {
            redstonePlaced(level, pos, insideBlockWrite);
        }
        if (previousElement && currentElement) {
            elementStateChanged(level, pos, previous, current, insideBlockWrite);
        }
        if (previousRedstone && currentRedstone) {
            redstonePowerChanged(level, pos);
        }
    }

    // ------------------------------------------------------------------
    // 元件：装配 / 拆除
    // ------------------------------------------------------------------

    /** 元件装配：原版 {@code setBlock(pos, 元件, 11)} + {@code onPlace}。 */
    private static void elementPlaced(ServerLevel level, BlockPos pos, int current, boolean insideBlockWrite) {
        // onPlace → updateNeighborsInFront（元件特有，总是要发）。
        ElementBlocks.updateNeighborsInFront(level, pos, current);
        if (insideBlockWrite) {
            return;
        }
        // ① 邻居变化：原版来源是**旧方块**——元件不占状态时它就是宿主本身。
        BlockState state = level.getBlockState(pos);
        level.updateNeighborsAt(pos, state.getBlock());
        // ② 形状更新。
        shapeUpdates(level, pos);
    }

    /** 元件拆除：原版 {@code removeBlock(pos, false)} 的 {@code setBlock(pos, 空气, 3)}。 */
    private static void elementRemoved(ServerLevel level, BlockPos pos, int previous, boolean insideBlockWrite) {
        // affectNeighborsAfterRemoval → updateNeighborsInFront。
        ElementBlocks.updateNeighborsInFront(level, pos, previous);
        if (insideBlockWrite) {
            return;
        }
        // ① 邻居变化：原版来源是**被移除的元件方块**。
        Block block = ElementBlocks.tickKey(previous);
        if (block != null) {
            level.updateNeighborsAt(pos, block);
        }
        // ② 形状更新。
        shapeUpdates(level, pos);
    }

    // ------------------------------------------------------------------
    // 元件：状态变化
    // ------------------------------------------------------------------

    private static void elementStateChanged(ServerLevel level, BlockPos pos, int previous, int current,
                                            boolean insideBlockWrite) {
        boolean powered = PhaseExtra.powered(previous) != PhaseExtra.powered(current);
        boolean locked = PhaseExtra.locked(previous) != PhaseExtra.locked(current);
        boolean delay = PhaseExtra.delay(previous) != PhaseExtra.delay(current);
        boolean mode = PhaseExtra.subtractMode(previous) != PhaseExtra.subtractMode(current);
        boolean power = PhaseExtra.power(previous) != PhaseExtra.power(current);
        switch (PhaseExtra.kind(current)) {
            case REPEATER -> {
                if (!powered && !locked && !delay) {
                    return;
                }
                // setBlock(..., 2|3) 的 onPlace → updateNeighborsInFront。
                ElementBlocks.updateNeighborsInFront(level, pos, current);
                if (delay) {
                    // setBlock(..., 3) 的 UPDATE_NEIGHBORS：来源是元件方块本身。
                    Block key = ElementBlocks.tickKey(current);
                    if (key != null) {
                        level.updateNeighborsAt(pos, key);
                    }
                }
                if (!insideBlockWrite) {
                    shapeUpdates(level, pos);
                }
            }
            case COMPARATOR -> {
                if (!powered && !mode && !power) {
                    return;
                }
                if (powered || mode) {
                    // setBlock(..., 2) 的 onPlace → updateNeighborsInFront。
                    ElementBlocks.updateNeighborsInFront(level, pos, current);
                    if (!insideBlockWrite) {
                        shapeUpdates(level, pos);
                    }
                }
                // refreshOutputState 末尾的无条件 updateNeighborsInFront。
                ElementBlocks.updateNeighborsInFront(level, pos, current);
            }
            case OBSERVER -> {
                if (!powered) {
                    return;
                }
                // 原版 ObserverBlock#onPlace 有 `!blockState.is(oldState.getBlock())` 守卫，
                // 同方块内的 POWERED 翻转不触发它；所以这里是 setBlock(..., 2) 的形状更新
                // 加上 tick 末尾的那一次 updateNeighborsInFront。
                if (!insideBlockWrite) {
                    shapeUpdates(level, pos);
                }
                ElementBlocks.updateNeighborsInFront(level, pos, current);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // 红石相
    // ------------------------------------------------------------------

    /** 红石相写入：原版红石线 {@code setBlock(..., 11)} + {@code onPlace}（含功率重算）。 */
    private static void redstonePlaced(ServerLevel level, BlockPos pos, boolean insideBlockWrite) {
        if (!insideBlockWrite) {
            BlockState state = level.getBlockState(pos);
            level.updateNeighborsAt(pos, state.getBlock());
            shapeUpdates(level, pos);
        }
        // 原版 RedStoneWireBlock#onPlace：上下邻居 + 邻线拐角 + updatePowerStrength。
        level.updateNeighborsAt(pos.above(), Blocks.REDSTONE_WIRE);
        level.updateNeighborsAt(pos.below(), Blocks.REDSTONE_WIRE);
        cornerChanges(level, pos, Blocks.REDSTONE_WIRE);
        // 原版 onPlace 第一步就是 updatePowerStrength（功率真的变了才写回，所以递归自然终止）。
        RedstoneBlocks.updatePower(level, pos, level.getBlockState(pos));
    }

    /** 红石相功率变化：{@code DefaultRedstoneWireEvaluator} 的 {@code setBlock(..., 2)} + 本格与 6 邻居。 */
    private static void redstonePowerChanged(ServerLevel level, BlockPos pos) {
        shapeUpdates(level, pos);
        level.updateNeighborsAt(pos, Blocks.REDSTONE_WIRE);
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), Blocks.REDSTONE_WIRE);
        }
    }

    /** 红石相移除：原版红石线 {@code affectNeighborsAfterRemoval}。 */
    private static void redstoneRemoved(ServerLevel level, BlockPos pos, boolean insideBlockWrite) {
        if (!insideBlockWrite) {
            level.updateNeighborsAt(pos, Blocks.REDSTONE_WIRE);
            shapeUpdates(level, pos);
        }
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), Blocks.REDSTONE_WIRE);
        }
        cornerChanges(level, pos, Blocks.REDSTONE_WIRE);
    }

    /**
     * 原版 {@code RedStoneWireBlock.updateNeighborsOfNeighboringWires}：
     * 四水平邻居里，是线的就 {@code checkCornerChangeAt}（本格 + 6 邻居），
     * 是导体的就看它上方那一格。
     */
    private static void cornerChanges(ServerLevel level, BlockPos pos, Block wire) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbourPos = pos.relative(direction);
            if (level.getBlockState(neighbourPos).is(wire)) {
                level.updateNeighborsAt(neighbourPos, wire);
                for (Direction inner : Direction.values()) {
                    level.updateNeighborsAt(neighbourPos.relative(inner), wire);
                }
            } else if (level.getBlockState(neighbourPos).isRedstoneConductor(level, neighbourPos)) {
                BlockPos above = neighbourPos.above();
                if (level.getBlockState(above).is(wire)) {
                    level.updateNeighborsAt(above, wire);
                    for (Direction inner : Direction.values()) {
                        level.updateNeighborsAt(above.relative(inner), wire);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * 原版 {@code Level.setBlock} 形状更新段里的 {@code newState.updateNeighbourShapes(this, pos, i & -34)}。
     * <p>
     * 元件装/拆时宿主状态不变，old/new 两个状态的 neighbour 形状更新效果一致，只发一遍；
     * {@code updateIndirectNeighbourShapes}（红石线的"对角跟随"）对元件宿主没有可观察副作用，
     * 红石相那侧的间接更新由 {@link #cornerChanges} 覆盖。
     */
    private static void shapeUpdates(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        state.updateNeighbourShapes(level, pos, SHAPE_FLAGS);
    }

    // ------------------------------------------------------------------
    // 自检辅助
    // ------------------------------------------------------------------

    /** 供自检读取：形状更新 flag 的口径。 */
    public static int shapeFlagsForTest() {
        return SHAPE_FLAGS;
    }

    /** 供自检读取：该格是否是元件条目。 */
    public static boolean isElementAt(ServerLevel level, BlockPos pos) {
        return PhaseExtra.isElement(PhaseExtras.get(level, pos));
    }

    /** 供自检读取：该格是否是红石条目。 */
    public static boolean isRedstoneAt(ServerLevel level, BlockPos pos) {
        return RedstoneBlocks.hasRedstone(level, pos);
    }

    /** 供自检读取：元件条目对应的 tick key。 */
    public static Block tickKeyForTest(int extra) {
        return ElementBlocks.tickKey(extra);
    }

    /** 供自检读取：该格是否是红石线（原版方块）。 */
    public static boolean isWireAt(ServerLevel level, BlockPos pos) {
        return RedstoneBlocks.isWire(level.getBlockState(pos));
    }

    /** 供自检读取：该格是否是能承载红石线的方块（原版 {@code canSurviveOn} 的两个分支）。 */
    public static boolean supportsWire(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof TrapDoorBlock || state.isFaceSturdy(level, pos, Direction.UP);
    }
}
