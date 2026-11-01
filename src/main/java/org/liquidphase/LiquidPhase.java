package org.liquidphase;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.liquidphase.interact.LiquidInteractions;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.redstone.CompositeBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 液相（Liquid Phase）主入口。
 * <p>
 * 初始化顺序：读配置 → 注入 phase 属性（可含水方块集合）→ 注册液体互动的发射器行为
 * → 里程碑 2 的组合方块注册。总开关关闭时全部跳过（决策点 D9：旧存档相被原版状态回退丢弃）。
 */
public final class LiquidPhase implements ModInitializer {
    public static final String MOD_ID = "liquidphase";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LiquidPhaseConfig.load();
        LiquidPhaseConfig config = LiquidPhaseConfig.get();

        if (config.phaseEnabled) {
            injectPhaseProperty();
            // 里程碑 2：红石相与元件相的组合方块（R1 方案）。
            CompositeBlocks.register();
        } else {
            LOGGER.info("[Liquid Phase] 双端功能已关闭（phase 属性未注入；旧存档中的相会被丢弃）");
        }
        LiquidInteractions.registerDispenserBehaviors();
        org.liquidphase.cauldron.CauldronBehaviorRegistration.register();
        LOGGER.info("[Liquid Phase] initialized");
    }

    /**
     * 给全部相容宿主方块注入 phase 属性。
     * 1.19.4/1.20.1：Statement（决策点 D2）；1.21.1+：自带注入器（fork 的方案，MIT）。
     */
    private static void injectPhaseProperty() {
        int injected = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof PhaseCapable) {
                addPhaseProperty(block);
                injected++;
            }
        }
        //? if 1.19.4 || 1.20.1 {
        /*virtuoel.statement.api.StateRefresher.INSTANCE.reorderBlockStates();
         *///?}
        LOGGER.info("[Liquid Phase] injected phase property into {} blocks (total states: {})",
            injected, Block.BLOCK_STATE_REGISTRY.size());
    }

    private static void addPhaseProperty(Block block) {
        //? if 1.19.4 || 1.20.1 {
        /*virtuoel.statement.api.StateRefresher.INSTANCE.addBlockProperty(block, PhaseHelper.PHASE, Phase.NONE);
         *///?} else {
        org.liquidphase.state.PhaseStateRefresher.addBlockProperty(block, PhaseHelper.PHASE, Phase.NONE);
        //?}
    }
}
