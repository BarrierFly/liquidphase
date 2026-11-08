package org.liquidphase;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import org.liquidphase.interact.LiquidInteractions;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseCapable;
import org.liquidphase.phase.PhaseHelper;
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
            LOGGER.info("[Liquid Phase] redstone phase uses phase=redstone_0..15 (no extra blocks registered)");
        } else {
            LOGGER.info("[Liquid Phase] 双端功能已关闭（phase 属性未注入；旧存档中的相会被丢弃）");
        }
        LiquidInteractions.registerDispenserBehaviors();
        org.liquidphase.cauldron.CauldronBehaviorRegistration.register();
        liquidphase$registerSelfTest();
        LOGGER.info("[Liquid Phase] initialized");
    }

    /**
     * 自检接线（默认不生效）：JVM 属性 {@code -Dliquidphase.selftest=true} 或环境变量
     * {@code LIQUIDPHASE_SELFTEST=true} 时，服务端启动完成后跑一次 {@code PhaseSelfTest}，
     * 把 PASS/FAIL 写进日志。
     */
    private static void liquidphase$registerSelfTest() {
        boolean enabled = Boolean.getBoolean("liquidphase.selftest")
            || "true".equalsIgnoreCase(System.getenv("LIQUIDPHASE_SELFTEST"));
        if (!enabled) {
            return;
        }
        // 便于脚本/CI：跑完自检直接关服，不用手动 kill（默认不关）。
        boolean stopAfter = Boolean.getBoolean("liquidphase.selftest.stop")
            || "true".equalsIgnoreCase(System.getenv("LIQUIDPHASE_SELFTEST_STOP"));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            org.liquidphase.selftest.PhaseSelfTest.run(server);
            if (stopAfter) {
                server.halt(false);
            }
        });
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
