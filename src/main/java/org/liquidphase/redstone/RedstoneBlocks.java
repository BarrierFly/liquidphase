package org.liquidphase.redstone;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
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
import net.minecraft.world.phys.BlockHitResult;
import org.liquidphase.phase.PhaseCapable;

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

    /** {@return 组合方块对应的宿主方块（注册前尚未填表时用构造期 ThreadLocal 兜底）} */
    public static Block hostBlockOf(Block composite) {
        Block host = COMPOSITE_TO_HOST.get(composite);
        if (host == null) {
            host = BUILDING_HOST.get();
        }
        return host;
    }

    /** 构造期（组合方块实例还没注册进映射表）的宿主，见 {@code CompositeFactories.create}。 */
    private static final ThreadLocal<Block> BUILDING_HOST = new ThreadLocal<>();

    /** 组合方块实例构造前设置宿主，构造后清除（供形状查询在 initCache 期间使用）。 */
    public static void beginBuilding(Block host) {
        BUILDING_HOST.set(host);
    }

    public static void endBuilding() {
        BUILDING_HOST.remove();
    }

    public static boolean isPhaseHostItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof PhaseCapable
            && HOST_TO_COMPOSITE.containsKey(blockItem.getBlock());
    }

    /**
     * 手持宿主方块右击红石线 → 该格变成红石相组合方块（§3.0.2 两向吸收）。
     * <p>
     * 不依赖物品放置流程：红石线自己的右键会先吃掉交互，方块物品那条分支根本走不到。
     * 这里直接在红石线的右键入口里完成替换，返回值表示"是否已处理"。
     * 宿主状态按线所在格计算（朝向/半砖/连接态都是放置状态），功率沿用原线。
     */
    public static boolean placeHostIntoWire(Level level, BlockPos pos, Player player, InteractionHand hand,
                                            BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (!isPhaseHostItem(stack)) {
            return false;
        }
        // 红石线不是可替换方块，普通 context 的 getClickedPos() 会算成线旁边那格；
        // WirePlaceContext 强制 replaceClicked，让宿主按线所在格算放置状态。
        BlockPlaceContext context = new WirePlaceContext(level, player, hand, stack, hit);
        return placeHostIntoWire(level, pos, stack, () -> ((BlockItem) stack.getItem()).getBlock().getStateForPlacement(context));
    }

    /** 放置实现（与玩家解耦，自检也走这条路径）。 */
    public static boolean placeHostIntoWire(Level level, BlockPos pos, ItemStack stack,
                                            java.util.function.Supplier<BlockState> hostStateSupplier) {
        if (!isPhaseHostItem(stack)) {
            return false;
        }
        BlockState wire = level.getBlockState(pos);
        if (!isWire(wire)) {
            return false;
        }
        Block host = ((BlockItem) stack.getItem()).getBlock();
        Block composite = HOST_TO_COMPOSITE.get(host);
        if (composite == null) {
            return false;
        }

        BlockState hostState = hostStateSupplier.get();
        BlockState cs = composite.defaultBlockState();
        if (hostState != null) {
            for (Property<?> property : hostState.getProperties()) {
                if (cs.hasProperty(property) && property != POWER) {
                    cs = copyValue(cs, property, hostState.getValue(property));
                }
            }
        }
        cs = cs.setValue(POWER, wire.getValue(BlockStateProperties.POWER));
        if (!cs.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, cs, Block.UPDATE_ALL);
        return true;
    }

    /** {@return 该状态是不是原版红石线} */
    public static boolean isWire(BlockState state) {
        //? if 26.3 {
        /*return state.getBlock() instanceof RedstoneWireBlock;
         *///?} else {
        return state.getBlock() instanceof RedStoneWireBlock;
        //?}
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
        VoxelShape host = hostOutline(state);
        return !Block.isFaceFull(host, direction);
    }

    /**
     * {@return 宿主轮廓（phase=none 的兄弟状态；组合方块换回宿主方块）}
     * <p>
     * 不用含相/组合状态的形状：组合状态的 {@code getShape} 已被改成整格选择框（§3.4 交互箱），
     * 用它判断"露出"会恒为 true。优先用宿主碰撞形状（固相真正挡住的面），查不到再退回视觉形状。
     */
    public static VoxelShape hostOutline(BlockState state) {
        BlockState host = isRedstonePhase(state) ? hostStateOf(state) : state;
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
    // 输出（组合方块覆盖的方法委托到这里）
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
        // 红石线是弱信号源；组合方块同型。
        return 0;
    }

    // ------------------------------------------------------------------
    // 供电重算（邻居变化即时结算，与原版红石线一致）
    // ------------------------------------------------------------------

    /**
     * 邻居变化 → 立即重算本格功率；功率变化则立即把邻居的"邻居变化"也走一遍。
     * <p>
     * 之前这里排的是 2 tick 计划刻，所以按开关/拆线要等两刻才反应（"信号不是瞬时的"）。
     * 即时结算需要防递归：只在本格功率真正变化时才继续向外传播（与原版
     * {@code DefaultRedstoneWireEvaluator.updatePowerStrength} 的做法一致）。
     */
    public static void neighborChanged(Level level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            updatePower(serverLevel, pos, state);
        }
    }

    /** 立即结算：返回功率是否变化过。 */
    public static boolean updatePower(ServerLevel level, BlockPos pos, BlockState state) {
        int newPower = computeTargetPower(level, pos, state);
        if (newPower == getPower(state)) {
            return false;
        }
        level.setBlock(pos, state.setValue(POWER, newPower), Block.UPDATE_ALL);
        // setBlock(UPDATE_ALL) 已触发邻居更新；组合方块自身的邻居变化又会回流到这里，
        // 但下一次 newPower == 当前功率，递归自然终止。
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
            if (isWire(neighbour)) {
                // 线→线直读，-1 衰减（原版规则）；被宿主覆盖的面不连接（§3.4）。
                if (exposed(state, direction)) {
                    power = Math.max(power, neighbour.getValue(BlockStateProperties.POWER) - 1);
                }
            } else if (neighbour.getBlock() instanceof RedstonePhaseBlock) {
                if (exposed(state, direction)) {
                    power = Math.max(power, getPower(neighbour) - 1);
                }
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
        Block hostBlock = hostBlockOf(composite.getBlock());
        if (hostBlock == null || hostBlock == Blocks.AIR) {
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
