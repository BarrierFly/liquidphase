package org.liquidphase.phase;

import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;

/**
 * 相（phase）类别枚举（方案 C，0.2.0 起）。
 * <p>
 * 只保留**必须无坐标回答**的类别：流体态（{@code getFluidState()}）、发光
 * （{@code getLightEmission()}）、随机刻资格（{@code isRandomlyTicking()}）都拿不到
 * {@code BlockPos}，所以熔岩/冰/霜冰必须留在状态里；固相碰撞（整格）与破坏保留同理。
 * <p>
 * 红石相与元件相（中继器/比较器/侦测器）**不占状态值**：它们的参数
 * （功率/朝向/延迟/模式/供电/锁存）整条存在旁路表 {@link PhaseExtra} 里，
 * 见 {@code docs/plan-c-elements.md}。这使 {@code filling} 的取值域固定为 8 个，
 * 2030 年代的元件扩展不再乘状态数。
 * <p>
 * 霜冰的渐进裂纹（原版 FrostedIce 的 AGE 0–3）保留为 4 个取值：它是**材质档位**，
 * 走静态 blockstate 覆盖层，不需要旁路表查询。
 */
public enum Phase implements StringRepresentable {
    NONE("none"),
    LAVA("lava"),
    OBSIDIAN("obsidian"),
    ICE("ice"),
    FROSTED_0("frosted_0"),
    FROSTED_1("frosted_1"),
    FROSTED_2("frosted_2"),
    FROSTED_3("frosted_3");

    /** 除 NONE 外的全部取值——注入时按此顺序为每个既有状态追加新状态。 */
    public static final Phase[] FILLED = {
        LAVA, OBSIDIAN, ICE, FROSTED_0, FROSTED_1, FROSTED_2, FROSTED_3,
    };
    public static final int FROSTED_MAX_AGE = 3;

    private final String name;

    Phase(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    public boolean isIce() {
        return this == ICE;
    }

    /** 霜冰的 4 个裂纹阶段（显式区间）。 */
    public boolean isFrosted() {
        return ordinal() >= FROSTED_0.ordinal() && ordinal() <= FROSTED_3.ordinal();
    }

    /** 冰相（含霜冰）及其任意裂纹阶段。 */
    public boolean isFrozen() {
        return this.isIce() || this.isFrosted();
    }

    /** 固体相：冰/霜冰/黑曜石（熔岩是流体）。 */
    public boolean isSolid() {
        return this == OBSIDIAN || this.isFrozen();
    }

    /**
     * {@return 该相作为"现有填充相"时能否被新的填充材料替换（0.1.17 可替换填充相规则）}
     * <p>
     * 吸收门控（{@code PhaseHelper.canAcceptPhase}）只看这一条：现有填充相可替换 →
     * 任何填充材料（冰/霜冰/黑曜石/熔岩/红石粉，及未来加入的相材料）都可写入；
     * 不可替换 → 按不可放置处理。空格与含水态（水即"现有填充相"）在门控里单独算可替换，
     * 不走本方法。
     */
    public boolean isReplaceableFilling() {
        return this == LAVA;
    }

    public int frostedAge() {
        return isFrosted() ? ordinal() - FROSTED_0.ordinal() : 0;
    }

    public static Phase frosted(int age) {
        return values()[FROSTED_0.ordinal() + Mth.clamp(age, 0, FROSTED_MAX_AGE)];
    }

    /** 霜冰推进一格（frosted_3 不再推进，由调用方处理融化）。 */
    public Phase advanceFrost() {
        return frosted(frostedAge() + 1);
    }
}
