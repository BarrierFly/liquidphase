package org.liquidphase.phase;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 活塞移动期间"相"的操作辅助（0.2.3）。
 * <ul>
 *   <li>破坏列表标记：{@code PistonBaseBlock.moveBlocks} 的 toDestroy 循环里，被摧毁复合方块的
 *       移除钩子与普通破坏走同一签名（{@code movedByPiston=false}、原格为空气/移塞方块），
 *       无法靠参数区分；用线程内标记把"这次移除来自活塞破坏列表"告诉
 *       {@code BlockStateBaseMixin.liquidphase$removed}，使其按"弹出物品"而非"破坏保留"处理。</li>
 *   <li>移动状态剥相：被活塞推/拉走的方块实体携带的方块状态一律剥掉材料填充相——
 *       相不随活塞移动（黑曜石相本来就推不动；固相在被覆盖的原格弹出、
 *       被清空的原格实体化；红石/元件条目本来就不在状态里）。含水标志保留（原版语义随方块走）。</li>
 * </ul>
 */
public final class PistonPhaseOps {

    private static final ThreadLocal<Boolean> DESTROY_LIST = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PistonPhaseOps() {
    }

    public static void beginDestroyList() {
        DESTROY_LIST.set(Boolean.TRUE);
    }

    public static void endDestroyList() {
        DESTROY_LIST.set(Boolean.FALSE);
    }

    /** {@return 本次移除是否来自活塞破坏列表（仅服务端线程调用）} */
    public static boolean inDestroyList() {
        return DESTROY_LIST.get();
    }

    /** 以破坏列表口径执行一段逻辑（标记只在这一段内成立，结束必清）。 */
    public static void runAsDestroyList(Runnable action) {
        beginDestroyList();
        try {
            action.run();
        } finally {
            endDestroyList();
        }
    }

    /**
     * {@return 移塞方块携带的状态剥掉材料填充相后的版本；无相时原样返回}
     * <p>
     * 相不随活塞移动：被推/拉走的宿主到新位置时是无相状态，相在原格按路径处理
     * （被覆盖 → 弹出物品；被清空 → 实体化留在原位，见 {@code BlockStateBaseMixin.liquidphase$removed}）。
     * 含水标志不剥——水随方块移动是原版含水语义。
     */
    public static BlockState stripCarriedPhase(BlockState carried) {
        if (carried == null || !PhaseHelper.hasPhaseProperty(carried) || PhaseHelper.getPhase(carried) == Phase.NONE) {
            return carried;
        }
        return PhaseHelper.withPhase(carried, Phase.NONE);
    }
}
