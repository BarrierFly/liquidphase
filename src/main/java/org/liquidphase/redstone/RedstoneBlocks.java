package org.liquidphase.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
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
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.phase.PhaseHelper;

/**
 * 红石相（方案 C）：宿主方块上的旁路条目 {@code kind=REDSTONE, power=0..15}。
 * <p>
 * 功率**不占方块状态**：{@code filling} 只有 8 个材料类别。凡是要用功率的入口
 * （{@code getSignal} / {@code getDirectSignal} / 邻居重算）都带坐标，从
 * {@link PhaseExtras} 读。
 * <ul>
 *   <li>输出（{@code getSignal}）：镜像原版红石线（DOWN 不输出），被宿主覆盖的面不输出。</li>
 *   <li>接收：相邻红石线/红石相宿主按"线规则"直读功率（-1 衰减），其他信号源经
 *       {@code level.getSignal} 指向暴露面时供电；邻居变化 → 立即重算。</li>
 *   <li>破坏保留：破坏含红石相的宿主 → 掉宿主战利品 + 原格留下对应功率的原版红石线。</li>
 * </ul>
 */
public final class RedstoneBlocks {

    private RedstoneBlocks() {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** {@return 该格是否含红石相} */
    public static boolean hasRedstone(BlockGetter level, BlockPos pos) {
        return PhaseExtra.isRedstone(PhaseExtras.get(level, pos));
    }

    /** {@return 红石相功率 0–15；非红石相返回 0} */
    public static int getPower(BlockGetter level, BlockPos pos) {
        int extra = PhaseExtras.get(level, pos);
        return PhaseExtra.isRedstone(extra) ? PhaseExtra.power(extra) : 0;
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
     * 用宿主轮廓（{@code filling=none} 的兄弟状态）判断"面是否被固相挡住"；
     * 红石相本身不加碰撞，所以不能直接用含相状态的碰撞形状。
     */
    public static boolean exposed(BlockState state, Direction direction) {
        VoxelShape host = hostOutline(state);
        return !Block.isFaceFull(host, direction);
    }

    /** {@return 宿主轮廓（{@code filling=none} 的兄弟状态）} */
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
    // 输出（由状态级 mixin 委托到这里）
    // ------------------------------------------------------------------

    public static int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (direction == Direction.DOWN || !exposed(state, direction.getOpposite())) {
            return 0;
        }
        int power = getPower(level, pos);
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
     * 邻居变化 → 立即重算本格功率。
     * <p>
     * 与原版 {@code DefaultRedstoneWireEvaluator.updatePowerStrength} 同一手法：
     * 只有功率真的变了才写回，写回后 `updateNeighborsAt` 让邻居重新结算，递归自然终止。
     * 现在功率在旁路表里，写回不改变方块状态，所以必须显式通知邻居。
     */
    public static void neighborChanged(Level level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            updatePower(serverLevel, pos, state);
        }
    }

    /** 立即结算：返回功率是否变化过。 */
    public static boolean updatePower(ServerLevel level, BlockPos pos, BlockState state) {
        int extra = PhaseExtras.get(level, pos);
        if (!PhaseExtra.isRedstone(extra)) {
            return false;
        }
        int newPower = computeTargetPower(level, pos, state);
        if (newPower == PhaseExtra.power(extra)) {
            return false;
        }
        PhaseExtras.set(level, pos, PhaseExtra.withPower(extra, newPower));
        level.updateNeighborsAt(pos, state.getBlock());
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
            } else if (PhaseExtra.isRedstone(PhaseExtras.get(level, neighbourPos))) {
                power = Math.max(power, getPower(level, neighbourPos) - 1);
            } else {
                // 原版电源/元件指向暴露面时供电。
                power = Math.max(power, level.getSignal(neighbourPos, direction));
            }
        }
        return Math.max(0, power);
    }

    // ------------------------------------------------------------------
    // 生成与破坏
    // ------------------------------------------------------------------

    /**
     * 把该格写成含红石相的宿主（**新放置**路径：红石粉点相容宿主）。
     * <p>
     * 方块本身不变，只写旁路条目；初始功率一律为 0——与"单独放置一根红石粉"一致，
     * 落地后由 {@code BlockStateBaseMixin} 在 onPlace 里立即按邻居重算出真实功率。
     * 写入条目时宿主状态剥掉材料填充相与含水标志（0.2.3 清含水：一格只表达一个被含相，
     * 熔岩相被红石粉覆盖时清回 none，含水宿主上的水一并清掉）。
     * <p>
     * 对比：**吸收**路径（把相容宿主放进红石线格）初始功率沿用原线
     * （{@code BlockItemMixin} / {@code FallingBlockIntoWireMixin}）。
     */
    public static boolean setRedstonePhase(LevelAccessor level, BlockPos pos, BlockState state) {
        if (!PhaseHelper.hasPhaseProperty(state)) {
            return false;
        }
        BlockState stripped = PhaseHelper.withoutFillingAndWater(state);
        if (stripped != state) {
            level.setBlock(pos, stripped, Block.UPDATE_ALL);
        }
        PhaseExtras.set(level, pos, PhaseExtra.redstone(0));
        // 状态没变（只写了旁路表），onPlace 不会触发：这里显式结算一次，
        // 镜像原版"放置红石粉 → onPlace → updatePowerStrength"。
        if (level instanceof ServerLevel serverLevel) {
            updatePower(serverLevel, pos, serverLevel.getBlockState(pos));
        }
        return true;
    }

    /**
     * 吸收红石线：初始功率沿用原线（{@code power}），随后立即按邻居重算。
     * <p>
     * 落位的宿主状态若带含水标志（如带水下落的相容宿主落进线格），先剥掉水（清含水）——
     * 条目本身就是一个被含相，水不能同时留在格内。
     */
    public static void setRedstonePhase(LevelAccessor level, BlockPos pos, int power) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
            && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
            level.setBlock(pos, state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, false), Block.UPDATE_ALL);
        }
        PhaseExtras.set(level, pos, PhaseExtra.redstone(power));
        if (level instanceof ServerLevel serverLevel) {
            updatePower(serverLevel, pos, serverLevel.getBlockState(pos));
        }
    }

    /** 直接挖红石相（D8）：掉红石粉、相清空、宿主保留。 */
    public static void mineRedstonePhase(ServerLevel level, BlockPos pos, BlockState state) {
        Block.popResource(level, pos, new ItemStack(Items.REDSTONE));
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
    }

    /** 破坏保留：含红石相的宿主被破坏 → 原格留下对应功率的原版红石线。 */
    public static void materializeWire(Level level, BlockPos pos, int power) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!serverLevel.getBlockState(pos).isAir()) {
            return;
        }
        serverLevel.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState()
            .setValue(BlockStateProperties.POWER, power), Block.UPDATE_ALL);
    }
}
