package org.liquidphase.phase;

import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;

/**
 * 相（phase）枚举：一格内"另一相"的取值。
 * <p>
 * 语义（规划 §2.3）：原版 {@code waterlogged} 布尔保留不动，水相 = 原版含水；
 * 本属性表达第二相，与 {@code waterlogged=true} 互斥。相之间存在转化链：
 * {@code lava}→（遇水凝固）→{@code obsidian}；{@code ice}/{@code frosted_*}→（融化）→水相。
 * 霜冰的渐进裂纹（原版 FrostedIce 的 AGE 0–3）用 frosted_0–3 取值表达，
 * 避免再乘一个 AGE 属性。26.4 的脆冰（BrittleIce）预留枚举空间，届时追加新取值
 * 等价于追加新状态，向下兼容（决策点 M2.5 预留）。
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

    /** 除 NONE 外的全部取值——注入时按此顺序为每个既有状态追加 7 个新状态。 */
    public static final Phase[] FILLED = {LAVA, OBSIDIAN, ICE, FROSTED_0, FROSTED_1, FROSTED_2, FROSTED_3};
    public static final int FROSTED_MAX_AGE = 3;

    private final String name;

    Phase(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    public boolean isNone() {
        return this == NONE;
    }

    public boolean isLava() {
        return this == LAVA;
    }

    public boolean isObsidian() {
        return this == OBSIDIAN;
    }

    public boolean isIce() {
        return this == ICE;
    }

    public boolean isFrosted() {
        return this.ordinal() >= FROSTED_0.ordinal();
    }

    /** 冰相（含霜冰）及其任意裂纹阶段。 */
    public boolean isFrozen() {
        return this.isIce() || this.isFrosted();
    }

    /** 固体相：冰/霜冰/黑曜石（红石相在里程碑 2 用组合方块表达，不占本枚举）。 */
    public boolean isSolid() {
        return this != NONE && this != LAVA;
    }

    public int frostedAge() {
        return this.ordinal() - FROSTED_0.ordinal();
    }

    public static Phase frosted(int age) {
        return values()[FROSTED_0.ordinal() + Mth.clamp(age, 0, FROSTED_MAX_AGE)];
    }

    /** 霜冰推进一格（frosted_3 不再推进，由调用方处理融化）。 */
    public Phase advanceFrost() {
        return frosted(this.frostedAge() + 1);
    }
}
