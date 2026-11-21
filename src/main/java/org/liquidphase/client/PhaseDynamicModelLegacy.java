package org.liquidphase.client;

//? if 1.19.4 || 1.20.1 || 1.21.1 {
/*
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
public final class PhaseDynamicModelLegacy {

    private static final ResourceLocation DYNAMIC = new ResourceLocation("liquidphase", "block/dynamic");
    private static final List<ResourceLocation> SUB_MODELS = buildSubModelIds();

    private PhaseDynamicModelLegacy() {
    }

    private static List<ResourceLocation> buildSubModelIds() {
        List<ResourceLocation> ids = new ArrayList<>(38);
        for (int power = 0; power < 16; power++) {
            ids.add(new ResourceLocation("liquidphase", "block/redstone_phase_" + power));
        }
        for (int delay = 1; delay <= 4; delay++) {
            for (int variant = 0; variant < 4; variant++) {
                ids.add(new ResourceLocation("liquidphase", "block/element_repeater_" + delay + "tick"
                    + ((variant & 1) != 0 ? "_on" : "") + ((variant & 2) != 0 ? "_locked" : "")));
            }
        }
        for (int variant = 0; variant < 4; variant++) {
            ids.add(new ResourceLocation("liquidphase", "block/element_comparator"
                + ((variant & 1) != 0 ? "_on" : "") + ((variant & 2) != 0 ? "_subtract" : "")));
        }
        ids.add(new ResourceLocation("liquidphase", "block/element_observer"));
        ids.add(new ResourceLocation("liquidphase", "block/element_observer_on"));
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

        @Override
        public BakedModel bake(ModelBaker baker, Function<Material, TextureAtlasSprite> textureGetter,
                               ModelState state
            //? if 1.19.4 || 1.20.1 {
            , ResourceLocation modelId
            //?}
        ) {
            Map<ResourceLocation, BakedModel> parts = new HashMap<>(SUB_MODELS.size() * 2);
            for (ResourceLocation model : SUB_MODELS) {
                parts.put(model, baker.bake(model, state));
            }
            return new DynamicBaked(parts);
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
            BakedModel part = this.parts.get(new ResourceLocation("liquidphase",
                ElementRenderSupport.subModelId(extra).substring("liquidphase:".length())));
            if (part == null) {
                return;
            }
            if (!org.liquidphase.phase.PhaseExtra.isElement(extra)) {
                ((FabricBakedModel) part).emitBlockQuads(view, state, pos, random, context);
                return;
            }
            VoxelShape shape = state.getCollisionShape(view, pos, CollisionContext.empty());
            float yaw = ElementRenderSupport.yawDegrees(extra);
            context.pushTransform(quad -> liftToHost(quad, shape, yaw));
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

*///?}
