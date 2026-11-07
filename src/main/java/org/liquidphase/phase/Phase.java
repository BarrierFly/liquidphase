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
 * 避免再乘一个 AGE 属性。
 * <p>
 * 红石相（§3.4）用 {@code redstone_0}…{@code redstone_15} **16 个取值编码功率**：
 * 方块本身仍是原来的宿主方块（楼梯/台阶/墙/栅栏……），只是多了一个 phase 值，
 * 不像早先的"组合方块"那样另注册一堆新方块。代价是状态数 ×3（24 个取值）。
 * 26.4 的脆冰（BrittleIce）预留枚举空间，届时追加新取值等价于追加新状态。
 */
public enum Phase implements StringRepresentable {
    NONE("none"),
    LAVA("lava"),
    OBSIDIAN("obsidian"),
    ICE("ice"),
    FROSTED_0("frosted_0"),
    FROSTED_1("frosted_1"),
    FROSTED_2("frosted_2"),
    FROSTED_3("frosted_3"),
    REDSTONE_0("redstone_0"),
    REDSTONE_1("redstone_1"),
    REDSTONE_2("redstone_2"),
    REDSTONE_3("redstone_3"),
    REDSTONE_4("redstone_4"),
    REDSTONE_5("redstone_5"),
    REDSTONE_6("redstone_6"),
    REDSTONE_7("redstone_7"),
    REDSTONE_8("redstone_8"),
    REDSTONE_9("redstone_9"),
    REDSTONE_10("redstone_10"),
    REDSTONE_11("redstone_11"),
    REDSTONE_12("redstone_12"),
    REDSTONE_13("redstone_13"),
    REDSTONE_14("redstone_14"),
    REDSTONE_15("redstone_15");

    /** 除 NONE 外的全部取值——注入时按此顺序为每个既有状态追加新状态。 */
    public static final Phase[] FILLED = {
        LAVA, OBSIDIAN, ICE, FROSTED_0, FROSTED_1, FROSTED_2, FROSTED_3,
        REDSTONE_0, REDSTONE_1, REDSTONE_2, REDSTONE_3, REDSTONE_4, REDSTONE_5,
        REDSTONE_6, REDSTONE_7, REDSTONE_8, REDSTONE_9, REDSTONE_10, REDSTONE_11,
        REDSTONE_12, REDSTONE_13, REDSTONE_14, REDSTONE_15,
    };
    public static final int FROSTED_MAX_AGE = 3;
    public static final int REDSTONE_MAX_POWER = 15;

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

    /** 霜冰的 4 个裂纹阶段（显式区间，不能再用 ordinal 比较——红石取值排在后面）。 */
    public boolean isFrosted() {
        return ordinal() >= FROSTED_0.ordinal() && ordinal() <= FROSTED_3.ordinal();
    }

    /** 冰相（含霜冰）及其任意裂纹阶段。 */
    public boolean isFrozen() {
        return this.isIce() || this.isFrosted();
    }

    /** 固体相：冰/霜冰/黑曜石（熔岩是流体；红石相不是固相）。 */
    public boolean isSolid() {
        return this == OBSIDIAN || this.isFrozen();
    }

    /** 红石相：功率编码在 phase 取值里。 */
    public boolean isRedstone() {
        return ordinal() >= REDSTONE_0.ordinal();
    }

    /** {@return 红石相功率 0–15；非红石相返回 0} */
    public int redstonePower() {
        return isRedstone() ? ordinal() - REDSTONE_0.ordinal() : 0;
    }

    /** {@return 指定功率的红石相取值（自动夹到 0–15）} */
    public static Phase redstone(int power) {
        return values()[REDSTONE_0.ordinal() + Mth.clamp(power, 0, REDSTONE_MAX_POWER)];
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
