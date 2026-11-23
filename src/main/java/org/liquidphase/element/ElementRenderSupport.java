package org.liquidphase.element;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseHelper;

/**
 * 元件/红石/材料相的渲染选择（纯逻辑，服务端也安全：不引用任何客户端类）。
 * <p>
 * 动态模型在区块构建期按坐标查旁路表与状态，选出要发射的子模型 id 与旋转角：
 * <ul>
 *   <li>红石：{@code liquidphase:block/redstone_phase_<power>}（16 档颜色），不旋转、不抬升；</li>
 *   <li>中继器：{@code liquidphase:block/element_repeater_<n>tick[_on][_locked]}；
 *       子模型只含杆与红条，按 1/16 像素分列，运行时逐列贴到宿主顶面；</li>
 *   <li>比较器：{@code liquidphase:block/element_comparator[_on][_subtract]}，同上；</li>
 *   <li>侦测器：{@code liquidphase:block/element_observer[_<facing>][_on]}；16×16 正常比例、
 *       固定在方块内（不抬升），只含侦测面五官与输出面输出口两个平面。
 *       **六个朝向各自预烘焙**，运行期不旋转（单面平面转向后容易被区块网格按旧朝向剔除）。</li>
 *   <li>材料相（0.2.5 起）：{@code liquidphase:block/phase_<obsidian|ice|frosted_0..3>}
 *       ——内缩 0.25px 的覆盖立方体。它不再写进 blockstate，而是由动态模型发射：
 *       这样每个 quad 可以**各自**按材质透明度选渲染图层，宿主几何保持原图层。</li>
 * </ul>
 * 中继器/比较器的 y 轴旋转按原版 blockstate 的 {@code y} 值。
 */
public final class ElementRenderSupport {

    /** 材料相覆盖层的内缩量（方块空间）：0.25px。 */
    public static final double OVERLAY_INSET = 0.25 / 16.0;
    private ElementRenderSupport() {
    }

    /** {@return 需要动态几何的条目} */
    public static boolean hasGeometry(int extra) {
        return PhaseExtra.isRedstone(extra) || PhaseExtra.isElement(extra);
    }

    /** {@return 该状态是否要在动态模型里画材料相覆盖层（黑曜石/冰/霜冰）} */
    public static boolean hasMaterialOverlay(BlockState state) {
        if (state == null || !state.hasProperty(PhaseHelper.FILLING)) {
            return false;
        }
        Phase phase = state.getValue(PhaseHelper.FILLING);
        return phase == Phase.OBSIDIAN || phase.isFrozen();
    }

    /** {@return 材料相覆盖层的子模型 id；无材料相返回 null} */
    public static String overlayModelId(BlockState state) {
        if (!hasMaterialOverlay(state)) {
            return null;
        }
        Phase phase = state.getValue(PhaseHelper.FILLING);
        return "liquidphase:block/phase_" + phase.getSerializedName();
    }

    /**
     * {@return 该材料相覆盖层应该用的渲染图层}
     * <p>
     * 冰/霜冰的原版贴图是**半透明**的（{@code ice.png} 整图 alpha=190，我实测过），
     * 必须走 {@code TRANSLUCENT}；黑曜石是不透明贴图，走 {@code CUTOUT} 保持与其它
     * cutout 面一致（也避免不透明面被丢进排序层）。0.2.5 起这是逐 quad 选择的，
     * 所以同一格里的"不透明宿主 + 半透明相"能各自正确显示。
     */
    public static OverlayLayer overlayLayer(BlockState state) {
        Phase phase = state == null || !state.hasProperty(PhaseHelper.FILLING)
            ? Phase.NONE : state.getValue(PhaseHelper.FILLING);
        return phase.isFrozen() ? OverlayLayer.TRANSLUCENT : OverlayLayer.CUTOUT;
    }

    /** 材料相覆盖层可选的渲染图层（与版本无关的极小枚举，避免纯逻辑类引用客户端类型）。 */
    public enum OverlayLayer {
        CUTOUT,
        TRANSLUCENT
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
        return stripNamespace(subModelId(extra));
    }

    /** {@return 任意 {@code liquidphase:} 模型 id 去掉前缀后的路径} */
    public static String stripNamespace(String id) {
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

    /**
     * {@return 该列宿主顶面高度（0–1）；空列按 0 处理}
     * <p>
     * ⚠️ 参数顺序陷阱（0.2.5 修）：{@code VoxelShape.max(Axis, a, b)} 的两个坐标**不是 (x, z)**，
     * 而是按 {@code AxisCycle.FORWARD/BACKWARD} 相对该轴取其余两轴，对 {@code Axis.Y} 而言
     * 依次是 **Z、X**（源码：{@code findIndex(AxisCycle.FORWARD.cycle(Y)=Z, a)}、
     * {@code findIndex(BACKWARD.cycle(Y)=X, b)}；1.19.4 的等价入口 {@code getEndingCoord}
     * 用的是同一套 AxisCycle）。传成 (x, z) 时 x/z 不对称的宿主（栅栏柱、台阶、楼梯、墙）
     * 会取到相邻列的高度，元件几何出现错位/悬空/埋入。
     * 六版本口径一致，所以这里统一按 (z, x) 传。
     */
    public static double columnTop(VoxelShape shape, double x, double z) {
        if (shape == null || shape.isEmpty()) {
            return 0.0;
        }
        // 顶点正好落在方块边界（x/z = 0 或 1）时列索引会越界，返回 NEGATIVE_INFINITY
        // （= 空列）——那样同一块模型只有内部顶点被抬升，边缘顶点留在原地，几何被拉花。
        // 夹到格子内部再采样。
        double sx = Math.max(1.0E-4, Math.min(1.0 - 1.0E-4, x));
        double sz = Math.max(1.0E-4, Math.min(1.0 - 1.0E-4, z));
        // 注意实参顺序是 (z, x)，见方法注释。
        double top = shape.max(Direction.Axis.Y, sz, sx);
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

    /** 供渲染缓存键使用：宿主形状 + 条目 + 材料相 → 稳定键。 */
    public record GeometryKey(int extra, long pos, int shapeIdentity, String overlay) {
    }
}
