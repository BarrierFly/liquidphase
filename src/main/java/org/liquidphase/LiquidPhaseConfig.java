package org.liquidphase;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 配置（规划 §6）：总开关（双端功能）、功能三开关、炼药锅各功能子开关。
 * 服务端权威：客户端侧只读文件用于本地渲染开关（同一份文件）。
 * <p>
 * 关闭总开关时（决策点 D9）：服务端跳过属性注入与新方块注册，所有"产生新相"的路径
 * 返回拒绝；旧存档里已存在的相会被原版状态回退机制直接丢弃（README 明示）。
 */
public final class LiquidPhaseConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("liquidphase.json");

    private static LiquidPhaseConfig instance = new LiquidPhaseConfig();

    // ---- 功能一（双端必装，总开关） ----
    /** 双端功能总开关：关闭时跳过 phase 属性注入与里程碑 2 的新方块注册。 */
    public boolean phaseEnabled = true;

    // ---- 功能三（双端必装，独立开关） ----
    /** 液体互动（桶左键挖掘/液体物品放置）。关闭时恢复原版桶机制并允许右键收取熔岩相。 */
    public boolean liquidInteraction = true;

    // ---- 渲染兼容（仅客户端生效） ----
    /**
     * 资源包兼容合并（0.1.17）：第三方资源包覆盖相容宿主的 blockstate 时，客户端在
     * blockstate 加载点把本 mod 的相覆盖部件合并进获胜定义，使包只生效于宿主 part。
     * 关闭则回到旧行为：包整体覆盖 blockstate、相外观静默丢失。
     */
    public boolean packMerge = true;

    // ---- 功能二：炼药锅（仅服务端，子开关） ----
    /** 熔岩锅+水 → 掉落黑曜石、锅清空（掉落版）。 */
    public boolean cauldronObsidian = true;
    /** 水锅+熔岩 → 按水位掉落石头/圆石/圆石台阶、锅清空。 */
    public boolean cauldronStone = true;
    /** 空锅收流体：只收直接接触的源（决策点 D13）。 */
    public boolean cauldronCollectFluid = true;
    /** 混凝土粉末固化：消耗 1 水位产出对应颜色混凝土。 */
    public boolean cauldronConcrete = true;
    /** 发射器/漏斗自动化（液体物品统一）。 */
    public boolean cauldronAutomation = true;
    /** 细雪锅遇水/熔岩转化。 */
    public boolean cauldronPowderSnow = true;

    public static LiquidPhaseConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(PATH)) {
            try {
                instance = GSON.fromJson(Files.readString(PATH), LiquidPhaseConfig.class);
            } catch (IOException | RuntimeException e) {
                LiquidPhase.LOGGER.error("[Liquid Phase] failed to read config, using defaults", e);
                instance = new LiquidPhaseConfig();
            }
            // 防止旧版本配置缺字段后为 null。
            LiquidPhaseConfig defaults = new LiquidPhaseConfig();
            if (instance == null) {
                instance = defaults;
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(instance));
        } catch (IOException e) {
            LiquidPhase.LOGGER.error("[Liquid Phase] failed to save config", e);
        }
    }

    /** 炼药锅转化类是否全部关闭（用于跳过 neighborChanged 挂钩）。 */
    public boolean anyCauldronFeatureEnabled() {
        return this.cauldronObsidian || this.cauldronStone || this.cauldronCollectFluid
            || this.cauldronConcrete || this.cauldronPowderSnow;
    }
}
