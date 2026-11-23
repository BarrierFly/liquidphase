package org.liquidphase.phase;

import net.minecraft.core.Direction;

/**
 * 旁路表的一个取值：一格"红石相 / 元件相"的全部参数，压进一个 {@code int}。
 * <p>
 * 状态里只放 8 个材料类别（见 {@link Phase}）；凡是**只在带坐标入口**才用得到的参数
 * （功率、朝向、延迟、模式、供电、锁存）全部在这里，键是 {@code BlockPos.asLong()}。
 * 一个格只有一条记录，{@code kind == NONE} 表示没有记录（不入表）。
 * <p>
 * 位布局（15 bit，够用且留 1 bit 余量）：
 * <pre>
 *  bit  0..2  kind      0 none / 1 redstone / 2 repeater / 3 comparator / 4 observer
 *  bit  3..6  power     0..15（红石功率 / 比较器输出）
 *  bit  7..9  facing    Direction.ordinal()
 *  bit 10..11 delay     0..3（= 1..4 redstone ticks）
 *  bit 12     mode      比较器：0 比较 / 1 减法
 *  bit 13     locked    中继器锁存
 *  bit 14     powered   元件输出状态
 * </pre>
 */
public final class PhaseExtra {

    public enum Kind {
        NONE,
        REDSTONE,
        REPEATER,
        COMPARATOR,
        OBSERVER;

        public boolean isElement() {
            return this == REPEATER || this == COMPARATOR || this == OBSERVER;
        }
    }

    private static final int KIND_SHIFT = 0;
    private static final int KIND_MASK = 0x7;
    private static final int POWER_SHIFT = 3;
    private static final int POWER_MASK = 0xF;
    private static final int FACING_SHIFT = 7;
    private static final int FACING_MASK = 0x7;
    private static final int DELAY_SHIFT = 10;
    private static final int DELAY_MASK = 0x3;
    private static final int MODE_SHIFT = 12;
    private static final int LOCKED_SHIFT = 13;
    private static final int POWERED_SHIFT = 14;

    public static final int EMPTY = 0;

    private PhaseExtra() {
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    public static int of(Kind kind, int power, Direction facing, int delay, boolean subtractMode,
                         boolean locked, boolean powered) {
        return encode(kind, power, facing, delay, subtractMode, locked, powered);
    }

    public static int encode(Kind kind, int power, Direction facing, int delay, boolean subtractMode,
                             boolean locked, boolean powered) {
        int facingOrdinal = facing == null ? Direction.NORTH.ordinal() : facing.ordinal();
        return ((kind.ordinal() & KIND_MASK) << KIND_SHIFT)
            | ((power & POWER_MASK) << POWER_SHIFT)
            | ((facingOrdinal & FACING_MASK) << FACING_SHIFT)
            | ((delay & DELAY_MASK) << DELAY_SHIFT)
            | ((subtractMode ? 1 : 0) << MODE_SHIFT)
            | ((locked ? 1 : 0) << LOCKED_SHIFT)
            | ((powered ? 1 : 0) << POWERED_SHIFT);
    }

    public static int redstone(int power) {
        return encode(Kind.REDSTONE, power, Direction.NORTH, 0, false, false, false);
    }

    public static int element(Kind kind, Direction facing, int delay, boolean subtractMode, boolean powered) {
        return encode(kind, 0, facing, delay, subtractMode, false, powered);
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    public static Kind kind(int extra) {
        return Kind.values()[extra & KIND_MASK];
    }

    public static boolean isEmpty(int extra) {
        return (extra & KIND_MASK) == Kind.NONE.ordinal();
    }

    public static boolean isRedstone(int extra) {
        return kind(extra) == Kind.REDSTONE;
    }

    public static boolean isElement(int extra) {
        return kind(extra).isElement();
    }

    public static int power(int extra) {
        return (extra >> POWER_SHIFT) & POWER_MASK;
    }

    public static Direction facing(int extra) {
        return Direction.values()[(extra >> FACING_SHIFT) & FACING_MASK];
    }

    public static int delay(int extra) {
        return (extra >> DELAY_SHIFT) & DELAY_MASK;
    }

    public static boolean subtractMode(int extra) {
        return ((extra >> MODE_SHIFT) & 1) != 0;
    }

    public static boolean locked(int extra) {
        return ((extra >> LOCKED_SHIFT) & 1) != 0;
    }

    public static boolean powered(int extra) {
        return ((extra >> POWERED_SHIFT) & 1) != 0;
    }

    // ------------------------------------------------------------------
    // 改写（返回新值，不改原 int）
    // ------------------------------------------------------------------

    public static int withPower(int extra, int power) {
        return (extra & ~(POWER_MASK << POWER_SHIFT)) | ((power & POWER_MASK) << POWER_SHIFT);
    }

    public static int withFacing(int extra, Direction facing) {
        return (extra & ~(FACING_MASK << FACING_SHIFT)) | ((facing.ordinal() & FACING_MASK) << FACING_SHIFT);
    }

    public static int withPowered(int extra, boolean powered) {
        return (extra & ~(1 << POWERED_SHIFT)) | ((powered ? 1 : 0) << POWERED_SHIFT);
    }

    public static int withLocked(int extra, boolean locked) {
        return (extra & ~(1 << LOCKED_SHIFT)) | ((locked ? 1 : 0) << LOCKED_SHIFT);
    }

    public static int withDelay(int extra, int delay) {
        return (extra & ~(DELAY_MASK << DELAY_SHIFT)) | ((delay & DELAY_MASK) << DELAY_SHIFT);
    }

    public static int withMode(int extra, boolean subtractMode) {
        return (extra & ~(1 << MODE_SHIFT)) | ((subtractMode ? 1 : 0) << MODE_SHIFT);
    }

    /**
     * {@return 该条目能否被"水冲落"（0.2.5）}
     * <p>
     * 口径是**实时按条目种类判断**，不写死红石/中继器/比较器三选一：
     * 目前 {@code REDSTONE} 与全部元件都算可冲落（对齐原版"水冲掉红石粉/红石火把"的语义），
     * 未来新增可冲落的条目种类只需在这里放行，水的判定/冲落处理会自动跟着走。
     */
    public static boolean isWashable(int extra) {
        Kind kind = kind(extra);
        return kind == Kind.REDSTONE || kind.isElement();
    }

    /** 元件在延时结束后应有的输出（侦测器 2 tick 脉冲、中继器/比较器同步）。 */
    public static int delayTicks(int extra) {
        return delay(extra) + 1;
    }

    public static String describe(int extra) {
        if (isEmpty(extra)) {
            return "-";
        }
        Kind kind = kind(extra);
        return switch (kind) {
            case REDSTONE -> "redstone(" + power(extra) + ")";
            case REPEATER -> "repeater(facing=" + facing(extra) + ",delay=" + delayTicks(extra)
                + ",powered=" + powered(extra) + ",locked=" + locked(extra) + ")";
            case COMPARATOR -> "comparator(facing=" + facing(extra) + ",mode="
                + (subtractMode(extra) ? "subtract" : "compare") + ",powered=" + powered(extra) + ")";
            case OBSERVER -> "observer(facing=" + facing(extra) + ",powered=" + powered(extra) + ")";
            default -> "-";
        };
    }
}
