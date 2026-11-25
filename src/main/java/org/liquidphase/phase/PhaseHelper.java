package org.liquidphase.phase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluids;
//? if 1.21.11 || 26.3 {
import net.minecraft.world.attribute.EnvironmentAttributes;
//?}

/**
 * 相系统的核心工具类：相查询、充入/收走、破坏保留的实体化、凝固/冻结/融化/霜裂推进、火蔓延。
 * 所有 mixin 与接口默认方法都委托到这里；版本差异（签名/蒸发判定）用 stonecutter 分支处理。
 */
public final class PhaseHelper {
    /**
     * 填充相属性：取值见 {@link Phase}；注入到全部可含水方块（PhaseCapable）。
     * <p>
     * 属性名 0.1.15 起为 {@code filling}（填充相口径；0.1.14 及以前叫 {@code phase}，
     * 属性名变更会使旧存档的相丢失，与移除 mod 同口径）。Java 侧沿用 Phase 命名。
     */
    public static final EnumProperty<Phase> FILLING = EnumProperty.create("filling", Phase.class);

    /** 原版 LiquidBlock.shouldSpreadLiquid 检查水的方向组（DOWN,SOUTH,NORTH,EAST,WEST，取 opposite 后即 UP+四水平向）。 */
    private static final Direction[] POSSIBLE_FLOW_DIRECTIONS = {Direction.DOWN, Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST};

    private PhaseHelper() {
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public static boolean hasPhaseProperty(BlockState state) {
        return state.hasProperty(FILLING);
    }

    public static Phase getPhase(BlockState state) {
        return state.hasProperty(FILLING) ? state.getValue(FILLING) : Phase.NONE;
    }

    public static boolean isLava(BlockState state) {
        return getPhase(state) == Phase.LAVA;
    }

    public static boolean isSolidPhase(BlockState state) {
        return getPhase(state).isSolid();
    }

    /**
     * 带相写入；水相相属性互斥（waterlogged=true ⟺ phase=none）。
     * <p>
     * 没有 phase 属性的状态原样返回：直接 setValue 会抛 IllegalArgumentException。
     */
    public static BlockState withPhase(BlockState state, Phase phase) {
        if (!state.hasProperty(FILLING)) {
            return state;
        }
        BlockState result = state.setValue(FILLING, phase);
        if (result.hasProperty(BlockStateProperties.WATERLOGGED)) {
            result = result.setValue(BlockStateProperties.WATERLOGGED, phase == Phase.NONE && result.getValue(BlockStateProperties.WATERLOGGED));
        }
        return result;
    }

    /**
     * 真正的"写相"入口：清掉同格的旁路条目（同格只表达一个相），再写方块状态。
     * <p>
     * 所有会改 {@code filling} 的路径都必须走这里，否则旁路表与状态会漂移
     * （例如含红石相/元件的格子被熔岩覆盖后，表里还留着旧条目）。
     */
    public static BlockState setPhase(LevelAccessor level, BlockPos pos, BlockState state, Phase phase, int flags) {
        if (phase != Phase.NONE) {
            // 这次 setBlock 随后就会把这一格换状态：条目清理按"方块写入流程内部"处理，
            // 原版的邻居变化与形状更新由那次 setBlock 自己发（含 affectNeighborsAfterRemoval）。
            PhaseExtras.set(level, pos, PhaseExtra.EMPTY, PhaseExtras.Notify.INSIDE_BLOCK_WRITE);
        }
        BlockState target = withPhase(state, phase);
        level.setBlock(pos, target, flags);
        return target;
    }

    /**
     * {@return 该格能否写入红石/元件条目}（同格只表达一个相）
     * <p>
     * 现有填充相可替换（空/含水/熔岩）时放行——写红石相会把材料相清回 {@code NONE}；
     * 冰/霜冰/黑曜石相拒绝（与 0.1.17 的互斥语义一致）。
     */
    public static boolean canAcceptExtra(BlockGetter level, BlockPos pos, BlockState state) {
        if (!PhaseExtra.isEmpty(PhaseExtras.get(level, pos))) {
            return false;
        }
        Phase current = getPhase(state);
        return current == Phase.NONE || current.isReplaceableFilling();
    }

    /** 融化：冰相 → 水相（waterlogged=true, phase=none）；蒸发维度直接去掉相。 */
    public static BlockState meltedState(BlockState state, boolean evaporates) {
        BlockState result = state.setValue(FILLING, Phase.NONE);
        if (!evaporates && result.hasProperty(BlockStateProperties.WATERLOGGED)) {
            result = result.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 充入（放置吸收 / 桶 / 发射器的公共入口）
    // ------------------------------------------------------------------

    /**
     * {@return 方块当前能否接受给定相}
     * <p>
     * 门控（0.1.17 起为"可替换填充相"规则，全吸收路径的唯一判定源）：
     * 现有填充相可替换 → 接受；不可替换 → 按不可放置处理。其中
     * <ul>
     *   <li>空格（{@code filling=none}、未含水）：无可替换物，接受（原有行为）；</li>
     *   <li>含水（{@code filling=none}、{@code waterlogged=true}）：水即现有填充相，可替换
     *       ——冰替换水=冻结、熔岩替换水、红石粉替换水等一律放行（0.1.16 及以前一律拒绝）；</li>
     *   <li>熔岩相：可替换（黑曜石/冰/红石粉等可覆盖熔岩相）；</li>
     *   <li>冰/霜冰/黑曜石/红石相：不可替换，拒绝（与 0.1.16 的互斥语义一致）。</li>
     * </ul>
     * 可替换集合由 {@link Phase#isReplaceableFilling()} 统一维护；水的流体容器路径
     * （水桶/水流写入）不走本门控，仍只进无相宿主——熔岩相遇水邻居走既有凝固逻辑。
     * <p>
     * 宿主本体充水能力探测照旧保留（决策点 D4：按原版充水的判定），但在**空宿主兄弟状态**
     * 上探测：含水/含熔岩相的宿主要用 {@code filling=none} 且未含水的同属性状态去问
     * {@code canPlaceLiquid(…, WATER)}，否则熔岩相状态会被 {@code PhaseCapable.canPlaceLiquid}
     * 的互斥分支误判为"宿主本体不接受水"。双层台阶等被原版自身覆写拒绝的照样拒绝，
     * 避免误杀树叶/格栅等整格方块。
     */
    public static boolean canAcceptPhase(BlockState state, BlockGetter level, BlockPos pos, Phase phase) {
        if (phase == Phase.NONE || !state.hasProperty(FILLING)) {
            return false;
        }
        // 同格只表达一个相：已经有红石/元件条目时不允许再写填充相。
        if (level != null && !PhaseExtra.isEmpty(PhaseExtras.get(level, pos))) {
            return false;
        }
        Phase current = state.getValue(FILLING);
        if (current != Phase.NONE && !current.isReplaceableFilling()) {
            return false;
        }
        if (state.getBlock() instanceof LiquidBlockContainer container && state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return canPlaceWaterProbe(container, level, pos, emptySibling(state));
        }
        return true;
    }

    /**
     * {@return 同状态剥掉材料填充相与含水标志后的版本（0.2.3 清含水）}
     * <p>
     * 写入红石/元件条目时对宿主状态用这个：一格只表达一个被含相，条目本身就是一个被含相，
     * 水不能再留在格内（含水 + 条目会变成两个被含相）。熔岩相同样清回 none；
     * 已是干净状态时返回同一实例（{@code setValue} 对未变化取值返回缓存实例）。
     */
    public static BlockState withoutFillingAndWater(BlockState state) {
        BlockState result = withPhase(state, Phase.NONE);
        if (result.hasProperty(BlockStateProperties.WATERLOGGED) && result.getValue(BlockStateProperties.WATERLOGGED)) {
            result = result.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return result;
    }

    /** {@return 同属性、无相且未含水的兄弟状态——宿主本体充水能力探测用} */
    private static BlockState emptySibling(BlockState state) {
        BlockState probe = withPhase(state, Phase.NONE);
        if (probe.hasProperty(BlockStateProperties.WATERLOGGED) && probe.getValue(BlockStateProperties.WATERLOGGED)) {
            probe = probe.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return probe;
    }

    /**
     * {@return 充水时"冲落"该格的红石/元件条目（0.2.5）}
     * <p>
     * 语义对齐原版：水会把红石粉、红石火把这类附着件冲掉，然后占住那一格。这里的处理是
     * 先按 {@link PhaseExtra#isWashable} 判定条目是否可冲落，可冲落则
     * <b>弹出对应物品并清掉条目</b>（红石相掉红石粉、元件掉对应元件物品，
     * 口径与活塞路径共用 {@link PhaseDrops#droppedItem}），然后调用方再正常充水。
     * <p>
     * 判定是实时的（读条目种类），所以以后新增"可冲落"的条目种类不用改这里。
     *
     * @return 是否真的冲落了东西（= 调用方可以继续充水）
     */
    public static boolean washOut(Level level, BlockPos pos) {
        int extra = PhaseExtras.get(level, pos);
        if (!PhaseExtra.isWashable(extra)) {
            return false;
        }
        ItemStack drop = PhaseDrops.droppedItem(getPhase(level.getBlockState(pos)), extra);
        // 水马上要占住这一格（调用方紧接着 setBlock）：邻居变化与形状更新由那次写入自己发，
        // 条目清理只补元件特有的前方通知。
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY, PhaseExtras.Notify.INSIDE_BLOCK_WRITE);
        if (level instanceof ServerLevel serverLevel && !drop.isEmpty()) {
            Block.popResource(serverLevel, pos, drop);
        }
        return true;
    }

    /** {@return 该格能否被水冲落（只看条目；材料相/水/熔岩不走这条路径）} */
    public static boolean canBeWashedOut(BlockGetter level, BlockPos pos) {
        return PhaseExtra.isWashable(PhaseExtras.get(level, pos));
    }

    /**
     * {@return 充入相是否成功；熔岩相会重排流体 tick 使内部熔岩流出}
     */
    public static boolean fillWithPhase(LevelAccessor level, BlockPos pos, BlockState state, Phase phase) {
        if (!canAcceptPhase(state, level, pos, phase)) {
            return false;
        }
        setPhase(level, pos, state, phase, Block.UPDATE_ALL);
        if (phase == Phase.LAVA) {
            level.scheduleTick(pos, Fluids.LAVA, Fluids.LAVA.getTickDelay(level));
        }
        return true;
    }

    /**
     * {@return 实体化：破坏宿主时相变成的真实方块状态（规划 §3.0.3）}
     */
    public static BlockState materializedPhase(BlockState state) {
        return switch (getPhase(state)) {
            case LAVA -> Fluids.LAVA.defaultFluidState().createLegacyBlock();
            case OBSIDIAN -> Blocks.OBSIDIAN.defaultBlockState();
            case ICE -> Blocks.ICE.defaultBlockState();
            case FROSTED_0 -> Blocks.FROSTED_ICE.defaultBlockState().setValue(BlockStateProperties.AGE_3, 0);
            case FROSTED_1 -> Blocks.FROSTED_ICE.defaultBlockState().setValue(BlockStateProperties.AGE_3, 1);
            case FROSTED_2 -> Blocks.FROSTED_ICE.defaultBlockState().setValue(BlockStateProperties.AGE_3, 2);
            case FROSTED_3 -> Blocks.FROSTED_ICE.defaultBlockState().setValue(BlockStateProperties.AGE_3, 3);
            default -> state;
        };
    }

    // ------------------------------------------------------------------
    // 凝固（熔岩相遇水 → 黑曜石相）
    // ------------------------------------------------------------------

    /**
     * 邻居变化时给熔岩相补上原版 {@code LiquidBlock.shouldSpreadLiquid} 的等价检查：
     * 下方是否灵魂土，UP+四水平向（原版同一组方向常量取 opposite）是否有水。
     * 有水 → 熔岩相凝固为黑曜石相 + 嘶声。灵魂土+蓝冰→玄武岩不做（决策点 D5）。
     */
    public static void solidifyIfWaterNearby(ServerLevel level, BlockPos pos, BlockState state) {
        if (getPhase(state) != Phase.LAVA) {
            return;
        }
        for (Direction direction : POSSIBLE_FLOW_DIRECTIONS) {
            if (level.getFluidState(pos.relative(direction.getOpposite())).is(Fluids.WATER)) {
                setPhase(level, pos, state, Phase.OBSIDIAN, Block.UPDATE_ALL);
                fizz(level, pos);
                return;
            }
        }
    }

    private static void fizz(Level level, BlockPos pos) {
        // 原版 LiquidBlock.fizz 的 levelEvent 1501。
        level.levelEvent(1501, pos, 0);
    }

    // ------------------------------------------------------------------
    // 冻结（含水相 → 含冰相，规划 §3.3）
    // ------------------------------------------------------------------

    /**
     * 对"水相宿主"的原版 {@code Biome.shouldFreeze} 镜像判定；满足则冻结成含冰相。
     * 通过 tickChunk/tickPrecipitation 里 shouldFreeze 调用点的 redirect 接入，原版行为不受影响。
     */
    public static boolean tryFreezeHost(ServerLevel level, Biome biome, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (getPhase(state) != Phase.NONE || !state.hasProperty(BlockStateProperties.WATERLOGGED) || !state.getValue(BlockStateProperties.WATERLOGGED)) {
            return false;
        }
        if (!shouldFreezeHost(level, biome, pos, state)) {
            return false;
        }
        setPhase(level, pos, state, Phase.ICE, Block.UPDATE_ALL);
        return true;
    }

    private static boolean shouldFreezeHost(LevelReader level, Biome biome, BlockPos pos, BlockState state) {
        if (warmEnoughToRain(biome, level, pos)) {
            return false;
        }
        //? if 1.21.10 || 1.21.11 || 26.3 {
        if (!level.isInsideBuildHeight(pos.getY())) {
            return false;
        }
        //?} else {
        /*if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
         *///?}
        if (level.getBrightness(LightLayer.BLOCK, pos) >= 10) {
            return false;
        }
        // 原版：四周不全是水才结冰；水相宿主按水计。
        return !(isWaterAt(level, pos.west()) && isWaterAt(level, pos.east())
            && isWaterAt(level, pos.north()) && isWaterAt(level, pos.south()));
    }

    private static boolean isWaterAt(LevelReader level, BlockPos pos) {
        if (level.isWaterAt(pos)) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        return state.hasProperty(FILLING)
            && state.getValue(FILLING) == Phase.NONE
            && state.hasProperty(BlockStateProperties.WATERLOGGED)
            && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    // ------------------------------------------------------------------
    // 融化（冰/霜冰相 → 水相）与霜裂推进
    // ------------------------------------------------------------------

    /** 随机刻融化（原版 IceBlock.randomTick 的光照条件）。 */
    public static void iceRandomTick(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (getPhase(state) != Phase.ICE) {
            return;
        }
        if (level.getBrightness(LightLayer.BLOCK, pos) > 11 - lightDampening(state, level, pos)) {
            melt(level, pos, state);
        }
    }

    /** 霜冰随机刻（1.19.4/1.20.1 的原版路径；1.21.1+ 原版用 onPlace+计划刻，我们两条都接）。 */
    public static void frostedRandomTick(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (!getPhase(state).isFrosted()) {
            return;
        }
        frostedProgress(level, pos, state, random);
    }

    /** 计划刻推进霜裂（1.21.1+ 原版路径）。 */
    public static void frostedScheduledTick(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (!getPhase(state).isFrosted()) {
            return;
        }
        frostedProgress(level, pos, state, random);
    }

    private static void frostedProgress(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        Phase phase = getPhase(state);
        if ((random.nextInt(3) == 0 || fewerFrostedNeighboursThan(level, pos, 4))
            && getMaxLocalRawBrightness(level, pos) > 11 - phase.frostedAge() - lightDampening(state, level, pos)
            && slightlyMelt(level, pos, state)) {
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (Direction direction : Direction.values()) {
                cursor.setWithOffset(pos, direction);
                BlockState neighbour = level.getBlockState(cursor);
                if (getPhase(neighbour).isFrosted()) {
                    level.scheduleTick(cursor.immutable(), neighbour.getBlock(), Mth.nextInt(random, 20, 40));
                }
            }
            return;
        }
        level.scheduleTick(pos, state.getBlock(), Mth.nextInt(random, 20, 40));
    }

    /** 原版 FrostedIceBlock.slightlyMelt：裂纹推进一格，到底则融化。 */
    private static boolean slightlyMelt(ServerLevel level, BlockPos pos, BlockState state) {
        Phase phase = getPhase(state);
        if (phase.frostedAge() < Phase.FROSTED_MAX_AGE) {
            // 原版 FrostedIceBlock 用 flag 2（UPDATE_CLIENTS，发客户端不排邻居更新）。
            setPhase(level, pos, state, phase.advanceFrost(), 2);
            return true;
        }
        melt(level, pos, state);
        return false;
    }

    /** 融化为水相（下界/蒸发维度直接消失，规划 §3.0.6）。 */
    public static void melt(ServerLevel level, BlockPos pos, BlockState state) {
        boolean evaporates = waterEvaporates(level, pos);
        // 下面的 setBlock 会自己发邻居变化与形状更新。
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY, PhaseExtras.Notify.INSIDE_BLOCK_WRITE);
        level.setBlock(pos, meltedState(state, evaporates), Block.UPDATE_ALL);
        if (!evaporates) {
            // 对齐原版 IceBlock.melt 的邻居通知（1.21.10+ 第三参为 Orientation，旧版为 fromPos）。
            //? if 1.21.10 || 1.21.11 || 26.3 {
            level.neighborChanged(pos, meltedState(state, false).getBlock(), null);
            //?} else {
            /*level.neighborChanged(pos, meltedState(state, false).getBlock(), pos);
             *///?}
        }
    }

    /** 原版 FrostedIceBlock.neighborChanged：相邻的霜冰方块变化且周围霜冰少于 2 时直接融化。 */
    public static void frostedNeighborChanged(Level level, BlockPos pos, BlockState state, Block neighborBlock) {
        if (!getPhase(state).isFrosted()) {
            return;
        }
        if (neighborBlock == Blocks.FROSTED_ICE && fewerFrostedNeighboursThan(level, pos, 2) && level instanceof ServerLevel serverLevel) {
            melt(serverLevel, pos, state);
        }
    }

    private static boolean fewerFrostedNeighboursThan(BlockGetter level, BlockPos pos, int limit) {
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            cursor.setWithOffset(pos, direction);
            if (getPhase(level.getBlockState(cursor)).isFrosted() && ++count >= limit) {
                return false;
            }
        }
        return true;
    }

    /** 挂在 onPlace：霜冰相放置/实体化后排一次推进刻（1.21.1+ 原版行为）。 */
    public static void scheduleFrostTickIfNeeded(LevelAccessor level, BlockPos pos, BlockState state) {
        if (getPhase(state).isFrosted() && level instanceof ServerLevel serverLevel) {
            serverLevel.scheduleTick(pos, state.getBlock(), Mth.nextInt(serverLevel.getRandom(), 60, 120));
        }
    }

    // ------------------------------------------------------------------
    // 环境与光照的版本差异
    // ------------------------------------------------------------------

    /** 下界（水蒸发维度）判定：≤1.21.10 用 dimensionType().ultraWarm()，1.21.11+/26.3 用环境属性。 */
    public static boolean waterEvaporates(Level level, BlockPos pos) {
        //? if 1.21.11 || 26.3 {
        return level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos);
        //?} else {
        /*return level.dimensionType().ultraWarm();
         *///?}
    }

    /** 光照削弱值：≤1.21.1 是 getLightBlock(level,pos)（即时计算），1.21.10/11 起走状态缓存无参版，26.3 改名 getLightDampening。 */
    private static int lightDampening(BlockState state, LevelReader level, BlockPos pos) {
        //? if 1.21.10 || 1.21.11 {
        return state.getLightBlock();
        //?} elif 26.3 {
        /*return state.getLightDampening();
         *///?} else {
        /*return state.getLightBlock(level, pos);
         *///?}
    }

    /** 1.21.11+ 霜冰用 getBrightness，旧版用 getMaxLocalRawBrightness（原版分支的镜像）。 */
    private static int getMaxLocalRawBrightness(ServerLevel level, BlockPos pos) {
        //? if 1.21.11 || 26.3 {
        return level.dimension() == Level.END
            ? level.getBrightness(LightLayer.BLOCK, pos)
            : level.getMaxLocalRawBrightness(pos);
        //?} else {
        /*return level.getMaxLocalRawBrightness(pos);
         *///?}
    }

    private static boolean warmEnoughToRain(Biome biome, LevelReader level, BlockPos pos) {
        //? if 1.21.10 || 1.21.11 || 26.3 {
        return biome.warmEnoughToRain(pos, level.getSeaLevel());
        //?} else {
        /*return biome.warmEnoughToRain(pos);
         *///?}
    }

    // ------------------------------------------------------------------
    // 容器接口的公共判定（PhaseCapable 与交互 mixin 共用）
    // ------------------------------------------------------------------

    /** 探测原版对水的接受度（决策点 D4 的"按原版充水的判定"）。签名按版本分支。 */
    public static boolean canPlaceWaterProbe(LiquidBlockContainer container, BlockGetter level, BlockPos pos, BlockState state) {
        //? if 1.19.4 || 1.20.1 {
        /*return container.canPlaceLiquid(level, pos, state, Fluids.WATER);
         *///?} else if 1.21.1 {
        /*return container.canPlaceLiquid(null, level, pos, state, Fluids.WATER);
         *///?} else {
        return container.canPlaceLiquid(null, level, pos, state, Fluids.WATER);
        //?}
    }

    /**
     * 火蔓延（决策点 D6：做）：熔岩相的随机刻直接委托原版熔岩流体的 randomTick，
     * 原版的火规则（gamerule、可燃物判定、版本差异）全部免费继承。
     */
    public static void lavaRandomTick(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (getPhase(state) == Phase.LAVA) {
            state.getFluidState().randomTick(level, pos, random);
        }
    }

    /** 排流体 tick：熔岩相邻居变化后让内部熔岩继续按源流动（fork 的 NeighborUpdate 语义）。 */
    public static void scheduleLavaFlowTick(LevelAccessor level, BlockPos pos, BlockState state) {
        if (getPhase(state) == Phase.LAVA && level instanceof ServerLevel serverLevel) {
            serverLevel.scheduleTick(pos, Fluids.LAVA, Fluids.LAVA.getTickDelay(serverLevel));
        }
    }
}
