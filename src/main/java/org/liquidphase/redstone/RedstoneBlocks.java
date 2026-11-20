package org.liquidphase.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
//? if 26.3 {
/*import net.minecraft.world.level.block.RedstoneWireBlock;
 *///?} else {
import net.minecraft.world.level.block.RedStoneWireBlock;
//?}
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;

/**
 * 红石相（§3.4）的信号语义。红石相**不再是独立方块**，而是宿主方块上的
 * {@code phase = redstone_0 … redstone_15}（功率直接编码在 phase 取值里）：
 * <ul>
 *   <li>方块本身仍是原来的楼梯/台阶/墙/栅栏，形状、掉落、碰撞全部照旧。</li>
 *   <li>输出（{@code getSignal}）：镜像原版红石线（DOWN 不输出），被宿主覆盖的面不输出。</li>
 *   <li>接收：相邻红石线/红石相宿主按"线规则"直读功率（-1 衰减），其他信号源经
 *       {@code level.getSignal} 指向暴露面时供电；邻居变化 → 立即重算。</li>
 *   <li>破坏保留：破坏含红石相的宿主 → 掉宿主战利品 + 原格留下对应功率的原版红石线。</li>
 * </ul>
 * 26.x 的实验性红石评估器不保证一致（§8 第 2 条，写进 README）。
 */
public final class RedstoneBlocks {

    private RedstoneBlocks() {
    }

    // ------------------------------------------------------------------
    // 吸收抑制窗口
    // ------------------------------------------------------------------

    /**
     * 正在被"吸收"（宿主落进红石线格）的格子。吸收的那次 {@code setBlock} 里，
     * 原版红石线移除钩子（{@code affectNeighborsAfterRemoval}）会无条件向四周一圈
     * 发邻居更新，通知环经邻近方块折回本格；本格的重算入口在此窗口内直接跳过，
     * 保证"吸收沿用原线功率、落地不重算"。窗口由 {@code FillingPlacementMixin}
     * 在包住那次 setBlock 时开/关（级联在深度 0 同步排空，finally 关窗是可靠的）。
     * 窗口结束后，邻居变化照常重算（§3.4 的即时结算语义不变）。
     */
    private static final ThreadLocal<java.util.Set<BlockPos>> ABSORPTION_SUPPRESSED =
        ThreadLocal.withInitial(java.util.HashSet::new);

    public static void beginAbsorptionSuppression(BlockPos pos) {
        ABSORPTION_SUPPRESSED.get().add(pos.immutable());
    }

    public static void endAbsorptionSuppression(BlockPos pos) {
        ABSORPTION_SUPPRESSED.get().remove(pos.immutable());
    }

    /** {@return 该格当前是否处于吸收抑制窗口内（{@code FillingPlacementMixin} 开窗）} */
    public static boolean isAbsorptionSuppressed(BlockPos pos) {
        return ABSORPTION_SUPPRESSED.get().contains(pos);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** {@return 该状态是否含红石相} */
    public static boolean isRedstonePhase(BlockState state) {
        return PhaseHelper.getPhase(state).isRedstone();
    }

    /** {@return 红石相功率 0–15；非红石相返回 0} */
    public static int getPower(BlockState state) {
        return PhaseHelper.getPhase(state).redstonePower();
    }

    /** {@return 该状态是否是原版红石线} */
    public static boolean isWire(BlockState state) {
        //? if 26.3 {
        /*return state.getBlock() instanceof RedstoneWireBlock;
         *///?} else {
        return state.getBlock() instanceof RedStoneWireBlock;
        //?}
    }

    /**
     * {@return 宿主在该方向是否露出红石相}
     * <p>
     * 用宿主轮廓（{@code phase=none} 的兄弟状态）判断"面是否被固相挡住"；
     * 红石相本身不加碰撞，所以不能直接用含相状态的碰撞形状。
     */
    public static boolean exposed(BlockState state, Direction direction) {
        VoxelShape host = hostOutline(state);
        return !Block.isFaceFull(host, direction);
    }

    /** {@return 宿主轮廓（phase=none 的兄弟状态）} */
    public static VoxelShape hostOutline(BlockState state) {
        BlockState host = PhaseHelper.withPhase(state, Phase.NONE);
        VoxelShape shape = host.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        if (shape == null || shape.isEmpty()) {
            VoxelShape visual = host.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            if (visual != null && !visual.isEmpty()) {
                shape = visual;
            }
        }
        return shape == null ? net.minecraft.world.phys.shapes.Shapes.empty() : shape;
    }

    // ------------------------------------------------------------------
    // 输出（由 BlockStateBaseMixin 的状态级注入委托到这里）
    // ------------------------------------------------------------------

    public static int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (direction == Direction.DOWN || !exposed(state, direction.getOpposite())) {
            return 0;
        }
        int power = getPower(state);
        if (power <= 0) {
            return 0;
        }
        // 红石线互相之间每格 -1（原版规则）。direction 是"接收方 → 本方块"的方向
        //（对齐 SignalGetter.getSignal 的语义），所以接收方在 direction.getOpposite()。
        // 只有接收方是原版红石线时才衰减，灯/活塞/比较器等元件读到的仍是满功率。
        if (isWire(level.getBlockState(pos.relative(direction.getOpposite())))) {
            return power - 1;
        }
        return power;
    }

    public static int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // 红石线是弱信号源；含红石相的宿主同型。
        return 0;
    }

    // ------------------------------------------------------------------
    // 供电重算（邻居变化即时结算，与原版红石线一致）
    // ------------------------------------------------------------------

    /**
     * 邻居变化 → 立即重算本格功率；功率变化则 {@code setBlock} 触发邻居更新继续向外传播。
     * <p>
     * 排计划刻会延迟两刻才反应；防递归靠"只有功率真的变了才写回"，
     * 与原版 {@code DefaultRedstoneWireEvaluator.updatePowerStrength} 同一手法。
     * 吸收抑制窗口内（宿主刚落进线格、原版红石线移除通知环折回本格）跳过，
     * 保证"吸收沿用原线功率"。
     */
    public static void neighborChanged(Level level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel && !isAbsorptionSuppressed(pos)) {
            updatePower(serverLevel, pos, state);
        }
    }

    /** 立即结算：返回功率是否变化过。 */
    public static boolean updatePower(ServerLevel level, BlockPos pos, BlockState state) {
        int newPower = computeTargetPower(level, pos, state);
        if (newPower == getPower(state)) {
            return false;
        }
        level.setBlock(pos, PhaseHelper.withPhase(state, Phase.redstone(newPower)), Block.UPDATE_ALL);
        return true;
    }

    public static void tick(ServerLevel level, BlockPos pos, BlockState state) {
        updatePower(level, pos, state);
    }

    private static int computeTargetPower(ServerLevel level, BlockPos pos, BlockState state) {
        int power = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbourPos = pos.relative(direction);
            BlockState neighbour = level.getBlockState(neighbourPos);
            if (!exposed(state, direction)) {
                // 被宿主覆盖的面既不接收也不输出。
                continue;
            }
            if (isWire(neighbour)) {
                // 线→线直读，-1 衰减（原版规则）。
                power = Math.max(power, neighbour.getValue(BlockStateProperties.POWER) - 1);
            } else if (isRedstonePhase(neighbour)) {
                power = Math.max(power, getPower(neighbour) - 1);
            } else {
                // 原版电源指向暴露面时供电（§3.4）。
                power = Math.max(power, level.getSignal(neighbourPos, direction));
            }
        }
        return Math.max(0, power);
    }

    // ------------------------------------------------------------------
    // 生成与破坏
    // ------------------------------------------------------------------

    /**
     * 把该格换成含红石相的宿主（**新放置**路径：红石粉点相容宿主）。
     * <p>
     * 方块本身不变，只写 phase 值。初始功率一律为 0——与"单独放置一根红石粉"一致
     * （原版 {@code getStateForPlacement} 给 POWER=0）；写入后立即按邻居重算出真实功率，
     * 等价于原版红石线 onPlace 的 updatePowerStrength。
     * <p>
     * 对比：**吸收**路径（把相容宿主放进红石线格）不走这里——那格的红石线本来就在，
     * 不是重新放置，功率沿用原线且落地不重算（{@code FillingPlacementMixin} 的
     * 吸收抑制窗口 + {@code BlockItemMixin.liquidphase$redstonePhase} /
     * {@code FallingBlockIntoWireMixin} 的功率继承）。
     */
    public static boolean setRedstonePhase(Level level, BlockPos pos, BlockState state) {
        if (!PhaseHelper.hasPhaseProperty(state)) {
            return false;
        }
        BlockState target = PhaseHelper.withPhase(state, Phase.REDSTONE_0);
        if (target == state) {
            return false;
        }
        if (!level.setBlock(pos, target, Block.UPDATE_ALL)) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel) {
            BlockState landed = serverLevel.getBlockState(pos);
            if (isRedstonePhase(landed)) {
                updatePower(serverLevel, pos, landed);
            }
        }
        return true;
    }

    /** 直接挖红石相（D8）：掉红石粉、相清空、宿主保留。 */    public static void mineRedstonePhase(ServerLevel level, BlockPos pos, BlockState state) {
        Block.popResource(level, pos, new ItemStack(Items.REDSTONE));
        level.setBlock(pos, PhaseHelper.withPhase(state, Phase.NONE), Block.UPDATE_ALL);
    }

    /** 破坏保留：含红石相的宿主被破坏 → 原格留下对应功率的原版红石线。 */
    public static void materializeWire(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!serverLevel.getBlockState(pos).isAir()) {
            return;
        }
        serverLevel.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState()
            .setValue(BlockStateProperties.POWER, getPower(state)), Block.UPDATE_ALL);
    }
}
