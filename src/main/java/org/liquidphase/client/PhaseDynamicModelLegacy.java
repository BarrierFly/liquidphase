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
        List<ResourceLocation> ids = new ArrayList<>(48);
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
