package org.liquidphase.interact;

import net.minecraft.core.BlockPos;
//? if 1.19.4 || 1.20.1 {
/*import net.minecraft.core.BlockSource;
 *///?} else {
import net.minecraft.core.dispenser.BlockSource;
//?}
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;

/**
 * 发射器自动化（规划 §4 发射器自动化 / §5.3 / 决策点 D15）：
 * <ul>
 *   <li>水/熔岩"桶"（液体物品）发射：前方是锅 → 填锅（消耗 1，不返还空桶）；
 *       否则功能三开启时像放方块一样放流体（整组消耗）、关闭时等价原版桶（emptyContents+空桶）。</li>
 *   <li>空桶发射（D15）：收取前方液体源/熔岩相/流体锅 → 产物置入发射器物品栏，装不下从发射器掉落；
 *       没有可收目标 → 原版默认（吐出空桶）。</li>
 *   <li>玻璃瓶发射：水位 ≥1 的水锅 → 水瓶，水位 -1。</li>
 * </ul>
 * BlockSource 访问器分版本：≤1.20.1 是接口 getter，1.21.1+ 是 record；
 * execute/consumeWithRemainder 的名字与签名全版本一致（guardian 核实）。
 */
public final class LiquidInteractions {

    private LiquidInteractions() {
    }

    public static void registerDispenserBehaviors() {
        LiquidPhaseConfig config = LiquidPhaseConfig.get();
        if (config.liquidInteraction || config.cauldronAutomation) {
            DispenserBlock.registerBehavior(Items.WATER_BUCKET, new LiquidBucketBehavior(false));
            DispenserBlock.registerBehavior(Items.LAVA_BUCKET, new LiquidBucketBehavior(true));
            DispenserBlock.registerBehavior(Items.BUCKET, new EmptyBucketBehavior());
        }
        if (config.cauldronAutomation) {
            DispenserBlock.registerBehavior(Items.GLASS_BOTTLE, new BottleBehavior());
        }
    }

    // ------------------------------------------------------------------
    // BlockSource 版本差异的统一访问
    // ------------------------------------------------------------------

    private static Level level(BlockSource source) {
        //? if 1.19.4 || 1.20.1 {
        /*return source.getLevel();
         *///?} else {
        return source.level();
        //?}
    }

    private static BlockPos pos(BlockSource source) {
        //? if 1.19.4 || 1.20.1 {
        /*return source.getPos();
         *///?} else {
        return source.pos();
        //?}
    }

    private static BlockState state(BlockSource source) {
        //? if 1.19.4 || 1.20.1 {
        /*return source.getBlockState();
         *///?} else {
        return source.state();
        //?}
    }

    private static Container blockEntity(BlockSource source) {
        //? if 1.19.4 || 1.20.1 {
        /*return source.getEntity();
         *///?} else {
        return source.blockEntity();
        //?}
    }

    private static BlockPos frontPos(BlockSource source) {
        return pos(source).relative(state(source).getValue(DispenserBlock.FACING));
    }

    /** 把产物尽量放回发射器物品栏的空槽，放不下则从发射器中心掉落（D15）。 */
    private static void storeOrDrop(BlockSource source, ItemStack stack) {
        Container container = blockEntity(source);
        if (container != null) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (container.getItem(i).isEmpty()) {
                    container.setItem(i, stack);
                    container.setChanged();
                    return;
                }
            }
        }
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(pos(source));
        level(source).addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
            level(source), center.x, center.y, center.z, stack));
    }

    // ------------------------------------------------------------------
    // 共享逻辑
    // ------------------------------------------------------------------

    /** 填锅：空锅→满锅；非满水锅+水 → +1。返回是否成功。 */
    private static boolean fillCauldron(Level level, BlockPos pos, BlockState state, boolean lava) {
        if (state.getBlock() == Blocks.CAULDRON) {
            level.setBlock(pos, lava
                ? Blocks.LAVA_CAULDRON.defaultBlockState()
                : Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), Block.UPDATE_ALL);
            level.playSound(null, pos, lava ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            return true;
        }
        if (!lava && state.getBlock() == Blocks.WATER_CAULDRON && state.getValue(LayeredCauldronBlock.LEVEL) < 3) {
            level.setBlock(pos, state.setValue(LayeredCauldronBlock.LEVEL, state.getValue(LayeredCauldronBlock.LEVEL) + 1), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            return true;
        }
        return false;
    }

    /** 水/熔岩"桶"发射主体。返回 null = 未处理（交给原版）。 */
    private static ItemStack liquidDispense(BlockSource source, ItemStack stack, boolean lava) {
        Level level = level(source);
        BlockPos front = frontPos(source);
        BlockState frontState = level.getBlockState(front);
        boolean liquidInteraction = LiquidPhaseConfig.get().liquidInteraction;

        if (LiquidPhaseConfig.get().cauldronAutomation && frontState.getBlock() instanceof AbstractCauldronBlock) {
            if (fillCauldron(level, front, frontState, lava)) {
                stack.shrink(1);
                return stack;
            }
            return null;
        }
        if (liquidInteraction) {
            if (!frontState.isAir() && !frontState.canBeReplaced()) {
                return null;
            }
            if (!lava && PhaseHelper.waterEvaporates(level, front)) {
                level.playSound(null, front, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.6F);
                stack.shrink(1);
                return stack;
            }
            level.setBlock(front, (lava ? Fluids.LAVA : Fluids.WATER).defaultFluidState().createLegacyBlock(), Block.UPDATE_ALL);
            level.playSound(null, front, lava ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
            stack.shrink(1);
            return stack;
        }
        // 功能三关闭：等价原版桶行为（emptyContents + 返还空桶）。
        if (frontState.isAir() || frontState.canBeReplaced()) {
            net.minecraft.world.item.BucketItem bucket =
                (net.minecraft.world.item.BucketItem) (lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET);
            if (bucket.emptyContents(null, level, front, null)) {
                return consumeWithRemainderHelper(source, stack, new ItemStack(Items.BUCKET));
            }
        }
        return null;
    }

    /** 复刻 vanilla 的 consumeWithRemainder：消耗 1，产物进槽或掉落。 */
    private static ItemStack consumeWithRemainderHelper(BlockSource source, ItemStack original, ItemStack remainder) {
        original.shrink(1);
        if (original.isEmpty()) {
            return remainder;
        }
        storeOrDrop(source, remainder);
        return original;
    }

    /** 空桶收取主体（D15）。返回 null = 没有可收目标。 */
    private static ItemStack emptyBucketCollect(BlockSource source, ItemStack stack) {
        Level level = level(source);
        BlockPos front = frontPos(source);
        BlockState frontState = level.getBlockState(front);
        boolean liquidInteraction = LiquidPhaseConfig.get().liquidInteraction;

        if (LiquidPhaseConfig.get().cauldronAutomation && frontState.getBlock() instanceof AbstractCauldronBlock) {
            if (frontState.getBlock() == Blocks.WATER_CAULDRON
                && frontState.getValue(LayeredCauldronBlock.LEVEL) >= LayeredCauldronBlock.MAX_FILL_LEVEL) {
                level.setBlock(front, Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
                level.playSound(null, front, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                return consumeWithRemainderHelper(source, stack, new ItemStack(Items.WATER_BUCKET));
            }
            if (frontState.getBlock() == Blocks.LAVA_CAULDRON) {
                level.setBlock(front, Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
                level.playSound(null, front, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
                return consumeWithRemainderHelper(source, stack, new ItemStack(Items.LAVA_BUCKET));
            }
            return null;
        }
        if (PhaseHelper.isLava(frontState)) {
            level.setBlock(front, PhaseHelper.withPhase(frontState, Phase.NONE), Block.UPDATE_ALL);
            level.playSound(null, front, SoundEvents.BUCKET_FILL_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
            return consumeWithRemainderHelper(source, stack, new ItemStack(Items.LAVA_BUCKET));
        }
        FluidState fluid = level.getFluidState(front);
        if (fluid.isSource() && (fluid.is(Fluids.WATER) || fluid.is(Fluids.LAVA))) {
            boolean lava = fluid.is(Fluids.LAVA);
            level.setBlock(front, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.playSound(null, front, lava ? SoundEvents.BUCKET_FILL_LAVA : SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
            return consumeWithRemainderHelper(source, stack, new ItemStack(lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET));
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 行为类
    // ------------------------------------------------------------------

    /** 水/熔岩"桶"发射行为。 */
    private static final class LiquidBucketBehavior extends DefaultDispenseItemBehavior {
        private final boolean lava;

        LiquidBucketBehavior(boolean lava) {
            this.lava = lava;
        }

        @Override
        protected ItemStack execute(BlockSource source, ItemStack stack) {
            ItemStack result = LiquidInteractions.liquidDispense(source, stack, this.lava);
            return result != null ? result : super.execute(source, stack);
        }
    }

    /** 空桶收取行为（D15）。 */
    private static final class EmptyBucketBehavior extends DefaultDispenseItemBehavior {
        @Override
        protected ItemStack execute(BlockSource source, ItemStack stack) {
            ItemStack result = LiquidInteractions.emptyBucketCollect(source, stack);
            return result != null ? result : super.execute(source, stack);
        }
    }

    /** 玻璃瓶发射行为：水锅取水瓶。 */
    private static final class BottleBehavior extends DefaultDispenseItemBehavior {
        @Override
        protected ItemStack execute(BlockSource source, ItemStack stack) {
            Level level = LiquidInteractions.level(source);
            BlockPos front = LiquidInteractions.frontPos(source);
            BlockState frontState = level.getBlockState(front);
            if (frontState.getBlock() == Blocks.WATER_CAULDRON && frontState.getValue(LayeredCauldronBlock.LEVEL) >= 1) {
                int newLevel = frontState.getValue(LayeredCauldronBlock.LEVEL) - 1;
                level.setBlock(front, newLevel == 0
                    ? Blocks.CAULDRON.defaultBlockState()
                    : frontState.setValue(LayeredCauldronBlock.LEVEL, newLevel), Block.UPDATE_ALL);
                level.playSound(null, front, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                stack.shrink(1);
                LiquidInteractions.storeOrDrop(source, LiquidInteractions.waterBottle());
                return stack;
            }
            return super.execute(source, stack);
        }
    }

    /** 水瓶（带 water 药水组件；1.20.5 前走 NBT）。 */
    private static ItemStack waterBottle() {
        ItemStack bottle = new ItemStack(Items.POTION);
        //? if 1.19.4 || 1.20.1 {
        /*bottle.getOrCreateTag().putString("Potion", "minecraft:water");
         *///?} else {
        bottle.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
            new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.WATER));
        //?}
        return bottle;
    }
}
