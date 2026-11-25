package org.liquidphase.phase;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 相的瞄准与挖掘（决策点 D7/D8，规划 §3.2/§5.1）：
 * <ul>
 *   <li>固体相（冰/霜冰/黑曜石）碰撞 = 整格实心（D7，与"相填满宿主空隙"的视觉一致）；
 *       熔岩相不加碰撞（内部是流体）。</li>
 *   <li>瞄准射线把"相可见区域"（整格减宿主轮廓）合并进 OUTLINE 形状：准星可以指到相露出的部分。</li>
 *   <li>命中点落在相区域 = "相目标"：挖掘进度与破坏掉落按相材质的伪状态计算
 *       （黑曜石需钻石镐、冰非精准采集化水、霜冰无掉落物化水），破坏后宿主保留。</li>
 * </ul>
 * 客户端与服务端共用同一套形状/射线判定，两侧结论一致。
 */
public final class PhaseTargeting {

    private static final Map<BlockState, VoxelShape> PHASE_VISIBLE_CACHE = new Reference2ObjectOpenHashMap<>();

    private PhaseTargeting() {
    }

    /** {@return 相可见区域 = 整格减宿主轮廓（按状态缓存；状态为单例，键安全）} */
    public static VoxelShape phaseVisibleShape(BlockState state) {
        return PHASE_VISIBLE_CACHE.computeIfAbsent(state,
            s -> Shapes.joinUnoptimized(Shapes.block(), hostOutline(s), BooleanOp.ONLY_FIRST).optimize());
    }

    /**
     * {@return 宿主轮廓（相=none 的同一状态）}
     * 形状表型的宿主（栅栏/墙/玻璃板等）按"构造时的状态集合"建表，含相状态查不到会返回 null；
     * 一律用 phase=none 的兄弟状态查询，既避开 null 也保证相区域 = 宿主真正留出的空隙。
     */
    public static VoxelShape hostOutline(BlockState state) {
        BlockState host = PhaseHelper.withPhase(state, Phase.NONE);
        return host.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
    }

    /** {@return 合并瞄准形状 = 宿主轮廓 ∪ 相可见区域；无相时返回 null（调用方直接用原形状）} */
    public static VoxelShape mergedOutline(net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                           BlockState state, VoxelShape vanilla) {
        if (!isPhaseMineable(level, pos, state)) {
            return null;
        }
        VoxelShape phase = phaseVisibleShape(state);
        if (phase.isEmpty()) {
            return null;
        }
        // vanilla 可能来自含相状态（形状表查不到 → null/空），此时用宿主轮廓兜底。
        VoxelShape host = (vanilla == null || vanilla.isEmpty()) ? hostOutline(state) : vanilla;
        return Shapes.or(host, phase);
    }

    /**
     * {@return 玩家是否正瞄准该格的"相区域"（而不是宿主本体）}
     * <p>
     * 判定口径 = 分别对**宿主轮廓**与**相区域**做射线命中，比较谁更近。
     * 不能用合并轮廓（宿主∪相 = 整格）来比：相区域的外表面本就落在方块边界上，
     * 与合并轮廓的命中点重合，那样比较会恒成立，导致任何瞄准都算挖相，
     * 挖掘速度永远按相材质（黑曜石没有钻石镐几乎挖不动）。
     * <p>
     * 宿主轮廓用 phase=none 兄弟状态查询：形状表型宿主对含相状态返回 null/空，
     * 直接用会把宿主当成"没有实体"，同样退化成恒选中相。
     */
    public static boolean isPhaseTarget(Player player, Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isPhaseMineable(level, pos, state)) {
            return false;
        }
        VoxelShape phase = phaseVisibleShape(state);
        if (phase.isEmpty()) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(pickRange(player)));
        BlockHitResult merged = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (merged.getType() != BlockHitResult.Type.BLOCK || !merged.getBlockPos().equals(pos)) {
            return false;
        }
        BlockHitResult phaseHit = phase.clip(eye, end, pos);
        // VoxelShape.clip 未命中时返回 null（不是 MISS 结果）：射线只碰到宿主本体时就是 null → 宿主目标。
        if (phaseHit == null || phaseHit.getType() != BlockHitResult.Type.BLOCK) {
            return false;
        }
        VoxelShape host = hostOutline(state);
        BlockHitResult hostHit = host.isEmpty() ? null : host.clip(eye, end, pos);
        if (hostHit == null || hostHit.getType() != BlockHitResult.Type.BLOCK) {
            // 射线根本没碰到宿主本体（只可能碰到相）→ 相目标。
            return true;
        }
        // 相命中必须严格更早（留 1e-7 容差处理共面命中）才算瞄准相。
        return phaseHit.getLocation().distanceToSqr(eye) < hostHit.getLocation().distanceToSqr(eye) - 1.0E-7;
    }

    /** {@return 固相（黑曜石/冰/霜冰）或红石相是否可被直接瞄准挖掘（D8）} */
    public static boolean isPhaseMineable(net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state) {
        return PhaseHelper.isSolidPhase(state)
            || org.liquidphase.phase.PhaseExtra.isRedstone(org.liquidphase.phase.PhaseExtras.get(level, pos));
    }

    /** {@return 该格是否带元件相（元件先于宿主被破坏）} */
    public static boolean isElementMineable(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        return org.liquidphase.phase.PhaseExtra.isElement(org.liquidphase.phase.PhaseExtras.get(level, pos));
    }

    /**
     * {@return 玩家是否正瞄准该格的**元件**（G-瞄准 (c)，用户口径：宿主任意朝上的面都算元件）}
     * <p>
     * 规则（见 {@code ElementBlocks#isTargeted}）：
     * <ul>
     *   <li>侦测器：整格命中即算（原版侦测器轮廓就是整格），**始终先被瞄准**；</li>
     *   <li>中继器/比较器：命中宿主形状且命中面朝上才算；侧面/底面算宿主。</li>
     * </ul>
     * 与 {@link #isPhaseTarget} 互斥：元件比固相/红石相更"外层"，所以先判元件。
     */
    public static boolean isElementTarget(Player player, Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        int extra = org.liquidphase.phase.PhaseExtras.get(level, pos);
        if (!org.liquidphase.phase.PhaseExtra.isElement(extra)) {
            return false;
        }
        // 元件总是"从上方看"，与固相/红石相的观察者视角口径一致；射线端点用同一交互距离。
        return org.liquidphase.element.ElementBlocks.isTargeted(player, level, pos, state, extra);
    }

    /** {@return 相对应的伪状态（用于挖掘速度与掉落判定）} */
    public static BlockState pseudoState(BlockState state, int extra) {
        if (org.liquidphase.phase.PhaseExtra.isRedstone(extra)) {
            return net.minecraft.world.level.block.Blocks.REDSTONE_WIRE.defaultBlockState();
        }
        Phase phase = PhaseHelper.getPhase(state);
        return switch (phase) {
            case OBSIDIAN -> Blocks.OBSIDIAN.defaultBlockState();
            case ICE -> Blocks.ICE.defaultBlockState();
            case FROSTED_0 -> Blocks.FROSTED_ICE.defaultBlockState().setValue(BlockStateProperties.AGE_3, 0);
            case FROSTED_1 -> Blocks.FROSTED_ICE.defaultBlockState().setValue(BlockStateProperties.AGE_3, 1);
            case FROSTED_2 -> Blocks.FROSTED_ICE.defaultBlockState().setValue(BlockStateProperties.AGE_3, 2);
            case FROSTED_3 -> Blocks.FROSTED_ICE.defaultBlockState().setValue(BlockStateProperties.AGE_3, 3);
            default -> state;
        };
    }

    private static boolean hasSilkTouch(Player player) {
        ItemStack stack = player.getMainHandItem();
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        return EnchantmentHelper.getItemEnchantmentLevel(
            player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .get(Enchantments.SILK_TOUCH).orElseThrow(), stack) > 0;
        //?} else {
        /*return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0;
         *///?}
    }

    /**
     * 直接挖掘固相（D8）：按伪状态的工具判定掉落，移除相、宿主保留。
     * 冰/霜冰非精准采集时化回水相（对齐原版冰的破坏行为）；霜冰无物品形态，精准采集也不掉落。
     */
    public static void minePhase(Player player, Level level, BlockPos pos, BlockState state) {
        int extra = org.liquidphase.phase.PhaseExtras.get(level, pos);
        if (org.liquidphase.phase.PhaseExtra.isElement(extra)) {
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                org.liquidphase.element.ElementBlocks.mine(serverLevel, pos, state, extra, player);
            }
            return;
        }
        if (org.liquidphase.phase.PhaseExtra.isRedstone(extra)) {
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                org.liquidphase.redstone.RedstoneBlocks.mineRedstonePhase(serverLevel, pos, state, player);
            }
            return;
        }
        Phase phase = PhaseHelper.getPhase(state);
        BlockState pseudo = pseudoState(state, extra);
        if (!player.isCreative() && phase == Phase.OBSIDIAN && player.hasCorrectToolForDrops(pseudo)) {
            pop(level, pos, new ItemStack(Items.OBSIDIAN));
        }
        if (!player.isCreative() && phase == Phase.ICE && hasSilkTouch(player)) {
            pop(level, pos, new ItemStack(Items.ICE));
        }
        if (!player.isCreative() && phase.isFrozen() && !hasSilkTouch(player)) {
            // 非精准采集：相化回水相（对齐原版冰）。
            level.setBlock(pos, PhaseHelper.meltedState(state, PhaseHelper.waterEvaporates(level, pos)), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
            return;
        }
        level.setBlock(pos, PhaseHelper.withPhase(state, Phase.NONE), Block.UPDATE_ALL);
        level.playSound(null, pos, pseudo.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static void pop(Level level, BlockPos pos, ItemStack stack) {
        level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
    }

    /** 交互距离（与 BucketItemUseMixin 一致）：1.21.1+ 用属性，旧版固定 5 格。 */
    public static double pickRange(Player player) {
        //? if 1.21.1 || 1.21.10 || 1.21.11 || 26.3 {
        return player.blockInteractionRange();
        //?} else {
        /*return 5.0D;
         *///?}
    }

    /** {@return 玩家是否够得着该格中心（服务端防作弊的粗校验）} */
    public static boolean withinRange(Player player, BlockPos pos) {
        double d = pickRange(player) + 1.0;
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= d * d;
    }

    /**
     * {@return 该格是否为"可用空桶挖掘的液体目标"：真实水/熔岩源、熔岩相宿主，或装了流体的炼药锅}
     * 炼药锅按方块显式判定（锅的 FluidState 为空）；细雪锅不算液体目标。
     */
    public static boolean isLiquidMineTarget(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (PhaseHelper.isLava(state)) {
            return true;
        }
        if (isFilledCauldron(state)) {
            return true;
        }
        FluidState fluid = level.getFluidState(pos);
        return fluid.isSource() && (fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA)) && state.getBlock() instanceof LiquidBlock;
    }

    /** {@return 是否装了水/熔岩的炼药锅（水锅要求满锅、熔岩锅总是满的）} */
    private static boolean isFilledCauldron(BlockState state) {
        if (state.getBlock() == net.minecraft.world.level.block.Blocks.WATER_CAULDRON) {
            // 满锅才能取（与桶装水一致）：未满的锅用空桶左键不再有反应。
            return state.getValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL)
                >= net.minecraft.world.level.block.LayeredCauldronBlock.MAX_FILL_LEVEL;
        }
        return state.getBlock() == net.minecraft.world.level.block.Blocks.LAVA_CAULDRON;
    }

    /**
     * 空桶挖掘液体目标：移除液体/熔岩相/锅中流体并掉落液体物品（功能三，§5.1）。
     * 炼药锅整锅一次拆空（与锅的装/倒粒度一致）。
     */
    public static void mineLiquid(Player player, Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        boolean cauldron = isFilledCauldron(state);
        boolean lava = PhaseHelper.isLava(state)
            || level.getFluidState(pos).is(Fluids.LAVA)
            || state.getBlock() == net.minecraft.world.level.block.Blocks.LAVA_CAULDRON;
        if (cauldron) {
            // 锅：整锅拆空，「锅本身」保留为外观的一部分，等价于把里面的流体挖走。
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
        } else if (PhaseHelper.isLava(state)) {
            level.setBlock(pos, PhaseHelper.withPhase(state, Phase.NONE), Block.UPDATE_ALL);
        } else {
            level.removeBlock(pos, false);
        }
        level.playSound(null, pos, lava ? SoundEvents.BUCKET_FILL_LAVA : SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (!player.isCreative()) {
            level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                new ItemStack(lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET)));
        }
    }

    /** {@return 玩家是否手持空桶（主手或副手）} */
    public static boolean holdingEmptyBucket(Player player) {
        return player.getMainHandItem().is(Items.BUCKET) || player.getOffhandItem().is(Items.BUCKET);
    }
}
