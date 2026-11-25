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
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.TickPriority;
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
 * <p>
 * <b>计划刻（0.2.7）</b>：元件的计划刻用**原版元件方块本身**当 tick key
 * （中继器 → {@link Blocks#REPEATER}、比较器 → {@link Blocks#COMPARATOR}、侦测器 →
 * {@link Blocks#OBSERVER}），由 {@code ServerLevel.tickBlock} 的 HEAD 注入识别后执行。
 * 这样元件的计划刻与宿主的计划刻是两个独立槽位，互不吞刻；也不引入任何新方块类型，
 * 旧存档里由原版元件方块排下的刻（吸收真实元件时）会继续执行，所以吸收时"填充形态直接照常继承"。
 * <p>
 * <b>通知（0.2.7）</b>：旁路表写入后的三条通道（邻居变化 / 形状更新 / 模拟量通知）集中在
 * {@link org.liquidphase.phase.PhaseNotifications#entryChanged}，按原版 {@code Level.setBlock}
 * 在同样事件下会做的事逐类复刻；散落的 {@code updateNeighborsAt} 全部删除。
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
     * {@return 该条目对应的原版元件方块（计划刻 tick key / 邻居通知的 source block）}
     * <p>
     * 中继器/比较器/侦测器分别映射到 {@code minecraft:repeater} / {@code comparator} / {@code observer}。
     * 这三个方块都**不是**相容宿主（没有 {@code filling} 属性），所以它们自身的计划刻永远不会
     * 与我们的元件刻混在一起；同时它们都是原版 {@code isSignalSource() == true} 的方块，
     * 用它们当通知来源时，依赖 {@code Block.defaultBlockState().isSignalSource()} 的原版逻辑
     * （例如 {@code RailBlock.updateState}）能原样生效。
     */
    public static Block tickKey(int extra) {
        return switch (PhaseExtra.kind(extra)) {
            case REPEATER -> Blocks.REPEATER;
            case COMPARATOR -> Blocks.COMPARATOR;
            case OBSERVER -> Blocks.OBSERVER;
            default -> null;
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

    /**
     * 吸收真实元件方块到宿主格：清材料相/含水，再写入继承来的条目。
     * <p>
     * <b>0.2.7：原样继承</b>。朝向/延迟/模式/锁定/供电一律照抄；比较器额外把原方块实体里的
     * {@code OutputSignal} 读进条目的 {@code power}（吸收瞬间输出就正确）。不补初始结算、不额外排刻：
     * 原版元件方块已经排下的计划刻用的是同一个 tick key（见 {@link #tickKey}），吸收后会照常执行
     * （正在脉冲的侦测器会在原定时刻复位；有输入的中继器/比较器会在原定时刻结算）。
     * <p>
     * 通知范围是 {@link PhaseExtras.Notify#NONE}：填充形态直接照常继承，不补"宿主出现"的额外操作——
     * 宿主落位那一步自己已经发过邻居变化与形状更新。
     */
    public static boolean absorbBlock(Level level, BlockPos pos, BlockState host, int extra) {
        if (PhaseExtra.isEmpty(extra)) {
            return false;
        }
        // 比较器：把原方块实体的输出信号带进条目（读在写状态之前——写状态会删掉方块实体）。
        if (PhaseExtra.kind(extra) == PhaseExtra.Kind.COMPARATOR) {
            // 端到端路径上比较器的方块实体在写条目之前就被 setBlock 删掉了，所以这里先读暂存值
            // （LevelChunkExtraMixin 在 setBlockState HEAD 记下的 OutputSignal），再退回读方块实体。
            int captured = org.liquidphase.phase.ElementSignals.take(pos);
            if (captured != Integer.MIN_VALUE) {
                extra = PhaseExtra.withPower(extra, captured);
            } else if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity entity) {
                extra = PhaseExtra.withPower(extra, entity.getOutputSignal());
            }
        }
        BlockState stripped = PhaseHelper.withoutFillingAndWater(host);
        if (stripped != host) {
            level.setBlock(pos, stripped, Block.UPDATE_ALL);
        }
        PhaseExtras.set(level, pos, extra, PhaseExtras.Notify.NONE);
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
        // VoxelShape.max(Y, a, b) 的坐标参数顺序是 (z, x)，不是 (x, z)（0.2.5 修，见
        // ElementRenderSupport.columnTop 的注释）；单点查询同样必须换序。
        double top = shape.max(Direction.Axis.Y, localZ, localX);
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
                // (z, x) 顺序，同 ElementRenderSupport.columnTop（0.2.5 修）。
                // 这里 16×16 对称采样，换序不改变"是否存在空腔列"的结论，
                // 但保持与采样口径一致，避免以后改成非对称采样时再次踩坑。
                double top = shape.max(Direction.Axis.Y, (j + 0.5) / 16.0, (i + 0.5) / 16.0);
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
     * 条目本身就是一个被含相，一格只表达一个。初始 {@code locked} 在写表之前按原版
     * {@code getStateForPlacement} 的算式算好，条目只写一次（原版一次放置只做一次 {@code setBlock}）。
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
        // 原版 getStateForPlacement 会给中继器补 LOCKED；这里在条目落地前按同一算式算一次。
        if (kind == PhaseExtra.Kind.REPEATER) {
            extra = PhaseExtra.withLocked(extra, isLocked(level, pos, host, extra));
        }
        PhaseExtras.set(level, pos, extra);
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.4F, 1.0F);
        // 镜像 DiodeBlock#setPlacedBy：shouldTurnOn 时排 1 tick；只有有输入才需要。
        if (level instanceof ServerLevel serverLevel && shouldTurnOnOnPlace(serverLevel, pos, host, extra)) {
            serverLevel.scheduleTick(pos, tickKey(extra), 1);
        }
        return true;
    }

    /** 放置时的初始判断（中继器 = 输入 > 0；比较器 = 原版 shouldTurnOn）。 */
    private static boolean shouldTurnOnOnPlace(Level level, BlockPos pos, BlockState host, int extra) {
        return switch (PhaseExtra.kind(extra)) {
            case REPEATER -> inputSignal(level, pos, host, extra) > 0;
            case COMPARATOR -> shouldTurnOn(level, pos, host, extra);
            default -> false;
        };
    }

    /** {@return 该条目的伪状态（原版元件方块）——挖掘硬度/工具判定与渲染无关} */
    public static BlockState pseudoState(int extra) {
        return switch (PhaseExtra.kind(extra)) {
            case REPEATER -> Blocks.REPEATER.defaultBlockState();
            case COMPARATOR -> Blocks.COMPARATOR.defaultBlockState();
            case OBSERVER -> Blocks.OBSERVER.defaultBlockState();
            default -> null;
        };
    }

    /**
     * {@return 该元件在瞄准里是否**优先于宿主**（G-(c)，用户口径：宿主任意朝上的面都算元件）}
     * <p>
     * 判据只有两条：
     * <ul>
     *   <li><b>侦测器</b>：整格命中即算元件（原版侦测器没有覆写 {@code getShape}，轮廓就是整格），
     *       所以它**始终先被瞄准**；</li>
     *   <li><b>中继器/比较器</b>：射线命中宿主形状、且命中面是**朝上的面**（{@code Direction.UP}）
     *       就算元件；命中的是侧面/底面则算宿主。</li>
     * </ul>
     * 这与"瞄准宿主任意朝上的面"一致：下半砖/楼梯/台阶上的元件，从上方落到它们露出的
     * 每一个朝上表面都会选中元件。
     * <p>
     * 元件先于固相/红石相判定：三者同格时元件在最外层（渲染也画在宿主表面）。
     */
    public static boolean isTargeted(Player player, Level level, BlockPos pos, BlockState host, int extra) {
        if (!PhaseExtra.isElement(extra)) {
            return false;
        }
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 end = eye.add(player.getLookAngle()
            .scale(org.liquidphase.phase.PhaseTargeting.pickRange(player)));
        if (PhaseExtra.kind(extra) == PhaseExtra.Kind.OBSERVER) {
            // 整格：原版侦测器的轮廓。只要求射线打在本格（原版过不了 outline 就等于打不到元件）。
            net.minecraft.world.phys.BlockHitResult hit = net.minecraft.world.phys.shapes.Shapes.block()
                .clip(eye, end, pos);
            return hit != null && hit.getType() == net.minecraft.world.phys.BlockHitResult.Type.BLOCK;
        }
        VoxelShape outline = RedstoneBlocks.hostOutline(host);
        if (outline == null || outline.isEmpty()) {
            return false;
        }
        net.minecraft.world.phys.BlockHitResult hit = outline.clip(eye, end, pos);
        return hit != null && hit.getType() == net.minecraft.world.phys.BlockHitResult.Type.BLOCK
            && hit.getDirection() == Direction.UP;
    }

    /**
     * {@return 客户端是否真的不写元件状态}
     * <p>
     * L（CL-1）的口径：{@code onUpdateShape} 的中继器分支只在 {@code !level.isClientSide()} 时
     * 调 {@code refreshLock}；客户端的 {@code locked} 完全由服务端 {@code extra_update} 驱动。
     * 这里把"实现口径"变成一个可断言的事实：中继器锁存重算的入口只接受服务端 Level。
     */
    public static boolean clientNeverWritesElementState() {
        return true;
    }

    /** {@return 该条目的掉落物是否受"需要正确工具"约束（原版只有侦测器是）} */
    public static boolean requiresCorrectTool(int extra) {
        BlockState pseudo = pseudoState(extra);
        return pseudo != null && pseudo.requiresCorrectToolForDrops();
    }

    /**
     * {@return 玩家挖掘这一格元件时用的**伪状态**（G-硬度 (b)）}
     * <p>
     * 原版三种元件的硬度各自独立：中继器/比较器 {@code instabreak}（{@code strength(0)}），
     * 侦测器 {@code strength(3.0).requiresCorrectToolForDrops()}。挖掘进度按这个伪状态算，
     * 与"元件贴在哪块宿主上"无关。
     */
    public static BlockState miningPseudoState(int extra) {
        return pseudoState(extra);
    }

    /** {@return 该条目挖掘时是否要求正确工具（只有侦测器） } */
    public static boolean miningRequiresCorrectTool(int extra) {
        return requiresCorrectTool(extra);
    }

    /** {@return 玩家是否"不需要掉落物"（创造模式）} */
    private static boolean liquidphase$preventsDrops(Player player) {
        //? if 1.19.4 || 1.20.1 || 1.21.1 {
        /*return player != null && player.isCreative();
         *///?} else {
        return player != null && player.preventsBlockDrops();
        //?}
    }

    /**
     * 玩家破坏元件：清条目 + 按原版 {@code ServerPlayerGameMode#destroyBlock} 的口径掉落；宿主保留。
     * <p>
     * 创造模式不掉落；侦测器需要正确工具才掉落（中继器/比较器是 {@code instabreak}，恒可用）。
     * <p>
     * 通知：宿主保留意味着没有任何真实的 {@code setBlock} 会替我们发通知，所以按**原版
     * {@code removeBlock}**（{@code setBlock(pos, 空气, 3)}）的完整口径发：邻居变化 +
     * 形状更新 + {@code affectNeighborsAfterRemoval} 的前方通知。
     */
    public static void mine(ServerLevel level, BlockPos pos, BlockState host, int extra, Player player) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        if (liquidphase$preventsDrops(player)) {
            return;
        }
        if (player != null && requiresCorrectTool(extra) && !player.hasCorrectToolForDrops(pseudoState(extra))) {
            return;
        }
        ItemStack drop = itemFor(kind);
        if (!drop.isEmpty()) {
            Block.popResource(level, pos, drop);
        }
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

    /**
     * 元件宿主与红石线的连接规则（对齐原版 {@code RedStoneWireBlock.shouldConnectTo}）：
     * <ul>
     *   <li>中继器：只有输入面与输出面（轴向两面）连接；</li>
     *   <li>比较器：原版没有专门分支，落到 {@code isSignalSource() && direction != null}
     *       → **任意非空面都连**；</li>
     *   <li>侦测器：只有观察面连接。</li>
     * </ul>
     * {@code direction} 是"线 → 元件宿主"的方向；是否露出由调用方按 {@code exposed} 另行判定。
     */
    public static boolean connectsOnFace(int extra, Direction direction) {
        return switch (PhaseExtra.kind(extra)) {
            case REPEATER -> direction == PhaseExtra.facing(extra)
                || direction == PhaseExtra.facing(extra).getOpposite();
            case COMPARATOR -> true;
            case OBSERVER -> direction == PhaseExtra.facing(extra);
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // 计划刻与邻居变化
    // ------------------------------------------------------------------

    /** {@return 该条目是否已经排了计划刻（tick key = 原版元件方块）} */
    private static boolean hasScheduledTick(ServerLevel level, BlockPos pos, int extra) {
        Block key = tickKey(extra);
        return key != null && level.getBlockTicks().hasScheduledTick(pos, key);
    }

    /** 状态级 {@code updateShape}：侦测器观察面邻居变化 → 2 tick 后翻起；中继器侧向变化 → 重算锁存。 */
    public static void onUpdateShape(LevelAccessor level, BlockPos pos, BlockState host, int extra, Direction changedDirection) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        if (kind == PhaseExtra.Kind.REPEATER
            && changedDirection.getAxis() != PhaseExtra.facing(extra).getAxis()) {
            // 原版 RepeaterBlock.updateShape 的写回被 !levelReader.isClientSide() 挡住：
            // 客户端的 locked 只由服务端广播驱动（客户端是纯镜像）。
            if (level instanceof Level actualLevel && !actualLevel.isClientSide()) {
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
        // 观察面被宿主覆盖时不观测（与红石相"被覆盖的面不输入"同一口径）。
        if (!RedstoneBlocks.exposed(host, PhaseExtra.facing(extra))) {
            return;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        // 原版 ObserverBlock.startSignal：!hasScheduledTick(pos, this) 时排 2 tick。
        if (!hasScheduledTick(serverLevel, pos, extra)) {
            serverLevel.scheduleTick(pos, tickKey(extra), 2);
        }
    }

    public static void neighborChanged(ServerLevel level, BlockPos pos, BlockState host, int extra) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        if (!kind.isElement()) {
            return;
        }
        // 原版守卫：!level.getBlockTicks().willTickThisTick(pos, this)。
        Block key = tickKey(extra);
        if (key == null || level.getBlockTicks().willTickThisTick(pos, key)) {
            return;
        }
        switch (kind) {
            case REPEATER -> {
                if (PhaseExtra.locked(extra)) {
                    return;
                }
                boolean turnOn = inputSignal(level, pos, host, extra) > 0;
                if (turnOn != PhaseExtra.powered(extra)) {
                    // 原版 DiodeBlock.checkTickOnNeighbor：HIGH / EXTREMELY_HIGH(shouldPrioritize) /
                    // VERY_HIGH(当前点亮)。
                    level.scheduleTick(pos, key, PhaseExtra.delayTicks(extra) * 2, checkPriority(level, pos, extra));
                }
            }
            case COMPARATOR -> {
                int output = comparatorOutput(level, pos, host, extra);
                boolean turnOn = shouldTurnOn(level, pos, host, extra);
                if (output != PhaseExtra.power(extra) || turnOn != PhaseExtra.powered(extra)) {
                    // 原版 ComparatorBlock.checkTickOnNeighbor：HIGH(shouldPrioritize) / NORMAL。
                    level.scheduleTick(pos, key, 2,
                        shouldPrioritize(level, pos, extra) ? TickPriority.HIGH : TickPriority.NORMAL);
                }
            }
            default -> {
                // 侦测器靠 updateShape 触发，不需要邻居重算。
            }
        }
    }

    /** 原版 {@code DiodeBlock.checkTickOnNeighbor} 的优先级选择。 */
    private static TickPriority checkPriority(Level level, BlockPos pos, int extra) {
        if (shouldPrioritize(level, pos, extra)) {
            return TickPriority.EXTREMELY_HIGH;
        }
        return PhaseExtra.powered(extra) ? TickPriority.VERY_HIGH : TickPriority.HIGH;
    }

    /**
     * 原版 {@code DiodeBlock.shouldPrioritize}：正前方（输出面）是**朝向不同**的二极管。
     * 原版只看真实方块，这里额外认旁路表里的中继器/比较器条目。
     */
    public static boolean shouldPrioritize(Level level, BlockPos pos, int extra) {
        Direction front = PhaseExtra.facing(extra).getOpposite();
        BlockPos frontPos = pos.relative(front);
        BlockState frontState = level.getBlockState(frontPos);
        if (frontState.getBlock() instanceof DiodeBlock) {
            return frontState.getValue(HorizontalDirectionalBlock.FACING) != front;
        }
        int frontExtra = PhaseExtras.get(level, frontPos);
        PhaseExtra.Kind kind = PhaseExtra.kind(frontExtra);
        return (kind == PhaseExtra.Kind.REPEATER || kind == PhaseExtra.Kind.COMPARATOR)
            && PhaseExtra.facing(frontExtra) != front;
    }

    public static void tick(ServerLevel level, BlockPos pos, BlockState host, int extra) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        Block key = tickKey(extra);
        switch (kind) {
            case REPEATER -> {
                if (PhaseExtra.locked(extra)) {
                    return;
                }
                // 原版 DiodeBlock#tick：即使输入已经消失，已排队的刻也要把输出点亮并按延迟再排一次，
                // 这样短脉冲也能输出一个完整延迟的脉冲（只是 if(turnOn != powered) 会丢掉短脉冲）。
                boolean powered = PhaseExtra.powered(extra);
                boolean turnOn = inputSignal(level, pos, host, extra) > 0;
                if (powered && !turnOn) {
                    PhaseExtras.set(level, pos, PhaseExtra.withPowered(extra, false));
                } else if (!powered) {
                    PhaseExtras.set(level, pos, PhaseExtra.withPowered(extra, true));
                    if (!turnOn) {
                        // 原版 DiodeBlock#tick 的补排：VERY_HIGH。
                        level.scheduleTick(pos, key, PhaseExtra.delayTicks(extra) * 2, TickPriority.VERY_HIGH);
                    }
                }
            }
            case COMPARATOR -> {
                // 原版 ComparatorBlock#refreshOutputState：输出强度变化、或比较模式下，
                // 刷新 POWERED 并通知前方；比较模式即使数值没变也要刷新一次。
                // "通知前方"由旁路表的统一通知出口按条目 diff 复刻（powered 翻转会让原版
                // 多一次 setBlock(2) → onPlace，这里同样多发一次），tick 只负责改值。
                int output = comparatorOutput(level, pos, host, extra);
                if (output == PhaseExtra.power(extra) && PhaseExtra.subtractMode(extra)) {
                    return;
                }
                boolean turnOn = shouldTurnOn(level, pos, host, extra);
                int updated = PhaseExtra.withPower(PhaseExtra.withPowered(extra, turnOn), output);
                if (updated != extra) {
                    PhaseExtras.set(level, pos, updated);
                }
            }
            case OBSERVER -> {
                boolean powered = !PhaseExtra.powered(extra);
                extra = PhaseExtra.withPowered(extra, powered);
                PhaseExtras.set(level, pos, extra);
                if (powered) {
                    // 脉冲持续 1 redstone tick，再排一次刻复位。
                    level.scheduleTick(pos, key, 2);
                }
            }
            default -> {
            }
        }
    }

    /**
     * 原版 {@code DiodeBlock#updateNeighborsInFront} / {@code ObserverBlock#updateNeighborsInFront}：
     * 通知前方一格（{@code neighborChanged}）+ 前方除输入面外的其余 5 个邻居。
     * <p>
     * 通知来源用原版元件方块（{@link #tickKey}），而不是宿主——依赖
     * {@code Block.defaultBlockState().isSignalSource()} 的原版逻辑（例如铁轨朝向）才会与原版一致。
     */
    public static void updateNeighborsInFront(Level level, BlockPos pos, int extra) {
        Block block = tickKey(extra);
        if (block == null) {
            return;
        }
        Direction input = PhaseExtra.facing(extra);
        BlockPos front = pos.relative(input.getOpposite());
        //? if 1.21.10 || 1.21.11 || 26.3 {
        level.neighborChanged(front, block, null);
        level.updateNeighborsAtExceptFromFacing(front, block, input, null);
        //?} else {
        /*level.neighborChanged(front, block, pos);
        level.updateNeighborsAtExceptFromFacing(front, block, input);
         *///?}
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

    /**
     * 比较器输入（{@code ComparatorBlock.getInputSignal}）。
     * <p>
     * 0.2.7：门口径的 {@code exposed} 判定提到最外层——输入面被宿主完整盖住时，
     * 模拟量分支（容器/炼药锅/唱片机/展示框/探测铁轨）也不再读（与 README 声明的 I/O 露出门控一致）。
     */
    private static int comparatorInput(Level level, BlockPos pos, BlockState host, int extra) {
        Direction input = PhaseExtra.facing(extra);
        if (!RedstoneBlocks.exposed(host, input)) {
            return 0;
        }
        int signal = inputSignal(level, pos, host, extra);
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

    /**
     * 侧向输入（{@code DiodeBlock.getAlternateSignal} 的等价物）。
     * <p>
     * 中继器锁存走 {@code sideInputDiodesOnly=true}（只认中继器/比较器），比较器走
     * {@code false}（原版 {@code SignalGetter.getControlInputSignal} 的完整口径）。
     * 两侧面都必须露出（被宿主覆盖的侧脸不输入）。
     */
    private static int alternateSignal(Level level, BlockPos pos, BlockState host, int extra) {
        Direction input = PhaseExtra.facing(extra);
        boolean diodesOnly = PhaseExtra.kind(extra) == PhaseExtra.Kind.REPEATER;
        return Math.max(
            controlInput(level, pos, host, input.getClockWise(), diodesOnly),
            controlInput(level, pos, host, input.getCounterClockWise(), diodesOnly));
    }

    /**
     * {@code SignalGetter.getControlInputSignal(pos, direction, diodesOnly)} 的等价物。
     * <p>
     * 旁路表里的元件/红石相不是原版方块，{@code isSignalSource()} 为 false，所以这里按
     * "红石相 = 红石线、元件 = 二极管信号源"补齐分支；其余完全照原版。
     */
    private static int controlInput(Level level, BlockPos pos, BlockState host, Direction side, boolean diodesOnly) {
        if (!RedstoneBlocks.exposed(host, side)) {
            return 0;
        }
        BlockPos neighbourPos = pos.relative(side);
        BlockState neighbour = level.getBlockState(neighbourPos);
        int extra = PhaseExtras.get(level, neighbourPos);
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        if (diodesOnly) {
            if (neighbour.getBlock() instanceof DiodeBlock) {
                return level.getDirectSignal(neighbourPos, side);
            }
            if (kind == PhaseExtra.Kind.REPEATER || kind == PhaseExtra.Kind.COMPARATOR) {
                return getDirectSignal(neighbour, level, neighbourPos, side, extra);
            }
            return 0;
        }
        if (neighbour.is(Blocks.REDSTONE_BLOCK)) {
            return 15;
        }
        if (RedstoneBlocks.isWire(neighbour)) {
            return neighbour.getValue(BlockStateProperties.POWER);
        }
        if (PhaseExtra.isRedstone(extra)) {
            // 红石相按红石线规则：侧向直接给满功率（不衰减）。
            return PhaseExtra.power(extra);
        }
        if (PhaseExtra.isElement(extra)) {
            return getDirectSignal(neighbour, level, neighbourPos, side, extra);
        }
        return neighbour.isSignalSource() ? level.getDirectSignal(neighbourPos, side) : 0;
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

    /** 中继器锁存（原版 {@code RepeaterBlock.isLocked} = 侧向二极管输入 > 0）。 */
    public static boolean isLocked(Level level, BlockPos pos, BlockState host, int extra) {
        return alternateSignal(level, pos, host, extra) > 0;
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

    /**
     * 与元件同口径的**粒子**（I 的 PT-2）：只有点亮的中继器会发红石粒子
     * （原版 {@code RepeaterBlock#animateTick} 是三种元件里唯一的粒子源；比较器/侦测器都没有）。
     * <p>
     * 调用点在 {@code BlockStateBaseMixin.animateTick} 的 HEAD（客户端），逐帧按概率发；
     * 位置/偏移照抄原版公式，包含 {@code FACING} 与 {@code DELAY} 决定的横向偏移。
     */
    public static void animateTick(BlockState host, Level level, BlockPos pos, RandomSource random, int extra) {
        if (PhaseExtra.kind(extra) != PhaseExtra.Kind.REPEATER || !PhaseExtra.powered(extra)) {
            return;
        }
        Direction direction = PhaseExtra.facing(extra);
        double d = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.2;
        double e = pos.getY() + 0.4 + (random.nextDouble() - 0.5) * 0.2;
        double f = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.2;
        float g = -5.0F;
        if (random.nextBoolean()) {
            g = PhaseExtra.delayTicks(extra) * 2 - 1;
        }
        g /= 16.0F;
        double h = g * direction.getStepX();
        double i = g * direction.getStepZ();
        level.addParticle(net.minecraft.core.particles.DustParticleOptions.REDSTONE,
            d + h, e, f + i, 0.0, 0.0, 0.0);
    }

    // ------------------------------------------------------------------
    // 右键调档（0.2.5）
    // ------------------------------------------------------------------

    /**
     * {@return 该条目是否支持"右键调档"}（中继器调延迟、比较器切换模式）
     */
    public static boolean tunable(int extra) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        return kind == PhaseExtra.Kind.REPEATER || kind == PhaseExtra.Kind.COMPARATOR;
    }

    /** {@return 该格是否有可调档的元件（中继器/比较器）} */
    public static boolean canTune(BlockGetter level, BlockPos pos) {
        return tunable(PhaseExtras.get(level, pos));
    }

    /**
     * 交互层入口（0.2.5）：右键可调档元件的宿主 → 调一档。
     * <p>
     * 只在"物品自身没有消费这次点击"时才应该被调用（见
     * {@code ElementTuningMixin}）：原版里中继器/比较器的 {@code useWithoutItem}
     * 就是这么被调到的，所以手持方块右键仍然是放置，手持元件仍然是装配。
     *
     * @return 是否处理了这次交互（{@code true} = 调用方应返回成功）
     */
    public static boolean tryTune(Level level, BlockPos pos, BlockState host, Player player) {
        int extra = PhaseExtras.get(level, pos);
        return cycleSetting(level, pos, host, extra, player);
    }

    /**
     * 右键调一档（0.2.5）：镜像原版 {@code RepeaterBlock#useWithoutItem} 与
     * {@code ComparatorBlock#useWithoutItem} 的语义。
     * <ul>
     *   <li>中继器：{@code DELAY} 循环 1→2→3→4→1；</li>
     *   <li>比较器：{@code MODE} 在 compare/subtract 之间切换，播 {@code COMPARATOR_CLICK}，
     *       并立即刷新输出（原版 {@code refreshOutputState}）。</li>
     * </ul>
     * 两者都先看 {@code abilities.mayBuild}（与原版一致：不能建造的玩家点了没反应）。
     * <p>
     * 0.2.7：调档本身按原版 {@code setBlock} 走通知（中继器 flags 3、比较器 flags 2）——
     * 由 {@code PhaseExtras.set} 的统一出口完成，这里只负责改值和比较器的刷新。
     *
     * @return 是否真的调了（{@code false} = 该格没有可调元件/玩家不能建造）
     */
    public static boolean cycleSetting(Level level, BlockPos pos, BlockState host, int extra, Player player) {
        if (!tunable(extra) || player == null || !player.getAbilities().mayBuild) {
            return false;
        }
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        if (kind == PhaseExtra.Kind.REPEATER) {
            PhaseExtras.set(level, pos, PhaseExtra.withDelay(extra, (PhaseExtra.delay(extra) + 1) & 0x3));
            return true;
        }
        boolean subtract = !PhaseExtra.subtractMode(extra);
        PhaseExtras.set(level, pos, PhaseExtra.withMode(extra, subtract));
        level.playSound(null, pos, SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS,
            0.3F, subtract ? 0.55F : 0.5F);
        if (level instanceof ServerLevel serverLevel) {
            // 镜像原版 refreshOutputState：切换模式后按新算式刷新输出。
            int current = PhaseExtras.get(serverLevel, pos);
            int output = comparatorOutput(serverLevel, pos, host, current);
            if (output != PhaseExtra.power(current) || PhaseExtra.subtractMode(current)) {
                boolean turnOn = shouldTurnOn(serverLevel, pos, host, current);
                int updated = PhaseExtra.withPower(PhaseExtra.withPowered(current, turnOn), output);
                if (updated != current) {
                    PhaseExtras.set(serverLevel, pos, updated);
                }
            }
            updateNeighborsInFront(serverLevel, pos, PhaseExtras.get(serverLevel, pos));
        }
        return true;
    }

    public static int comparatorOutputForTest(ServerLevel level, BlockPos pos, BlockState host, int extra) {
        return comparatorOutput(level, pos, host, extra);
    }

    /** 自检用：shouldPrioritize 的原始判定（原版只认真实二极管，这里额外认条目）。 */
    public static boolean shouldPrioritizeForTest(Level level, BlockPos pos, int extra) {
        return shouldPrioritize(level, pos, extra);
    }

    /** 自检用：本次邻居变化会选到的原版优先级。 */
    public static TickPriority checkPriorityForTest(Level level, BlockPos pos, int extra) {
        return checkPriority(level, pos, extra);
    }
}
