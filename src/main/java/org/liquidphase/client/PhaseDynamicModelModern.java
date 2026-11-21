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
import net.minecraft.world.level.BlockAndTintGetter;
//?}
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.core.BlockPos;
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

    /** 全部子模型（相对 {@code liquidphase:} 的路径）：16 外壳 + 16 中继器 + 4 比较器 + 2 侦测器。 */
    private static final String[] SUB_PATHS = buildSubPaths();

    private static final ModelBaker.SharedOperationKey<Map<String, Object>> PARTS_KEY =
        PhaseDynamicModelModern::bakeParts;

    private PhaseDynamicModelModern() {
    }

    private static String[] buildSubPaths() {
        String[] ids = new String[16 + 16 + 4 + 2];
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
        ids[i++] = "block/element_observer";
        ids[i++] = "block/element_observer_on";
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
            VoxelShape shape = state.getCollisionShape(view, pos, CollisionContext.empty());
            float yaw = ElementRenderSupport.yawDegrees(extra);
            emitter.pushTransform(quad -> liftToHost(quad, shape, yaw));
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

        @Override
        public Object createGeometryKey(BlockAndTintGetter view, BlockPos pos, BlockState state, RandomSource random) {
            int extra = PhaseExtras.get(view, pos);
            if (!ElementRenderSupport.hasGeometry(extra)) {
                return null;
            }
            VoxelShape shape = state.getCollisionShape(view, pos, CollisionContext.empty());
            return new ElementRenderSupport.GeometryKey(extra, pos.asLong(), System.identityHashCode(shape));
        }
    }

    /** 把子模型的每个顶点绕方块中心旋转，再抬到该列宿主顶面高度（逐列采样）。 */
    private static boolean liftToHost(MutableQuadView quad, VoxelShape shape, float yawDegrees) {
        double radians = Math.toRadians(yawDegrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        for (int i = 0; i < 4; i++) {
            float x = quad.x(i);
            float y = quad.y(i);
            float z = quad.z(i);
            float dx = x - 0.5F;
            float dz = z - 0.5F;
            float rx = 0.5F + dx * cos - dz * sin;
            float rz = 0.5F + dx * sin + dz * cos;
            double top = ElementRenderSupport.columnTop(shape, rx, rz);
            quad.pos(i, rx, (float) (y + top), rz);
        }
        return true;
    }
}

//?}
