package org.liquidphase.cauldron;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.LiquidPhaseConfig;
import org.liquidphase.phase.PhaseHelper;
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
