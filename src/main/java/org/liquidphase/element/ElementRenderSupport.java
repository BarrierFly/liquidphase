package org.liquidphase.element;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.PhaseExtra;

/**
 * 元件/红石相的渲染选择（纯逻辑，服务端也安全：不引用任何客户端类）。
 * <p>
 * 动态模型在区块构建期按坐标查旁路表，选出要发射的子模型 id 与旋转角：
 * <ul>
 *   <li>红石：{@code liquidphase:block/redstone_phase_<power>}（16 档颜色），不旋转、不抬升；</li>
 *   <li>中继器：{@code liquidphase:block/element_repeater_<n>tick[_on][_locked]}；
 *       子模型只含杆与红条，按 1/16 像素分列，运行时逐列贴到宿主顶面；</li>
 *   <li>比较器：{@code liquidphase:block/element_comparator[_on][_subtract]}，同上；</li>
 *   <li>侦测器：{@code liquidphase:block/element_observer[_<facing>][_on]}；16×16 正常比例、
 *       固定在方块内（不抬升），只含侦测面五官与输出面输出口两个平面。
 *       **六个朝向各自预烘焙**，运行期不旋转（单面平面转向后容易被区块网格按旧朝向剔除）。</li>
 * </ul>
 * 中继器/比较器的 y 轴旋转按原版 blockstate 的 {@code y} 值。
 */
public final class ElementRenderSupport {

    private ElementRenderSupport() {
    }

    /** {@return 需要动态几何的条目} */
    public static boolean hasGeometry(int extra) {
        return PhaseExtra.isRedstone(extra) || PhaseExtra.isElement(extra);
    }

    /** {@return 该条目是否按"每个 1/16 像素列各自贴到宿主顶面"处理（中继器/比较器）} */
    public static boolean terrainLift(int extra) {
        PhaseExtra.Kind kind = PhaseExtra.kind(extra);
        return kind == PhaseExtra.Kind.REPEATER || kind == PhaseExtra.Kind.COMPARATOR;
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
            case OBSERVER -> "liquidphase:block/element_observer"
                + (PhaseExtra.facing(extra) == Direction.NORTH ? "" : "_" + PhaseExtra.facing(extra).getName())
                + (PhaseExtra.powered(extra) ? "_on" : "");
            default -> "liquidphase:block/dynamic";
        };
    }

    /** {@return 子模型路径（去掉 {@code liquidphase:} 前缀，动态模型表用它做键）} */
    public static String subModelPath(int extra) {
        String id = subModelId(extra);
        return id.startsWith("liquidphase:") ? id.substring("liquidphase:".length()) : id;
    }

    /**
     * {@return y 轴旋转角（度，按 {@code x' = x cosθ - z sinθ; z' = x sinθ + z cosθ} 绕方块中心旋转）}
     * <p>
     * 与原版 blockstate 的 {@code y} 值一致：中继器/比较器无旋转时 FACING=SOUTH。
     * 侦测器按朝向预烘焙，不走这条旋转。
     */
    public static float yawDegrees(int extra) {
        Direction facing = PhaseExtra.facing(extra);
        return switch (facing) {
            case SOUTH -> 0.0F;
            case NORTH -> 180.0F;
            case EAST -> 270.0F;
            case WEST -> 90.0F;
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
