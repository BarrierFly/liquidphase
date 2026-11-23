package org.liquidphase.client;

//? if 1.19.4 || 1.20.1 || 1.21.1 {
/*
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.liquidphase.element.ElementRenderSupport;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.phase.PhaseHelper;

// 动态相/元件渲染（1.19.4–1.21.1）：注册一个自定义 unbaked 模型接管
// liquidphase:block/dynamic，由 blockstate 里的无条件 part 触发。
// 这一代 FRAPI 的 emitBlockQuads(view, state, pos, random, ctx) 自带世界与坐标，
// 可以直接按旁路表发射红石外壳/元件子模型，并用 ctx.pushTransform 做旋转与逐列抬升。
//
// 这个文件整体包在注释里（活动版本 1.21.11 不编译它）。因此：
//  - 不要在里面写块注释，也不要用嵌套的 //? 分支（会提前闭合外层注释）；
//  - 三个旧版本的差异用"同时实现两个 bake 重载" + 反射构造 ResourceLocation 解决。
public final class PhaseDynamicModelLegacy {

    private static final ResourceLocation DYNAMIC = rl("block/dynamic");
    private static final List<ResourceLocation> SUB_MODELS = buildSubModelIds();

    private PhaseDynamicModelLegacy() {
    }

    private static ResourceLocation rl(String path) {
        try {
            Method of = ResourceLocation.class.getMethod("fromNamespaceAndPath", String.class, String.class);
            return (ResourceLocation) of.invoke(null, "liquidphase", path);
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Constructor<ResourceLocation> ctor = ResourceLocation.class.getConstructor(String.class, String.class);
            return ctor.newInstance("liquidphase", path);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("liquidphase: cannot create ResourceLocation", e);
        }
    }

    private static List<ResourceLocation> buildSubModelIds() {
        List<ResourceLocation> ids = new ArrayList<>(64);
        for (int power = 0; power < 16; power++) {
            ids.add(rl("block/redstone_phase_" + power));
        }
        for (int delay = 1; delay <= 4; delay++) {
            for (int variant = 0; variant < 4; variant++) {
                ids.add(rl("block/element_repeater_" + delay + "tick"
                    + ((variant & 1) != 0 ? "_on" : "") + ((variant & 2) != 0 ? "_locked" : "")));
            }
        }
        for (int variant = 0; variant < 4; variant++) {
            ids.add(rl("block/element_comparator"
                + ((variant & 1) != 0 ? "_on" : "") + ((variant & 2) != 0 ? "_subtract" : "")));
        }
        for (String suffix : new String[]{"", "_south", "_east", "_west", "_up", "_down"}) {
            ids.add(rl("block/element_observer" + suffix));
            ids.add(rl("block/element_observer" + suffix + "_on"));
        }
        // 材料相覆盖层（0.2.5）：黑曜石 + 冰 + 霜冰 4 档裂纹。
        for (String overlay : new String[]{"phase_obsidian", "phase_ice",
            "phase_frosted_0", "phase_frosted_1", "phase_frosted_2", "phase_frosted_3"}) {
            ids.add(rl("block/" + overlay));
        }
        return ids;
    }

    public static void register() {
        ModelLoadingPlugin.register(context -> context.resolveModel().register(modelContext ->
            DYNAMIC.equals(modelContext.id()) ? new DynamicUnbaked() : null));
    }

    private record DynamicUnbaked() implements UnbakedModel {

        @Override
        public Collection<ResourceLocation> getDependencies() {
            return SUB_MODELS;
        }

        @Override
        public void resolveParents(Function<ResourceLocation, UnbakedModel> resolver) {
            for (ResourceLocation model : SUB_MODELS) {
                resolver.apply(model);
            }
        }

        public BakedModel bake(ModelBaker baker, Function<Material, TextureAtlasSprite> textureGetter,
                               ModelState state) {
            Map<ResourceLocation, BakedModel> parts = new HashMap<>(SUB_MODELS.size() * 2);
            for (ResourceLocation model : SUB_MODELS) {
                parts.put(model, baker.bake(model, state));
            }
            return new DynamicBaked(parts);
        }

        public BakedModel bake(ModelBaker baker, Function<Material, TextureAtlasSprite> textureGetter,
                               ModelState state, ResourceLocation modelId) {
            return this.bake(baker, textureGetter, state);
        }
    }

    private record DynamicBaked(Map<ResourceLocation, BakedModel> parts) implements BakedModel, FabricBakedModel {

        @Override
        public boolean isVanillaAdapter() {
            return false;
        }

        @Override
        public void emitBlockQuads(BlockAndTintGetter view, BlockState state, BlockPos pos,
                                   Supplier<RandomSource> random, RenderContext context) {
            // 材料相覆盖层（0.2.5）：先发它，逐 quad 选图层（冰→TRANSLUCENT、黑曜石→CUTOUT）。
            emitOverlay(state, context);
            int extra = PhaseExtras.get(view, pos);
            if (!ElementRenderSupport.hasGeometry(extra)) {
                return;
            }
            BakedModel part = this.parts.get(rl(
                ElementRenderSupport.subModelId(extra).substring("liquidphase:".length())));
            if (part == null) {
                return;
            }
            if (!org.liquidphase.phase.PhaseExtra.isElement(extra)) {
                ((FabricBakedModel) part).emitBlockQuads(view, state, pos, random, context);
                return;
            }
            // 侦测器六个朝向都在烘焙期定好，运行期不旋转、不抬升，直接发几何。
            if (org.liquidphase.phase.PhaseExtra.kind(extra) == org.liquidphase.phase.PhaseExtra.Kind.OBSERVER) {
                ((FabricBakedModel) part).emitBlockQuads(view, state, pos, random, context);
                return;
            }
            VoxelShape shape = state.getCollisionShape(view, pos, CollisionContext.empty());
            float yaw = ElementRenderSupport.yawDegrees(extra);
            context.pushTransform(quad -> transformElement(quad, shape, yaw));
            try {
                ((FabricBakedModel) part).emitBlockQuads(view, state, pos, random, context);
            } finally {
                context.popTransform();
            }
        }

        @Override
        public void emitItemQuads(net.minecraft.world.item.ItemStack stack, Supplier<RandomSource> random,
                                  RenderContext context) {
        }

        // 材料相覆盖层（0.2.5）：把已烘焙的 phase_* 子模型的静态面按材质发出去，并逐 quad 选图层。
        // 这里不用 emitBlockQuads：覆盖层是纯静态几何（不含 tint/AO 依赖），直接取 getQuads
        // 逐面向 emitter 发射更简单，也避免把 null 世界传进去（子模型若走了需要
        // BlockAndTintGetter 的路径会 NPE）。
        // ≤1.21.1 的 FRAPI 通过 RenderMaterial 的 BlendMode 决定图层（Indigo 的
        // BlockRenderInfo.effectiveRenderLayer 按 quad 取值，再按图层取独立缓冲）。
        // 所以每个 quad 调一次 material(...) 即可——半透明冰与不透明宿主因此能各自正确显示。
        private void emitOverlay(BlockState state, RenderContext context) {
            String overlayId = ElementRenderSupport.overlayModelId(state);
            if (overlayId == null) {
                return;
            }
            BakedModel part = this.parts.get(rl(overlayId.substring("liquidphase:".length())));
            if (part == null) {
                return;
            }
            net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial material =
                overlayMaterial(ElementRenderSupport.overlayLayer(state));
            net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter emitter = context.getEmitter();
            emitStatic(part, null, null, material, emitter);
            for (Direction face : Direction.values()) {
                emitStatic(part, state, face, material, emitter);
            }
        }

        // 把 part 在某个方向上的静态面逐面翻制进 emitter，并打上材质。
        // 1.19.4–1.21.1 的 BakedQuad 顶点打包在 int[] 里（getVertices）。
        // fromVanilla(int[], int) 全版本都有（第二参 = 顶点数），材质再单独用 material(...)
        // 打上去——1.19.4 没有 fromVanilla(int[], material, face) 这个重载。
        private static void emitStatic(BakedModel part, BlockState state, Direction face,
                                       net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial material,
                                       net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter emitter) {
            // 覆盖层子模型只有一条 element、没有变体/随机，用固定种子即可（结果与种子无关）。
            for (BakedQuad quad : part.getQuads(state, face, RandomSource.create(42L))) {
                emitter.fromVanilla(quad.getVertices(), 4);
                emitter.material(material);
                emitter.nominalFace(face);
                emitter.emit();
            }
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
            return List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean isGui3d() {
            return false;
        }

        @Override
        public boolean usesBlockLight() {
            return true;
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return this.parts.values().iterator().next().getParticleIcon();
        }

        @Override
        public ItemTransforms getTransforms() {
            return ItemTransforms.NO_TRANSFORMS;
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }

    // 1/64 方块：足够跨过像素边界取样，又不会落进相邻像素列。
    private static final float INWARD = 1.0F / 64.0F;

    // 覆盖层用的两种渲染材质（0.2.5）：CUTOUT（不透明：黑曜石）与 TRANSLUCENT（半透明：冰/霜冰）。
    // 懒加载并缓存：渲染器要到渲染初始化之后才可用，不能在类初始化时调用。
    // 材质本身是全局只读对象，缓存安全。
    private static net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial overlayCutout;
    private static net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial overlayTranslucent;

    private static net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial overlayMaterial(
            ElementRenderSupport.OverlayLayer layer) {
        if (layer == ElementRenderSupport.OverlayLayer.TRANSLUCENT) {
            if (overlayTranslucent == null) {
                overlayTranslucent = net.fabricmc.fabric.api.renderer.v1.RendererAccess.INSTANCE.getRenderer()
                    .materialFinder()
                    .blendMode(net.fabricmc.fabric.api.renderer.v1.material.BlendMode.TRANSLUCENT)
                    .find();
            }
            return overlayTranslucent;
        }
        if (overlayCutout == null) {
            overlayCutout = net.fabricmc.fabric.api.renderer.v1.RendererAccess.INSTANCE.getRenderer()
                .materialFinder()
                .blendMode(net.fabricmc.fabric.api.renderer.v1.material.BlendMode.CUTOUT)
                .find();
        }
        return overlayCutout;
    }

    // 旋转并按列抬升一个元件小面（只用于中继器/比较器；侦测器已按朝向预烘焙）。
    // 子模型已经按 1/16 像素列切好，所以每个小面只取一个列高度、整面刚性平移
    // （而不是逐顶点插值）：这样跨台阶时每个像素列都真正搭在宿主顶面上，
    // 同一根杆/红条内部允许逐列落差。侧立面用面法线朝内偏移半格取样，避免正好落在
    // 像素边界上取到相邻列。
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
            Direction face = quad.nominalFace();
            if (face != null) {
                float nx = face.getStepX() * cosY - face.getStepZ() * sinY;
                float nz = face.getStepX() * sinY + face.getStepZ() * cosY;
                rx -= nx * INWARD;
                rz -= nz * INWARD;
            }
            lift = (float) ElementRenderSupport.columnTop(shape, rx, rz);
        }
        // 只改顶点位置不够：法线与朝向面一并旋转，否则光照会按模型空间的方向算。
        Direction rotatedFace = rotateDirection(quad.nominalFace(), cosY, sinY);
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
                quad.normal(i, nx * cosY - nz * sinY, quad.normalY(i), nx * sinY + nz * cosY);
            }
        }
        return true;
    }

    private static Direction rotateDirection(Direction face, float cosY, float sinY) {
        if (face == null) {
            return null;
        }
        float x = face.getStepX();
        float z = face.getStepZ();
        float x1 = x * cosY - z * sinY;
        float z1 = x * sinY + z * cosY;
        if (Math.abs(x1) >= Math.abs(z1)) {
            return x1 > 0.0F ? Direction.EAST : Direction.WEST;
        }
        return z1 > 0.0F ? Direction.SOUTH : Direction.NORTH;
    }
}

*///?}
