package org.liquidphase;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
            LOGGER.info("[Liquid Phase] scheme C: filling carries 8 material categories; redstone power and element parameters live in the sparse side table");
        } else {
            LOGGER.info("[Liquid Phase] 双端功能已关闭（phase 属性未注入；旧存档中的相会被丢弃）");
        }
        LiquidInteractions.registerDispenserBehaviors();
        org.liquidphase.cauldron.CauldronBehaviorRegistration.register();
        // 相能力握手：把服务端配置广播给客户端；连原版服务器的客户端永远收不到，
        // 客户端侧功能会自动退回原版行为（见 PhaseHandshake / PhaseSyncState）。
        org.liquidphase.sync.PhaseHandshake.registerServer();
        if (config.phaseEnabled) {
            org.liquidphase.sync.PhaseExtraSync.registerServer();
            org.liquidphase.phase.PhasePersistence.register();
        }
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
        int waterloggedInjected = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof PhaseCapable) {
                // 传送门三件套没有原版 WATERLOGGED，但语义上要和其他宿主一样能含水：
                // 先补这个布尔属性，再补 filling（顺序不能反——filling 的变体要带上它）。
                if (block.getStateDefinition().getProperty("waterlogged") == null) {
                    addWaterloggedProperty(block);
                    waterloggedInjected++;
                }
                addPhaseProperty(block);
                injected++;
            }
        }
        //? if 1.19.4 || 1.20.1 {
        /*virtuoel.statement.api.StateRefresher.INSTANCE.reorderBlockStates();
         *///?}
        LOGGER.info("[Liquid Phase] injected phase property into {} blocks (total states: {})",
            injected, Block.BLOCK_STATE_REGISTRY.size());
        if (waterloggedInjected > 0) {
            LOGGER.info("[Liquid Phase] injected WATERLOGGED into {} hosts that lacked it (portal blocks)", waterloggedInjected);
        }
    }

    private static void addWaterloggedProperty(Block block) {
        //? if 1.19.4 || 1.20.1 {
        /*virtuoel.statement.api.StateRefresher.INSTANCE.addBlockProperty(block, BlockStateProperties.WATERLOGGED, false);
         *///?} else {
        org.liquidphase.state.PhaseStateRefresher.addBlockProperty(block, BlockStateProperties.WATERLOGGED, false);
        //?}
    }

    private static void addPhaseProperty(Block block) {
        //? if 1.19.4 || 1.20.1 {
        /*virtuoel.statement.api.StateRefresher.INSTANCE.addBlockProperty(block, PhaseHelper.FILLING, Phase.NONE);
         *///?} else {
        org.liquidphase.state.PhaseStateRefresher.addBlockProperty(block, PhaseHelper.FILLING, Phase.NONE);
        //?}
    }
}
