package org.liquidphase.redstone;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Map;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 红石相（§3.4，决策点 D10/R1 组合方块）的信号语义：
 * <ul>
 *   <li>组合方块 = 宿主类子类 + POWER(0-15)；红石线纹理满格覆盖、无碰撞（碰撞=宿主自身）。</li>
 *   <li>输出（getSignal）：镜像原版红石线（DOWN 不输出），但被宿主覆盖的面不输出
 *       （"露出红石相的表面均可输出，被固相覆盖的表面不可"，上下同标准）。</li>
 *   <li>接收：相邻原版红石线/组合方块按"线规则"直读功率（-1 衰减链），其他信号源经
 *       level.getSignal 指向暴露面时供电；邻居变化 → 计划刻重算，避免递归。</li>
 *   <li>破坏保留：组合方块被破坏 → 掉宿主战利品 + 原格留下对应功率的原版红石线。</li>
 * </ul>
 * 26.x 的实验性红石评估器不保证一致（§8 第 2 条，写进 README）。
 */
public final class RedstoneBlocks {

    /** 红石相功率（组合方块自己的属性）。 */
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 15);

    /** 组合方块 → 宿主方块（注册时填充，破坏保留/挖相时换回宿主状态）。 */
    public static final Map<Block, Block> COMPOSITE_TO_HOST = new Reference2ObjectOpenHashMap<>();

    /** 宿主 → 已注册的组合方块（运行时按宿主查表，绝不在交互时新建注册项）。 */
    public static final Map<Block, Block> HOST_TO_COMPOSITE = new Reference2ObjectOpenHashMap<>();

    private RedstoneBlocks() {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public static boolean isRedstonePhase(BlockState state) {
        return state.getBlock() instanceof RedstonePhaseBlock;
    }

    public static int getPower(BlockState state) {
        return state.hasProperty(POWER) ? state.getValue(POWER) : 0;
    }

    /** {@return 宿主在该方向是否露出红石相（用宿主碰撞形状判定，§3.4）} */
    public static boolean exposed(BlockState state, Direction direction) {
        VoxelShape host = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        return !Block.isFaceFull(host, direction);
    }

    // ------------------------------------------------------------------
    // 输出（组合方块覆盖的方法委托到这里）
    // ------------------------------------------------------------------

    public static int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (direction == Direction.DOWN || !exposed(state, direction.getOpposite())) {
            return 0;
        }
        return getPower(state);
    }

    public static int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // 红石线是弱信号源；组合方块同型。
        return 0;
    }

    // ------------------------------------------------------------------
    // 供电重算（计划刻驱动，避免邻居更新递归）
    // ------------------------------------------------------------------

    public static void neighborChanged(Level level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel
            && !serverLevel.getBlockTicks().hasScheduledTick(pos, state.getBlock())) {
            serverLevel.scheduleTick(pos, state.getBlock(), 2);
        }
    }

    public static void tick(ServerLevel level, BlockPos pos, BlockState state) {
        int newPower = computeTargetPower(level, pos, state);
        if (newPower != getPower(state)) {
            level.setBlock(pos, state.setValue(POWER, newPower), Block.UPDATE_ALL);
        }
    }

    private static boolean isWire(BlockState state) {
        //? if 26.3 {
        /*return state.getBlock() instanceof RedstoneWireBlock;
         *///?} else {
        return state.getBlock() instanceof RedStoneWireBlock;
        //?}
    }

    private static int computeTargetPower(ServerLevel level, BlockPos pos, BlockState state) {
        int power = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbourPos = pos.relative(direction);
            BlockState neighbour = level.getBlockState(neighbourPos);
            if (isWire(neighbour)) {
                // 线→线直读，-1 衰减（原版规则）。
                power = Math.max(power, neighbour.getValue(BlockStateProperties.POWER) - 1);
            } else if (neighbour.getBlock() instanceof RedstonePhaseBlock) {
                power = Math.max(power, getPower(neighbour) - 1);
            } else if (exposed(state, direction)) {
                // 原版电源指向暴露面时供电（§3.4）。
                power = Math.max(power, level.getSignal(neighbourPos, direction));
            }
        }
        return Math.max(0, power);
    }

    // ------------------------------------------------------------------
    // 破坏保留：组合方块被移除 → 原版红石线（对应功率）留在原格（§3.0.3）
    // ------------------------------------------------------------------

    public static void onRemoved(Level level, BlockPos pos, BlockState state, boolean movedByPiston) {
        if (movedByPiston || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!serverLevel.getBlockState(pos).isAir()) {
            return;
        }
        serverLevel.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState()
            .setValue(BlockStateProperties.POWER, getPower(state)), Block.UPDATE_ALL);
    }

    /** 直接挖红石相（D8）：掉红石粉、组合方块换回宿主方块（属性逐名拷贝）。 */
    public static void mineRedstonePhase(ServerLevel level, BlockPos pos, BlockState state) {
        Block.popResource(level, pos, new ItemStack(Items.REDSTONE));
        level.setBlock(pos, hostStateOf(state), Block.UPDATE_ALL);
    }

    /** {@return 宿主方块上与组合状态同名属性拷贝出的状态} */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockState hostStateOf(BlockState composite) {
        Block hostBlock = COMPOSITE_TO_HOST.get(composite.getBlock());
        if (hostBlock == null) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState host = hostBlock.defaultBlockState();
        for (Property<?> property : composite.getProperties()) {
            if (property != POWER && host.hasProperty(property)) {
                host = copyValue(host, property, composite.getValue(property));
            }
        }
        return host;
    }

    /** {@return 该状态在 property 上的取值（跨版本：26.3 没有 getValues().entrySet()）} */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Comparable<?> valueOf(BlockState state, Property<?> property) {
        return (Comparable<?>) ((net.minecraft.world.level.block.state.StateHolder) state).getValue((Property) property);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockState copyValue(BlockState state, Property property, Comparable<?> value) {
        return (BlockState) ((net.minecraft.world.level.block.state.StateHolder) state).setValue(property, value);
    }
}
