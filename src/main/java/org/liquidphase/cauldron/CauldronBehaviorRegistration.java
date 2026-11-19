package org.liquidphase.cauldron;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.LiquidPhaseConfig;
//? if 26.3 {
/*import org.liquidphase.mixin.DispatcherAccessor;
 *///?}

/**
 * 功能二：向原版炼药锅行为表追加条目（规划 §4 第一批，仅服务端语义、纯原版方块可表达）。
 * <ul>
 *   <li>水锅 + 熔岩桶/液体熔岩 → 按水位掉落石/圆石/台阶、锅清空（掉落版）</li>
 *   <li>熔岩锅 + 水桶/液体水 → 掉落黑曜石、锅清空（掉落版）</li>
 *   <li>水锅 + 混凝土粉末 → 消耗 1 水位产出混凝土</li>
 *   <li>细雪锅 + 水/熔岩 → 满水锅</li>
 * </ul>
 * 表访问分版本：≤1.20.1 直接 put 静态 Map；1.21.1-1.21.11 用 InteractionMap.map()；
 * 26.3 的 Dispatcher 自带 put（常量挪到了 CauldronInteractions）。
 * 功能三开启时水/熔岩"桶"即液体物品：消耗整组 1 个、不返还空桶。
 */
public final class CauldronBehaviorRegistration {

    private CauldronBehaviorRegistration() {
    }

    public static void register() {
        //? if 1.19.4 || 1.20.1 {
        /*net.minecraft.core.cauldron.CauldronInteraction.WATER.put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$waterCauldronUse(state, level, pos, player, hand, stack)
                ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.LAVA.put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$lavaCauldronUse(state, level, pos, player, hand, stack)
                ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.POWDER_SNOW.put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$powderSnowUse(state, level, pos, player, hand, stack)
                ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.POWDER_SNOW.put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$powderSnowUse(state, level, pos, player, hand, stack)
                ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS);
         *///?} elif 1.21.1 {
        /*net.minecraft.core.cauldron.CauldronInteraction.WATER.map().put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$waterCauldronUse(state, level, pos, player, hand, stack)
                ? net.minecraft.world.ItemInteractionResult.SUCCESS : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
        net.minecraft.core.cauldron.CauldronInteraction.LAVA.map().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$lavaCauldronUse(state, level, pos, player, hand, stack)
                ? net.minecraft.world.ItemInteractionResult.SUCCESS : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
        net.minecraft.core.cauldron.CauldronInteraction.POWDER_SNOW.map().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$powderSnowUse(state, level, pos, player, hand, stack)
                ? net.minecraft.world.ItemInteractionResult.SUCCESS : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
        net.minecraft.core.cauldron.CauldronInteraction.POWDER_SNOW.map().put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$powderSnowUse(state, level, pos, player, hand, stack)
                ? net.minecraft.world.ItemInteractionResult.SUCCESS : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
         *///?} elif 1.21.10 || 1.21.11 {
        net.minecraft.core.cauldron.CauldronInteraction.WATER.map().put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$waterCauldronUse(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.LAVA.map().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$lavaCauldronUse(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.POWDER_SNOW.map().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$powderSnowUse(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.POWDER_SNOW.map().put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$powderSnowUse(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.PASS);
        //?} else {
        /*java.util.Map<Item, net.minecraft.core.cauldron.CauldronInteraction> waterItems =
            ((DispatcherAccessor) (Object) net.minecraft.core.cauldron.CauldronInteractions.WATER).liquidphase$items();
        waterItems.put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$waterCauldronUse(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND);
        ((DispatcherAccessor) (Object) net.minecraft.core.cauldron.CauldronInteractions.LAVA).liquidphase$items().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$lavaCauldronUse(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND);
        java.util.Map<Item, net.minecraft.core.cauldron.CauldronInteraction> snowItems =
            ((DispatcherAccessor) (Object) net.minecraft.core.cauldron.CauldronInteractions.POWDER_SNOW).liquidphase$items();
        snowItems.put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$powderSnowUse(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND);
        snowItems.put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$powderSnowUse(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND);
         *///?}
        registerConcretePowders();
        registerLiquidItemInteractions();
    }

    /**
     * 功能三联动：液体物品口径下，锅的桶交互不再产生空桶。
     * <ul>
     *   <li>空桶右键水锅/熔岩锅：禁用（取水只走左键挖掘，桶始终留在手上）。</li>
     *   <li>液体水/液体熔岩倒进空锅/未满水锅：照常填锅，但消耗 1 个、不返还空桶。</li>
     *   <li>熔岩锅 + 液体熔岩：原版是"无操作但换回空桶"的怪行为，功能三开启时改为不处理。</li>
     * </ul>
     * 功能三关闭时完全不注册，回到原版桶语义。
     */
    private static void registerLiquidItemInteractions() {
        if (!LiquidPhaseConfig.get().liquidInteraction) {
            return;
        }

        // 空桶 → 水锅/熔岩锅：PASS（客户端与服务端一致，避免桶被换成液体物品）。
        putBucketBlocker(false);
        putBucketBlocker(true);

        // 液体物品 → 空锅 / 未满水锅：照常填充，消耗 1、不返还桶。
        //? if 1.19.4 || 1.20.1 {
        /*net.minecraft.core.cauldron.CauldronInteraction.EMPTY.put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$pourIntoEmpty(state, level, pos, player, hand, stack, false)
                ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.EMPTY.put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$pourIntoEmpty(state, level, pos, player, hand, stack, true)
                ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.WATER.put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$topUpWater(state, level, pos, player, hand, stack)
                ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.LAVA.put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> InteractionResult.PASS);
         *///?} elif 1.21.1 {
        /*net.minecraft.core.cauldron.CauldronInteraction.EMPTY.map().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$pourIntoEmpty(state, level, pos, player, hand, stack, false)
                ? net.minecraft.world.ItemInteractionResult.SUCCESS : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
        net.minecraft.core.cauldron.CauldronInteraction.EMPTY.map().put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$pourIntoEmpty(state, level, pos, player, hand, stack, true)
                ? net.minecraft.world.ItemInteractionResult.SUCCESS : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
        net.minecraft.core.cauldron.CauldronInteraction.WATER.map().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$topUpWater(state, level, pos, player, hand, stack)
                ? net.minecraft.world.ItemInteractionResult.SUCCESS : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
        net.minecraft.core.cauldron.CauldronInteraction.LAVA.map().put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
         *///?} elif 1.21.10 || 1.21.11 {
        net.minecraft.core.cauldron.CauldronInteraction.EMPTY.map().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$pourIntoEmpty(state, level, pos, player, hand, stack, false)
                ? InteractionResult.SUCCESS : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.EMPTY.map().put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$pourIntoEmpty(state, level, pos, player, hand, stack, true)
                ? InteractionResult.SUCCESS : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.WATER.map().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$topUpWater(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.PASS);
        net.minecraft.core.cauldron.CauldronInteraction.LAVA.map().put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> InteractionResult.PASS);
        //?} else {
        /*java.util.Map<Item, net.minecraft.core.cauldron.CauldronInteraction> emptyItems =
            ((DispatcherAccessor) (Object) net.minecraft.core.cauldron.CauldronInteractions.EMPTY).liquidphase$items();
        emptyItems.put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$pourIntoEmpty(state, level, pos, player, hand, stack, false)
                ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND);
        emptyItems.put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$pourIntoEmpty(state, level, pos, player, hand, stack, true)
                ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND);
        ((DispatcherAccessor) (Object) net.minecraft.core.cauldron.CauldronInteractions.WATER).liquidphase$items().put(Items.WATER_BUCKET,
            (state, level, pos, player, hand, stack) -> liquidphase$topUpWater(state, level, pos, player, hand, stack)
                ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND);
        ((DispatcherAccessor) (Object) net.minecraft.core.cauldron.CauldronInteractions.LAVA).liquidphase$items().put(Items.LAVA_BUCKET,
            (state, level, pos, player, hand, stack) -> InteractionResult.TRY_WITH_EMPTY_HAND);
         *///?}
    }

    /** 空桶右键水锅/熔岩锅的拒绝条目（各版本的表访问与返回值统一收口）。 */
    private static void putBucketBlocker(boolean lavaCauldron) {
        //? if 1.19.4 || 1.20.1 {
        /*// 这两个版本 CauldronInteraction 是枚举：两个常量的静态类型不同，不能写成三元表达式。
        if (lavaCauldron) {
            net.minecraft.core.cauldron.CauldronInteraction.LAVA.put(Items.BUCKET,
                (state, level, pos, player, hand, stack) -> InteractionResult.PASS);
        } else {
            net.minecraft.core.cauldron.CauldronInteraction.WATER.put(Items.BUCKET,
                (state, level, pos, player, hand, stack) -> InteractionResult.PASS);
        }
         *///?} elif 1.21.1 {
        /*(lavaCauldron
            ? net.minecraft.core.cauldron.CauldronInteraction.LAVA
            : net.minecraft.core.cauldron.CauldronInteraction.WATER).map().put(Items.BUCKET,
            (state, level, pos, player, hand, stack) -> net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
         *///?} elif 1.21.10 || 1.21.11 {
        (lavaCauldron
            ? net.minecraft.core.cauldron.CauldronInteraction.LAVA
            : net.minecraft.core.cauldron.CauldronInteraction.WATER).map().put(Items.BUCKET,
            (state, level, pos, player, hand, stack) -> InteractionResult.PASS);
        //?} else {
        /*((DispatcherAccessor) (Object) (lavaCauldron
            ? net.minecraft.core.cauldron.CauldronInteractions.LAVA
            : net.minecraft.core.cauldron.CauldronInteractions.WATER)).liquidphase$items().put(Items.BUCKET,
            (state, level, pos, player, hand, stack) -> InteractionResult.TRY_WITH_EMPTY_HAND);
         *///?}
    }

    /** 液体水/熔岩倒进空锅：填满对应锅，消耗 1 个液体物品（功能三：不返还空桶）。 */
    private static boolean liquidphase$pourIntoEmpty(BlockState state, Level level, BlockPos pos, Player player,
                                                     InteractionHand hand, ItemStack stack, boolean lava) {
        if (state.getBlock() != Blocks.CAULDRON) {
            return false;
        }
        if (!level.isClientSide()) {
            level.setBlock(pos, lava
                ? Blocks.LAVA_CAULDRON.defaultBlockState()
                : Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), Block.UPDATE_ALL);
            level.playSound(null, pos, lava ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
            CauldronBehaviors.consumeLiquidContainer(level, pos, player, hand);
        }
        return true;
    }

    /** 液体水倒进未满的水锅：+1 水位，消耗 1 个液体物品（功能三：不返还空桶）。 */
    private static boolean liquidphase$topUpWater(BlockState state, Level level, BlockPos pos, Player player,
                                                  InteractionHand hand, ItemStack stack) {
        if (state.getBlock() != Blocks.WATER_CAULDRON
            || state.getValue(LayeredCauldronBlock.LEVEL) >= 3) {
            return false;
        }
        if (!level.isClientSide()) {
            level.setBlock(pos, state.setValue(LayeredCauldronBlock.LEVEL,
                state.getValue(LayeredCauldronBlock.LEVEL) + 1), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
            CauldronBehaviors.consumeLiquidContainer(level, pos, player, hand);
        }
        return true;
    }

    /** 16 色混凝土粉末注册（各版本的表访问统一收口到这里）。 */
    @SuppressWarnings("unchecked")
    private static void registerConcretePowders() {
        for (Item powder : ConcreteMappings.powders()) {
            //? if 1.19.4 || 1.20.1 {
            /*net.minecraft.core.cauldron.CauldronInteraction.WATER.put(powder,
                (state, level, pos, player, hand, stack) -> liquidphase$waterCauldronUse(state, level, pos, player, hand, stack)
                    ? InteractionResult.sidedSuccess(level.isClientSide) : InteractionResult.PASS);
             *///?} elif 1.21.1 {
            /*net.minecraft.core.cauldron.CauldronInteraction.WATER.map().put(powder,
                (state, level, pos, player, hand, stack) -> liquidphase$waterCauldronUse(state, level, pos, player, hand, stack)
                    ? net.minecraft.world.ItemInteractionResult.SUCCESS : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
             *///?} elif 1.21.10 || 1.21.11 {
            net.minecraft.core.cauldron.CauldronInteraction.WATER.map().put(powder,
                (state, level, pos, player, hand, stack) -> liquidphase$waterCauldronUse(state, level, pos, player, hand, stack)
                    ? InteractionResult.SUCCESS : InteractionResult.PASS);
            //?} else {
            /*((DispatcherAccessor) (Object) net.minecraft.core.cauldron.CauldronInteractions.WATER).liquidphase$items().put(powder,
                (state, level, pos, player, hand, stack) -> liquidphase$waterCauldronUse(state, level, pos, player, hand, stack)
                    ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND);
             *///?}
        }
    }

    // ------------------------------------------------------------------
    // 共享行为体（返回是否处理；物品消耗在内部完成）
    // ------------------------------------------------------------------

    private static boolean liquidphase$waterCauldronUse(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide() || !(state.getBlock() instanceof AbstractCauldronBlock)) {
            return false;
        }
        LiquidPhaseConfig config = LiquidPhaseConfig.get();
        if (stack.is(Items.LAVA_BUCKET) && config.cauldronStone
            && state.getBlock() == Blocks.WATER_CAULDRON && state.getValue(LayeredCauldronBlock.LEVEL) >= 1) {
            CauldronBehaviors.lavaMeetsWaterCauldron(level, pos, state);
            CauldronBehaviors.consumeLiquidContainer(level, pos, player, hand);
            return true;
        }
        if (config.cauldronConcrete && CauldronBehaviors.hardenConcrete(level, pos, state, player, hand)) {
            return true;
        }
        return false;
    }

    private static boolean liquidphase$lavaCauldronUse(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide() || !LiquidPhaseConfig.get().cauldronObsidian) {
            return false;
        }
        if (stack.is(Items.WATER_BUCKET) && state.getBlock() == Blocks.LAVA_CAULDRON) {
            CauldronBehaviors.waterMeetsLavaCauldron(level, pos);
            CauldronBehaviors.consumeLiquidContainer(level, pos, player, hand);
            return true;
        }
        return false;
    }

    private static boolean liquidphase$powderSnowUse(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide() || !LiquidPhaseConfig.get().cauldronPowderSnow) {
            return false;
        }
        if ((stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)) && state.getBlock() == Blocks.POWDER_SNOW_CAULDRON) {
            CauldronBehaviors.powderSnowMeetsFluid(level, pos);
            CauldronBehaviors.consumeLiquidContainer(level, pos, player, hand);
            return true;
        }
        return false;
    }
}
