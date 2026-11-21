package org.liquidphase.element;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseStorage;

/**
 * 元件/红石相的渲染选择（纯逻辑，服务端也安全：不引用任何客户端类）。
 * <p>
 * 动态模型在区块构建期按坐标查旁路表，选出要发射的子模型 id 与 y 轴旋转角：
 * <ul>
 *   <li>红石：{@code liquidphase:block/redstone_phase_<power>}（16 档颜色），不旋转、不抬升；</li>
 *   <li>中继器：{@code liquidphase:block/element_repeater_<n>tick[_on][_locked]}，
 *       模型按"朝向 = 南"手工（脚本从原版模型复制），需要按 facing 旋转；</li>
 *   <li>比较器：{@code liquidphase:block/element_comparator[_on][_subtract]}，同上；</li>
 *   <li>侦测器：{@code liquidphase:block/element_observer[_on]}，模型按"观察面 = 北"手工，需要旋转。</li>
 * </ul>
 * 抬升 = 逐顶点采样宿主碰撞形状在该列的最高点（0–1），即"画在宿主表面上层"。
 */
public final class ElementRenderSupport {

    private ElementRenderSupport() {
    }

    /** 元件几何高度（方块空间，0–1）：中继器/比较器 7px，侦测器 8px。 */
    public static double elementHeight(PhaseExtra.Kind kind) {
        return kind == PhaseExtra.Kind.OBSERVER ? 0.5 : 7.0 / 16.0;
    }

    /** {@return 需要动态几何的条目} */
    public static boolean hasGeometry(int extra) {
        return PhaseExtra.isRedstone(extra) || PhaseExtra.isElement(extra);
    }

    /** {@return 子模型 id（{@code namespace:path}，不带 {@code .json}）} */
    public static String subModelId(int extra) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        return switch (kind) {
            case REDSTONE -> "liquidphase:block/redstone_phase_" + PhaseExtra.power(extra);
            case REPEATER -> "liquidphase:block/element_repeater_" + (PhaseExtra.delay(extra) + 1) + "tick"
                + (PhaseExtra.powered(extra) ? "_on" : "")
                + (PhaseExtra.locked(extra) ? "_locked" : "");
            case COMPARATOR -> "liquidphase:block/element_comparator"
                + (PhaseExtra.powered(extra) ? "_on" : "")
                + (PhaseExtra.subtractMode(extra) ? "_subtract" : "");
            case OBSERVER -> "liquidphase:block/element_observer" + (PhaseExtra.powered(extra) ? "_on" : "");
            default -> "liquidphase:block/dynamic";
        };
    }

    /**
     * {@return y 轴旋转角（度，按 {@code x' = x cosθ - z sinθ; z' = x sinθ + z cosθ} 绕方块中心旋转）}
     * <p>
     * 对应原版 blockstate 的 {@code y} 值：中继器/比较器模型在无旋转时朝南，
     * 侦测器（我们的贴花模型）在无旋转时观察面朝北。
     */
    public static float yawDegrees(int extra) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        Direction facing = PhaseExtra.facing(extra);
        boolean observer = kind == PhaseExtra.Kind.OBSERVER;
        return switch (facing) {
            case SOUTH -> observer ? 180.0F : 0.0F;
            case NORTH -> observer ? 0.0F : 180.0F;
            case EAST -> observer ? 90.0F : 270.0F;
            case WEST -> observer ? 270.0F : 90.0F;
            default -> 0.0F;
        };
    }

    /** {@return 该列宿主顶面高度（0–1）；空列按 0 处理} */
    public static double columnTop(VoxelShape shape, double x, double z) {
        if (shape == null || shape.isEmpty()) {
            return 0.0;
        }
        // 顶点正好落在方块边界（x/z = 0 或 1）时 DiscreteVoxelShape 的列索引会越界，
        // 返回 NEGATIVE_INFINITY（= 空列）——那样同一块模型只有内部顶点被抬升，
        // 边缘顶点留在原地，几何被拉花。夹到格子内部再采样。
        double sx = Math.max(1.0E-4, Math.min(1.0 - 1.0E-4, x));
        double sz = Math.max(1.0E-4, Math.min(1.0 - 1.0E-4, z));
        double top = shape.max(Direction.Axis.Y, sx, sz);
        if (top == Double.NEGATIVE_INFINITY) {
            return 0.0;
        }
        return Math.min(1.0, top);
    }

    /** {@return 宿主顶面最高点（用于放置前提判断）} */
    public static double footprintTop(VoxelShape shape) {
        if (shape == null || shape.isEmpty()) {
            return 0.0;
        }
        double best = 0.0;
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                best = Math.max(best, columnTop(shape, (i + 0.5) / 8.0, (j + 0.5) / 8.0));
            }
        }
        return best;
    }

    /** 便捷：按存储取条目（异常/无存储返回 0）。 */
    public static int extraAt(BlockState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos) {
        if (!state.hasProperty(org.liquidphase.phase.PhaseHelper.FILLING)) {
            return PhaseExtra.EMPTY;
        }
        return org.liquidphase.phase.PhaseExtras.get(level, pos);
    }

    /** 供渲染缓存键使用：宿主形状 + 条目 → 稳定键。 */
    public record GeometryKey(int extra, long pos, int shapeIdentity) {
    }
}
