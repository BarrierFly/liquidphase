package org.liquidphase.client;

//? if 1.21.10 || 1.21.11 || 26.3 {

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
//? if 26.3 {
/*import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModelPart;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.block.dispatch.Variant;
import net.minecraft.client.resources.model.sprite.Material;
*///?} else {
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockModelPart;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.block.model.Variant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.BlockAndTintGetter;
//?}
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.element.ElementRenderSupport;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.phase.PhaseHelper;

/**
 * 动态相/元件渲染（1.21.10+）：在 {@code BlockStateModel} 层给宿主模型追加几何。
 * <p>
 * 这一代 {@code BlockModelPart} 的 {@code emitQuads} 拿不到世界与坐标，所以"给 blockstate
 * 追加一个动态 part"行不通；改为用 {@code modifyBlockModelOnLoad} 把宿主状态模型包一层，
 * 在带坐标的 {@link FabricBlockStateModel#emitQuads} 里按旁路表发射红石外壳/元件子模型。
 * <p>
 * 子模型只烘一次（{@link ModelBaker.SharedOperationKey}，每次资源重载一份），
 * 元件的 y 轴旋转与"逐列贴合宿主顶面"都在 {@link QuadEmitter#pushTransform} 里做。
 */
public final class PhaseDynamicModelModern {

    /** 全部子模型（相对 {@code liquidphase:} 的路径）：16 外壳 + 16 中继器 + 4 比较器 + 12 侦测器 + 6 材料相。 */
    private static final String[] SUB_PATHS = buildSubPaths();

    private static final ModelBaker.SharedOperationKey<Map<String, Object>> PARTS_KEY =
        PhaseDynamicModelModern::bakeParts;

    private PhaseDynamicModelModern() {
    }

    private static String[] buildSubPaths() {
        String[] facings = {"", "_south", "_east", "_west", "_up", "_down"};
        String[] overlays = {"phase_obsidian", "phase_ice",
            "phase_frosted_0", "phase_frosted_1", "phase_frosted_2", "phase_frosted_3"};
        String[] ids = new String[16 + 16 + 4 + facings.length * 2 + overlays.length];
        int i = 0;
        for (int power = 0; power < 16; power++) {
            ids[i++] = "block/redstone_phase_" + power;
        }
        for (int delay = 1; delay <= 4; delay++) {
            for (int variant = 0; variant < 4; variant++) {
                ids[i++] = "block/element_repeater_" + delay + "tick"
                    + ((variant & 1) != 0 ? "_on" : "") + ((variant & 2) != 0 ? "_locked" : "");
            }
        }
        for (int variant = 0; variant < 4; variant++) {
            ids[i++] = "block/element_comparator" + ((variant & 1) != 0 ? "_on" : "")
                + ((variant & 2) != 0 ? "_subtract" : "");
        }
        for (String suffix : facings) {
            ids[i++] = "block/element_observer" + suffix;
            ids[i++] = "block/element_observer" + suffix + "_on";
        }
        // 材料相覆盖层（0.2.5）：由动态模型发射，逐 quad 选图层。
        for (String overlay : overlays) {
            ids[i++] = "block/" + overlay;
        }
        return ids;
    }

    //? if 1.21.10 {
    /*private static net.minecraft.resources.ResourceLocation id(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("liquidphase", path);
    }
     *///?} else {
    private static net.minecraft.resources.Identifier id(String path) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath("liquidphase", path);
    }
    //?}

    // 值类型故意用 Object：1.21.10/11 是 BlockModelPart、26.3 是 BlockStateModelPart，
    // 而真正发射时统一走 FabricBlockModelPart / FabricBlockStateModelPart 接口。
    private static Map<String, Object> bakeParts(ModelBaker baker) {
        Map<String, Object> baked = new HashMap<>(SUB_PATHS.length * 2);
        for (String path : SUB_PATHS) {
            baked.put(path, new Variant(id(path)).bake(baker));
        }
        return baked;
    }

    public static void register() {
        ModelLoadingPlugin.register(context ->
            context.modifyBlockModelOnLoad().register(ModelModifier.WRAP_PHASE,
                (root, ctx) -> ctx.state().hasProperty(PhaseHelper.FILLING) ? new WrappedRoot(root) : root));
    }

    // ------------------------------------------------------------------
    // UnbakedRoot 包装：只声明依赖，烘焙时换成我们的模型
    // ------------------------------------------------------------------

    private record WrappedRoot(BlockStateModel.UnbakedRoot wrapped) implements BlockStateModel.UnbakedRoot {

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            this.wrapped.resolveDependencies(resolver);
            for (String path : SUB_PATHS) {
                resolver.markDependency(id(path));
            }
        }

        @Override
        public Object visualEqualityGroup(BlockState state) {
            return this.wrapped.visualEqualityGroup(state);
        }

        @Override
        public BlockStateModel bake(BlockState state, ModelBaker baker) {
            return new DynamicModel(this.wrapped.bake(state, baker), baker.compute(PARTS_KEY));
        }
    }

    // ------------------------------------------------------------------
    // 动态模型
    // ------------------------------------------------------------------

    private record DynamicModel(BlockStateModel base, Map<String, Object> parts)
        implements FabricBlockStateModel, BlockStateModel {

        //? if 26.3 {
        /*@Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> list) {
            this.base.collectParts(random, list);
        }
         *///?} else {
        @Override
        public void collectParts(RandomSource random, List<BlockModelPart> list) {
            this.base.collectParts(random, list);
        }
        //?}

        //? if 26.3 {
        /*@Override
        public Material.Baked particleMaterial() {
            return this.base.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return this.base.materialFlags();
        }
         *///?} else {
        @Override
        public net.minecraft.client.renderer.texture.TextureAtlasSprite particleIcon() {
            return this.base.particleIcon();
        }
        //?}

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter view, BlockPos pos, BlockState state,
                              RandomSource random, Predicate<net.minecraft.core.Direction> cullTest) {
            ((FabricBlockStateModel) this.base).emitQuads(emitter, view, pos, state, random, cullTest);
            // 材料相覆盖层（0.2.5）：逐 quad 选图层（冰/霜冰→TRANSLUCENT、黑曜石→CUTOUT）。
            emitOverlay(emitter, state);
            int extra = PhaseExtras.get(view, pos);
            if (!ElementRenderSupport.hasGeometry(extra)) {
                return;
            }
            Object part = this.parts.get(ElementRenderSupport.subModelPath(extra));
            if (part == null) {
                return;
            }
            if (!PhaseExtra.isElement(extra)) {
                //? if 26.3 {
                /*((FabricBlockStateModelPart) part).emitQuads(emitter, direction -> false);
                 *///?} else {
                ((FabricBlockModelPart) part).emitQuads(emitter, direction -> false);
                //?}
                return;
            }
            // 侦测器六个朝向都在烘焙期定好，运行期不旋转、不抬升，直接发几何。
            if (PhaseExtra.kind(extra) == PhaseExtra.Kind.OBSERVER) {
                //? if 26.3 {
                /*((FabricBlockStateModelPart) part).emitQuads(emitter, direction -> false);
                 *///?} else {
                ((FabricBlockModelPart) part).emitQuads(emitter, direction -> false);
                //?}
                return;
            }
            VoxelShape shape = state.getCollisionShape(view, pos, CollisionContext.empty());
            float yaw = ElementRenderSupport.yawDegrees(extra);
            emitter.pushTransform(quad -> transformElement(quad, shape, yaw));
            try {
                //? if 26.3 {
                /*((FabricBlockStateModelPart) part).emitQuads(emitter, direction -> false);
                 *///?} else {
                ((FabricBlockModelPart) part).emitQuads(emitter, direction -> false);
                //?}
            } finally {
                emitter.popTransform();
            }
        }

        /**
         * 材料相覆盖层（0.2.5）：把 {@code phase_*} 子模型发进 emitter，并按材质透明度逐 quad 选图层。
         * <p>
         * 用 {@code pushTransform} 逐 quad 覆盖图层：{@code 1.21.10/1.21.11} 是
         * {@code renderLayer(ChunkSectionLayer)}，{@code 26.3} 改名为 {@code chunkLayer}。
         * 两者都作用在单个 quad 上，所以同一格里"不透明宿主 + 半透明冰"能各自正确显示
         * （静态 blockstate part 只能整块状态一个图层，这就是 0.2.4 及以前冰变死白、
         * 玻璃宿主上的黑曜石被拖进排序层的原因）。
         * <p>
         * 覆盖层子模型只有一条 element、没有变体，直接用 {@code emitQuads} 发，
         * 不需要世界与坐标（它也不带 tint/cullface）。
         */
        private void emitOverlay(QuadEmitter emitter, BlockState state) {
            String overlayId = ElementRenderSupport.overlayModelId(state);
            if (overlayId == null) {
                return;
            }
            Object part = this.parts.get(ElementRenderSupport.stripNamespace(overlayId));
            if (part == null) {
                return;
            }
            ElementRenderSupport.OverlayLayer layer = ElementRenderSupport.overlayLayer(state);
            ChunkSectionLayer chunkLayer = layer == ElementRenderSupport.OverlayLayer.TRANSLUCENT
                ? ChunkSectionLayer.TRANSLUCENT : ChunkSectionLayer.CUTOUT;
            //? if 26.3 {
            /*emitter.pushTransform(quad -> {
                quad.chunkLayer(chunkLayer);
                return true;
            });
            try {
                ((FabricBlockStateModelPart) part).emitQuads(emitter, direction -> false);
            } finally {
                emitter.popTransform();
            }
             *///?} else {
            emitter.pushTransform(quad -> {
                quad.renderLayer(chunkLayer);
                return true;
            });
            try {
                ((FabricBlockModelPart) part).emitQuads(emitter, direction -> false);
            } finally {
                emitter.popTransform();
            }
            //?}
        }

        @Override
        public Object createGeometryKey(BlockAndTintGetter view, BlockPos pos, BlockState state, RandomSource random) {
            int extra = PhaseExtras.get(view, pos);
            String overlay = ElementRenderSupport.overlayModelId(state);
            if (!ElementRenderSupport.hasGeometry(extra) && overlay == null) {
                return null;
            }
            // 只有中继器/比较器依赖宿主形状；侦测器固定在方块内，形状变化不应导致重画。
            VoxelShape shape = ElementRenderSupport.terrainLift(extra)
                ? state.getCollisionShape(view, pos, CollisionContext.empty()) : null;
            return new ElementRenderSupport.GeometryKey(extra, pos.asLong(),
                shape == null ? 0 : System.identityHashCode(shape), overlay);
        }
    }

    /** 1/64 方块：足够跨过像素边界取样，又不会落进相邻像素列。 */
    private static final float INWARD = 1.0F / 64.0F;

    /**
     * 旋转并按列抬升一个元件小面（只用于中继器/比较器；侦测器已按朝向预烘焙）。
     * <p>
     * 子模型已经按 1/16 像素列切好，所以每个小面只取**一个**列高度、整面刚性平移
     * （而不是逐顶点插值）：这样跨台阶时每个像素列都真正搭在宿主顶面上，
     * 同一根杆/红条内部允许逐列落差。侧立面用面法线朝内偏移半格取样，避免正好落在
     * 像素边界上取到相邻列。
     */
    private static boolean transformElement(MutableQuadView quad, VoxelShape shape, float yawDegrees) {
        double yawRadians = Math.toRadians(yawDegrees);
        float cosY = (float) Math.cos(yawRadians);
        float sinY = (float) Math.sin(yawRadians);
        float lift = 0.0F;
        if (shape != null && !shape.isEmpty()) {
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                minX = Math.min(minX, quad.x(i));
                maxX = Math.max(maxX, quad.x(i));
                minZ = Math.min(minZ, quad.z(i));
                maxZ = Math.max(maxZ, quad.z(i));
            }
            float dx = (minX + maxX) * 0.5F - 0.5F;
            float dz = (minZ + maxZ) * 0.5F - 0.5F;
            float rx = 0.5F + dx * cosY - dz * sinY;
            float rz = 0.5F + dx * sinY + dz * cosY;
            net.minecraft.core.Direction face = quad.nominalFace();
            if (face != null) {
                float nx = face.getStepX() * cosY - face.getStepZ() * sinY;
                float nz = face.getStepX() * sinY + face.getStepZ() * cosY;
                rx -= nx * INWARD;
                rz -= nz * INWARD;
            }
            lift = (float) ElementRenderSupport.columnTop(shape, rx, rz);
        }
        // 只改顶点位置不够：法线与朝向面一并旋转，否则光照会按模型空间的方向算。
        net.minecraft.core.Direction rotatedFace = rotateDirection(quad.nominalFace(), cosY, sinY);
        if (rotatedFace != null) {
            quad.nominalFace(rotatedFace);
        }
        for (int i = 0; i < 4; i++) {
            float x = quad.x(i) - 0.5F;
            float z = quad.z(i) - 0.5F;
            float x1 = x * cosY - z * sinY;
            float z1 = x * sinY + z * cosY;
            quad.pos(i, x1 + 0.5F, quad.y(i) + lift, z1 + 0.5F);
            if (quad.hasNormal(i)) {
                float nx = quad.normalX(i);
                float nz = quad.normalZ(i);
                quad.normal(i, nx * cosY - nz * sinY, quad.normalY(i),
                    nx * sinY + nz * cosY);
            }
        }
        return true;
    }

    private static net.minecraft.core.Direction rotateDirection(net.minecraft.core.Direction face,
                                                                float cosY, float sinY) {
        if (face == null) {
            return null;
        }
        float x = face.getStepX();
        float z = face.getStepZ();
        float x1 = x * cosY - z * sinY;
        float z1 = x * sinY + z * cosY;
        if (Math.abs(x1) >= Math.abs(z1)) {
            return x1 > 0.0F ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST;
        }
        return z1 > 0.0F ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
    }
}

//?}
