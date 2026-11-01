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
            s -> Shapes.joinUnoptimized(Shapes.block(),
                s.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty()),
                BooleanOp.ONLY_FIRST).optimize());
    }

    /** {@return 合并瞄准形状 = 宿主轮廓 ∪ 相可见区域；无相时返回 null（调用方直接用原形状）} */
    public static VoxelShape mergedOutline(BlockState state, VoxelShape vanilla) {
        if (!isPhaseMineable(state)) {
            return null;
        }
        VoxelShape phase = phaseVisibleShape(state);
        if (phase.isEmpty()) {
            return null;
        }
        return Shapes.or(vanilla, phase);
    }

    /**
     * {@return 玩家是否正瞄准该格的"相区域"（而不是宿主本体）}
     * 以与客户端准星相同的射线口径（OUTLINE + 交互距离）做合并命中与相区域命中，比较距离。
     */
    public static boolean isPhaseTarget(Player player, Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isPhaseMineable(state)) {
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
        if (phaseHit.getType() != BlockHitResult.Type.BLOCK) {
            return false;
        }
        return merged.getLocation().distanceToSqr(eye) >= phaseHit.getLocation().distanceToSqr(eye) - 1.0E-7;
    }

    /** {@return 固相（枚举相或红石相组合方块）是否可被直接瞄准挖掘（D8）} */
    public static boolean isPhaseMineable(BlockState state) {
        return PhaseHelper.isSolidPhase(state) || org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(state);
    }

    /** {@return 相对应的伪状态（用于挖掘速度与掉落判定）} */
    public static BlockState pseudoState(BlockState state) {
        if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(state)) {
            return net.minecraft.world.level.block.Blocks.REDSTONE_WIRE.defaultBlockState();
        }
        return switch (PhaseHelper.getPhase(state)) {
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
        if (org.liquidphase.redstone.RedstoneBlocks.isRedstonePhase(state)) {
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                org.liquidphase.redstone.RedstoneBlocks.mineRedstonePhase(serverLevel, pos, state);
            }
            return;
        }
        Phase phase = PhaseHelper.getPhase(state);
        BlockState pseudo = pseudoState(state);
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

    /** {@return 该格是否为"可用空桶挖掘的液体目标"：真实水/熔岩源，或熔岩相宿主} */
    public static boolean isLiquidMineTarget(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (PhaseHelper.isLava(state)) {
            return true;
        }
        FluidState fluid = level.getFluidState(pos);
        return fluid.isSource() && (fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA)) && state.getBlock() instanceof LiquidBlock;
    }

    /** 空桶挖掘液体目标：移除液体/熔岩相并掉落液体物品（功能三，§5.1）。 */
    public static void mineLiquid(Player player, Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        boolean lava = PhaseHelper.isLava(state) || level.getFluidState(pos).is(Fluids.LAVA);
        if (PhaseHelper.isLava(state)) {
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
