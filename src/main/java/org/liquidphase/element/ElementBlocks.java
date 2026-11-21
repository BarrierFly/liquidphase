package org.liquidphase.element;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 元件相（中继器 / 比较器 / 侦测器）的逻辑。
 * <p>
 * 元件不占方块、不占状态：参数全部在旁路表里（{@link PhaseExtra}）。这里实现
 * 放置、输入/输出、计划刻与锁存。信号语义照抄原版对应方块（默认评估器口径）：
 * <ul>
 *   <li>中继器/比较器的 {@code facing} = **输入面**，输出面 = {@code facing.getOpposite()}
 *       （与 {@code DiodeBlock} 的 {@code FACING} 语义一致，见 {@code getSignal}/{@code updateNeighborsInFront}）；</li>
 *   <li>侦测器的 {@code facing} = **观察面**，输出口 = {@code facing.getOpposite()}；
 *       观察面邻居变化 → 2 tick 后翻 {@code powered}，再过 2 tick 复位；</li>
 *   <li>被宿主遮住的面不输入也不输出（复用 {@link RedstoneBlocks#exposed}）。</li>
 * </ul>
 */
public final class ElementBlocks {

    /** 元件几何的最小"空腔"判据：足迹内至少有一列顶面低于整格。 */
    private static final double MIN_CAVITY = 1.0;

    private ElementBlocks() {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public static PhaseExtra.Kind kindOfItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return PhaseExtra.Kind.NONE;
        }
        if (stack.is(Items.REPEATER)) {
            return PhaseExtra.Kind.REPEATER;
        }
        if (stack.is(Items.COMPARATOR)) {
            return PhaseExtra.Kind.COMPARATOR;
        }
        if (stack.is(Items.OBSERVER)) {
            return PhaseExtra.Kind.OBSERVER;
        }
        return PhaseExtra.Kind.NONE;
    }

    public static ItemStack itemFor(PhaseExtra.Kind kind) {
        return switch (kind) {
            case REPEATER -> new ItemStack(Items.REPEATER);
            case COMPARATOR -> new ItemStack(Items.COMPARATOR);
            case OBSERVER -> new ItemStack(Items.OBSERVER);
            default -> ItemStack.EMPTY;
        };
    }

    /**
     * {@return 真实元件方块状态对应的旁路条目（kind + 朝向/延迟/模式/锁定/供电）；不是元件方块返回 EMPTY}
     * <p>
     * 0.2.3 的"相材料格"口径：中继器/比较器/侦测器方块本身也能被宿主吸收
     * （宿主放进它所在的格、setBlock 写进宿主格、下落宿主落进去），参数完整继承该方块的状态。
     * 朝向语义与装配一致：中继器/比较器 {@code FACING} = 输入面；侦测器 {@code FACING} = 观察面。
     */
    public static int extraOfBlock(BlockState state) {
        net.minecraft.world.level.block.Block block = state.getBlock();
        if (block instanceof net.minecraft.world.level.block.RepeaterBlock) {
            return PhaseExtra.encode(PhaseExtra.Kind.REPEATER, 0,
                state.getValue(net.minecraft.world.level.block.RepeaterBlock.FACING),
                state.getValue(net.minecraft.world.level.block.RepeaterBlock.DELAY) - 1,
                false,
                state.getValue(net.minecraft.world.level.block.RepeaterBlock.LOCKED),
                state.getValue(net.minecraft.world.level.block.RepeaterBlock.POWERED));
        }
        if (block instanceof net.minecraft.world.level.block.ComparatorBlock) {
            return PhaseExtra.encode(PhaseExtra.Kind.COMPARATOR, 0,
                state.getValue(net.minecraft.world.level.block.ComparatorBlock.FACING),
                0,
                state.getValue(net.minecraft.world.level.block.ComparatorBlock.MODE)
                    == net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT,
                false,
                state.getValue(net.minecraft.world.level.block.ComparatorBlock.POWERED));
        }
        if (block instanceof net.minecraft.world.level.block.ObserverBlock) {
            return PhaseExtra.encode(PhaseExtra.Kind.OBSERVER, 0,
                state.getValue(net.minecraft.world.level.block.ObserverBlock.FACING),
                0, false, false,
                state.getValue(net.minecraft.world.level.block.ObserverBlock.POWERED));
        }
        return PhaseExtra.EMPTY;
    }

    /** {@return 该状态是否是真实元件方块（可被宿主吸收的相材料格）} */
    public static boolean isElementBlock(BlockState state) {
        return !PhaseExtra.isEmpty(extraOfBlock(state));
    }

    /** 吸收真实元件方块到宿主格：清材料相/含水，再写入继承来的条目。 */
    public static boolean absorbBlock(Level level, BlockPos pos, BlockState host, int extra) {
        if (PhaseExtra.isEmpty(extra)) {
            return false;
        }
        BlockState stripped = PhaseHelper.withoutFillingAndWater(host);
        if (stripped != host) {
            level.setBlock(pos, stripped, net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        PhaseExtras.set(level, pos, extra);
        level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
        return true;
    }

    public static boolean hasElement(BlockGetter level, BlockPos pos) {
        return PhaseExtra.isElement(PhaseExtras.get(level, pos));
    }

    /** {@return 该格元件几何可用的"宿主顶面高度"（0–1，逐列）} */
    public static double hostTop(Level level, BlockPos pos, BlockState host, double localX, double localZ) {
        VoxelShape shape = host.getCollisionShape(level, pos, CollisionContext.empty());
        if (shape == null || shape.isEmpty()) {
            return 0.0;
        }
        double top = shape.max(Direction.Axis.Y, localX, localZ);
        if (top == Double.NEGATIVE_INFINITY) {
            return 0.0;
        }
        return Math.min(1.0, top);
    }

    /**
     * {@return 足迹内是否存在"有空间"的列（放置前提，文档 §2.2）}
     * <p>
     * 相门控（0.2.3 通用化）：与红石相共用同一条"可写入"判定
     * {@link PhaseHelper#canAcceptExtra}——条目为空且现有填充相为空（含水可写，水被清掉）
     * 或熔岩（被清回 none）；冰/霜冰/黑曜石相不可覆盖。元件不再要求"严格空相"。
     */
    public static boolean hasCavity(Level level, BlockPos pos, BlockState host) {
        if (!PhaseHelper.hasPhaseProperty(host) || !PhaseHelper.canAcceptExtra(level, pos, host)) {
            return false;
        }
        VoxelShape shape = host.getCollisionShape(level, pos, CollisionContext.empty());
        if (shape == null || shape.isEmpty()) {
            return true;
        }
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                double top = shape.max(Direction.Axis.Y, (i + 0.5) / 16.0, (j + 0.5) / 16.0);
                if (top == Double.NEGATIVE_INFINITY || top < MIN_CAVITY) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 放置 / 破坏
    // ------------------------------------------------------------------

    /**
     * 在宿主上表面放一个元件。{@code facing} 按元件语义解释（中继器/比较器 = 输入面，
     * 侦测器 = 观察面）。
     * <p>
     * 写入条目时宿主状态剥掉材料填充相与含水标志（清含水 / 覆盖熔岩相）——
     * 条目本身就是一个被含相，一格只表达一个。
     */
    public static boolean place(Level level, BlockPos pos, BlockState host, PhaseExtra.Kind kind, Direction facing) {
        if (!hasCavity(level, pos, host)) {
            return false;
        }
        BlockState stripped = PhaseHelper.withoutFillingAndWater(host);
        if (stripped != host) {
            level.setBlock(pos, stripped, Block.UPDATE_ALL);
            host = stripped;
        }
        int extra = PhaseExtra.element(kind, facing, 0, false, false);
        PhaseExtras.set(level, pos, extra);
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.4F, 1.0F);
        // 有输入时立即排一次刻（镜像 setPlacedBy → shouldTurnOn）。
        if (level instanceof ServerLevel serverLevel) {
            int input = inputSignal(serverLevel, pos, host, extra);
            if (kind == PhaseExtra.Kind.REPEATER && input > 0) {
                serverLevel.scheduleTick(pos, host.getBlock(), 1);
            } else if (kind == PhaseExtra.Kind.COMPARATOR) {
                serverLevel.scheduleTick(pos, host.getBlock(), 2);
            }
        }
        return true;
    }

    /** 破坏元件：清条目 + 掉落对应物品；宿主保留。 */
    public static void mine(ServerLevel level, BlockPos pos, BlockState host, int extra) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        ItemStack drop = itemFor(kind);
        if (!drop.isEmpty()) {
            Block.popResource(level, pos, drop);
        }
        notifyOutput(level, pos, host, extra);
    }

    // ------------------------------------------------------------------
    // 信号输出
    // ------------------------------------------------------------------

    public static int getSignal(BlockState host, BlockGetter level, BlockPos pos, Direction direction, int extra) {
        if (PhaseExtra.isEmpty(extra) || !PhaseExtra.isElement(extra)) {
            return 0;
        }
        // 接收方在输出面（facing.getOpposite()）上时，direction（接收方→本格）= facing。
        if (direction != PhaseExtra.facing(extra)) {
            return 0;
        }
        if (!RedstoneBlocks.exposed(host, direction.getOpposite())) {
            return 0;
        }
        if (!PhaseExtra.powered(extra)) {
            return 0;
        }
        return switch (PhaseExtra.kind(extra)) {
            case REPEATER, OBSERVER -> 15;
            case COMPARATOR -> PhaseExtra.power(extra);
            default -> 0;
        };
    }

    public static int getDirectSignal(BlockState host, BlockGetter level, BlockPos pos, Direction direction, int extra) {
        // 原版中继器/比较器/侦测器的强信号 = 弱信号。
        return getSignal(host, level, pos, direction, extra);
    }

    // ------------------------------------------------------------------
    // 计划刻与邻居变化
    // ------------------------------------------------------------------

    /** 状态级 {@code updateShape}：侦测器观察面邻居变化 → 2 tick 后翻起；中继器侧向变化 → 重算锁存。 */
    public static void onUpdateShape(LevelAccessor level, BlockPos pos, BlockState host, int extra, Direction changedDirection) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        if (kind == PhaseExtra.Kind.REPEATER
            && changedDirection.getAxis() != PhaseExtra.facing(extra).getAxis()) {
            if (level instanceof Level actualLevel) {
                refreshLock(actualLevel, pos, host, extra);
            }
            return;
        }
        if (kind != PhaseExtra.Kind.OBSERVER) {
            return;
        }
        if (changedDirection != PhaseExtra.facing(extra) || PhaseExtra.powered(extra)) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!serverLevel.getBlockTicks().hasScheduledTick(pos, host.getBlock())) {
            serverLevel.scheduleTick(pos, host.getBlock(), 2);
        }
    }

    public static void neighborChanged(ServerLevel level, BlockPos pos, BlockState host, int extra) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        if (!kind.isElement()) {
            return;
        }
        if (level.getBlockTicks().willTickThisTick(pos, host.getBlock())) {
            return;
        }
        switch (kind) {
            case REPEATER -> {
                if (PhaseExtra.locked(extra)) {
                    return;
                }
                boolean turnOn = inputSignal(level, pos, host, extra) > 0;
                if (turnOn != PhaseExtra.powered(extra)) {
                    level.scheduleTick(pos, host.getBlock(), PhaseExtra.delayTicks(extra) * 2);
                }
            }
            case COMPARATOR -> {
                int output = comparatorOutput(level, pos, host, extra);
                boolean turnOn = shouldTurnOn(level, pos, host, extra);
                if (output != PhaseExtra.power(extra) || turnOn != PhaseExtra.powered(extra)) {
                    level.scheduleTick(pos, host.getBlock(), 2);
                }
            }
            default -> {
                // 侦测器靠 updateShape 触发，不需要邻居重算。
            }
        }
    }

    public static void tick(ServerLevel level, BlockPos pos, BlockState host, int extra) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        switch (kind) {
            case REPEATER -> {
                if (PhaseExtra.locked(extra)) {
                    return;
                }
                boolean turnOn = inputSignal(level, pos, host, extra) > 0;
                if (turnOn != PhaseExtra.powered(extra)) {
                    int previous = extra;
                    extra = PhaseExtra.withPowered(extra, turnOn);
                    PhaseExtras.set(level, pos, extra);
                    notifyOutput(level, pos, host, previous);
                }
            }
            case COMPARATOR -> {
                int output = comparatorOutput(level, pos, host, extra);
                boolean turnOn = shouldTurnOn(level, pos, host, extra);
                int previous = extra;
                extra = PhaseExtra.withPower(PhaseExtra.withPowered(extra, turnOn), output);
                if (extra != previous) {
                    PhaseExtras.set(level, pos, extra);
                    notifyOutput(level, pos, host, previous);
                }
            }
            case OBSERVER -> {
                int previous = extra;
                boolean powered = !PhaseExtra.powered(extra);
                extra = PhaseExtra.withPowered(extra, powered);
                PhaseExtras.set(level, pos, extra);
                if (powered) {
                    // 脉冲持续 1 redstone tick，再排一次刻复位。
                    level.scheduleTick(pos, host.getBlock(), 2);
                }
                notifyOutput(level, pos, host, previous);
            }
            default -> {
            }
        }
    }

    private static void notifyOutput(ServerLevel level, BlockPos pos, BlockState host, int extra) {
        Direction output = PhaseExtra.facing(extra).getOpposite();
        BlockPos front = pos.relative(output);
        level.updateNeighborsAt(front, host.getBlock());
        level.updateNeighborsAt(pos, host.getBlock());
    }

    // ------------------------------------------------------------------
    // 原版算式（默认评估器）
    // ------------------------------------------------------------------

    /** 中继器输入（{@code DiodeBlock.getInputSignal}）。 */
    private static int inputSignal(Level level, BlockPos pos, BlockState host, int extra) {
        if (!RedstoneBlocks.exposed(host, PhaseExtra.facing(extra))) {
            return 0;
        }
        Direction input = PhaseExtra.facing(extra);
        BlockPos behind = pos.relative(input);
        int signal = level.getSignal(behind, input);
        if (signal >= 15) {
            return signal;
        }
        BlockState state = level.getBlockState(behind);
        return Math.max(signal, RedstoneBlocks.isWire(state)
            ? state.getValue(BlockStateProperties.POWER) : 0);
    }

    /** 比较器输入（{@code ComparatorBlock.getInputSignal}）。 */
    private static int comparatorInput(Level level, BlockPos pos, BlockState host, int extra) {
        int signal = inputSignal(level, pos, host, extra);
        Direction input = PhaseExtra.facing(extra);
        BlockPos behind = pos.relative(input);
        BlockState state = level.getBlockState(behind);
        if (state.hasAnalogOutputSignal()) {
            //? if 1.21.10 || 1.21.11 || 26.3 {
            return state.getAnalogOutputSignal(level, behind, input.getOpposite());
            //?} else {
            /*return state.getAnalogOutputSignal(level, behind);
             *///?}
        }
        if (signal < 15 && state.isRedstoneConductor(level, behind)) {
            BlockPos further = behind.relative(input);
            state = level.getBlockState(further);
            ItemFrame frame = itemFrame(level, input, further);
            //? if 1.21.10 || 1.21.11 || 26.3 {
            int analog = Math.max(
                frame == null ? Integer.MIN_VALUE : frame.getAnalogOutput(),
                state.hasAnalogOutputSignal() ? state.getAnalogOutputSignal(level, further, input.getOpposite()) : Integer.MIN_VALUE);
            //?} else {
            /*int analog = Math.max(
                frame == null ? Integer.MIN_VALUE : frame.getAnalogOutput(),
                state.hasAnalogOutputSignal() ? state.getAnalogOutputSignal(level, further) : Integer.MIN_VALUE);
             *///?}
            if (analog != Integer.MIN_VALUE) {
                return analog;
            }
        }
        return signal;
    }

    private static ItemFrame itemFrame(Level level, Direction direction, BlockPos pos) {
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class,
            new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1),
            frame -> frame.getDirection() == direction);
        return frames.size() == 1 ? frames.get(0) : null;
    }

    /** 比较器侧输入（{@code DiodeBlock.getAlternateSignal} 的等价物）。 */
    private static int alternateSignal(Level level, BlockPos pos, BlockState host, int extra) {
        Direction input = PhaseExtra.facing(extra);
        return Math.max(sideSignal(level, pos.relative(input.getClockWise()), input.getClockWise()),
            sideSignal(level, pos.relative(input.getCounterClockWise()), input.getCounterClockWise()));
    }

    private static int sideSignal(Level level, BlockPos pos, Direction side) {
        BlockState state = level.getBlockState(pos);
        int extra = PhaseExtras.get(level, pos);
        if (PhaseExtra.isElement(extra)) {
            PhaseExtra.Kind kind = PhaseExtra.kind(extra);
            if ((kind == PhaseExtra.Kind.REPEATER || kind == PhaseExtra.Kind.COMPARATOR)
                && PhaseExtra.facing(extra) == side) {
                return getSignal(state, level, pos, side, extra);
            }
            return 0;
        }
        return level.getSignal(pos, side);
    }

    private static boolean shouldTurnOn(Level level, BlockPos pos, BlockState host, int extra) {
        int input = comparatorInput(level, pos, host, extra);
        if (input == 0) {
            return false;
        }
        int alternate = alternateSignal(level, pos, host, extra);
        return input > alternate || (input == alternate && !PhaseExtra.subtractMode(extra));
    }

    private static int comparatorOutput(Level level, BlockPos pos, BlockState host, int extra) {
        int input = comparatorInput(level, pos, host, extra);
        if (input == 0) {
            return 0;
        }
        int alternate = alternateSignal(level, pos, host, extra);
        if (alternate > input) {
            return 0;
        }
        return PhaseExtra.subtractMode(extra) ? input - alternate : input;
    }

    // ------------------------------------------------------------------
    // 中继器锁存
    // ------------------------------------------------------------------

    public static boolean isLocked(Level level, BlockPos pos, BlockState host, int extra) {
        Direction input = PhaseExtra.facing(extra);
        for (Direction side : new Direction[]{input.getClockWise(), input.getCounterClockWise()}) {
            BlockPos sidePos = pos.relative(side);
            int sideExtra = PhaseExtras.get(level, sidePos);
            if (!PhaseExtra.isElement(sideExtra) || !PhaseExtra.powered(sideExtra)) {
                continue;
            }
            PhaseExtra.Kind kind = PhaseExtra.kind(sideExtra);
            if (kind != PhaseExtra.Kind.REPEATER && kind != PhaseExtra.Kind.COMPARATOR) {
                continue;
            }
            // 侧向元件要对着本格输出（其输出面 = facing.getOpposite() 指向本格）。
            if (PhaseExtra.facing(sideExtra).getOpposite() == side.getOpposite()) {
                return true;
            }
        }
        return false;
    }

    public static void refreshLock(Level level, BlockPos pos, BlockState host, int extra) {
        if (PhaseExtra.kind(extra) != PhaseExtra.Kind.REPEATER) {
            return;
        }
        boolean locked = isLocked(level, pos, host, extra);
        if (locked != PhaseExtra.locked(extra)) {
            PhaseExtras.set(level, pos, PhaseExtra.withLocked(extra, locked));
        }
    }

    /** 随机源占位（比较器/中继器计划刻不需要随机数）。 */
    public static RandomSource unusedRandom(ServerLevel level) {
        return level.getRandom();
    }

    public static int comparatorOutputForTest(ServerLevel level, BlockPos pos, BlockState host, int extra) {
        return comparatorOutput(level, pos, host, extra);
    }
}
