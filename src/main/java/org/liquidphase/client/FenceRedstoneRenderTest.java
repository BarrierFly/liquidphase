//? if 1.21.11 {
package org.liquidphase.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableMesh;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBlockStateModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.sprite.FabricSpriteAtlasTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.liquidphase.LiquidPhase;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 客户端渲染回归：含红石相的**栅栏**必须真的画得出来。
 * <p>
 * 栅栏是"形状表型宿主"（{@code FenceBlock} 自建 occlusion 形状表，1.21.10+ 由
 * {@code getOcclusionShape} 手工缓存），含相时最容易渲染不出来；这里用**真实客户端**
 * 把它钉住，而不是只看 jar 里的 blockstate JSON。
 * <p>
 * 断言（任一条失败即让 run 失败并逐条打印 FAIL 行）：
 * <ol>
 *   <li>客户端烘焙模型里**既有宿主 quad、也有** {@code redstone_phase_*} 外壳 quad
 *       （外壳没丢，宿主也没被外壳顶掉）；方案 C 下外壳由动态模型在带坐标的
 *       {@code emitQuads} 里按旁路表发射，所以这里走 FRAPI 的发射结果而不是静态 part</li>
 *   <li>宿主状态的区块图层不是 {@code SOLID}（外壳是透明底贴图，SOLID 会丢弃透明像素）</li>
 *   <li>外壳 quad 顶点不越出本格（方块空间 0..1，允许 0.01px 外扩）</li>
 * </ol>
 * 同时落三张截图到运行目录的 {@code screenshots/}：斜视、俯视、0 档对照。
 * <p>
 * 跑法：{@code ./gradlew :1.21.11:runClientGameTest}（run config 见 {@code build.gradle.kts}）。
 * 运行目录 {@code versions/<版本>/run-clientgametest/} 与常规 {@code run/} 刻意分开：
 * 新目录里没有 {@code resourcepacks/}，等于"无资源包"的干净客户端。
 * <p>
 * 只在 1.21.11 接线：{@code FabricBlockStateModel.emitQuads} / {@code SpriteFinder} /
 * {@code ChunkSectionLayer} 是这一版起的 API（1.21.10 的动态模型还没有带坐标的
 * {@code emitQuads}）。其它版本的渲染覆盖由服务端自检与 {@code tools/verify_jars.py} 承担。
 */
public final class FenceRedstoneRenderTest implements FabricClientGameTest {

    /** 含相栅栏的格数（每格功率递减 1）。 */
    private static final int FENCE_COUNT = 6;
    /** 外壳几何允许的越界量（方块空间）：生成器按 0.01px 外扩。 */
    private static final double SHELL_SLACK = 0.02;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitTicks(20);
        context.runOnClient(client -> client.options.hideGui = true);

        try (TestSingleplayerContext sp = context.worldBuilder()
                .adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
                .create()) {
            sp.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null);

            BlockPos feet = context.computeOnClient(client -> client.player.blockPosition());
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("weather clear");

            BlockPos bright = feet;
            BlockPos dark = feet.offset(0, 0, 40);

            sp.getServer().runOnServer(server -> {
                buildScene(server, bright, true);
                buildScene(server, dark, false);
                buildElementScene(server, bright);
                buildOverlayScene(server, bright);
            });
            // 侦测器是 2 tick 开、2 tick 关的脉冲：必须在脉冲窗口内读（中继器 2 tick 后也亮了）。
            context.waitTicks(3);
            for (String line : sp.getServer().computeOnServer(server -> readBackElements(server, bright))) {
                LiquidPhase.LOGGER.info("[gametest] {}", line);
            }
            context.waitTicks(9);

            for (String line : sp.getServer().computeOnServer(server -> readBackPhases(server, bright))) {
                LiquidPhase.LOGGER.info("[gametest] {}", line);
            }

            List<String> failures = new ArrayList<>();
            for (String line : context.computeOnClient(client -> inspect(client, bright))) {
                LiquidPhase.LOGGER.info("[gametest] {}", line);
                if (line.contains("FAIL")) {
                    failures.add(line);
                }
            }
            for (String line : context.computeOnClient(client -> inspectElements(client, bright))) {
                LiquidPhase.LOGGER.info("[gametest] {}", line);
                if (line.contains("FAIL")) {
                    failures.add(line);
                }
            }
            for (String line : context.computeOnClient(client -> inspectOverlay(client, bright))) {
                LiquidPhase.LOGGER.info("[gametest] {}", line);
                if (line.contains("FAIL")) {
                    failures.add(line);
                }
            }

            String rebuildFailure = runSectionRebuildRegression(context, sp, bright.offset(0, 0, 2));
            if (rebuildFailure != null) {
                LiquidPhase.LOGGER.info("[gametest] {}", rebuildFailure);
                failures.add(rebuildFailure);
            }

            // 侦测器脉冲用的东侧石头会挡住观察面五官；照片阶段移走（移除本身也会触发一次 updateShape）。
            sp.getServer().runOnServer(server -> {
                ServerLevel level = server.getLevel(Level.OVERWORLD);
                if (level != null) {
                    level.setBlockAndUpdate(
                        bright.offset(36, 0, 21).relative(net.minecraft.core.Direction.EAST),
                        Blocks.AIR.defaultBlockState());
                }
            });
            sp.getClientWorld().waitForChunksRender();

            shot(context, sp, bright, -3.5, 1.0, 1.2, 0.5, 1.0, 4.5, "fence_redstone_oblique");
            shot(context, sp, bright, 0.5, 3.2, 4.5, 0.5, 0.0, 4.5, "fence_redstone_topdown");
            shot(context, sp, dark, -3.5, 1.0, 1.2, 0.5, 1.0, 4.5, "fence_redstone_power0");
            // 元件场景（x+36, z+20..22）：斜视近景 + 俯视，便于人工复核杆/红条/五官/输出口。
            shot(context, sp, bright.offset(36, 0, 20), 2.0, 1.4, 3.0, 0.5, 0.55, 1.0,
                "element_oblique");
            shot(context, sp, bright.offset(36, 0, 20), 0.5, 2.6, 2.6, 0.5, 0.2, 1.0,
                "element_topdown");
            // 侦测器是 facing=east：从东侧看观察面（五官），从西侧看输出口。
            shot(context, sp, bright.offset(36, 0, 20), 3.0, 1.1, 1.5, 0.5, 0.6, 1.5,
                "observer_face_east");
            shot(context, sp, bright.offset(36, 0, 20), -2.0, 1.1, 1.5, 0.5, 0.6, 1.5,
                "observer_port_west");
            // 下半楼梯上的中继器：杆/红条横跨台阶，用于人工复核逐像素落差。
            shot(context, sp, bright.offset(36, 0, 23), 2.2, 1.4, 3.0, 0.5, 0.8, 0.5,
                "element_stair");
            // 透明度分离：冰（半透明相）落在下半砖上、黑曜石（不透明相）落在玻璃板上。
            shot(context, sp, bright.offset(36, 0, 30), 2.0, 1.4, 2.6, 0.5, 0.6, 1.0,
                "overlay_translucency");

            if (!failures.isEmpty()) {
                throw new AssertionError("fence redstone phase rendering broken:\n  "
                        + String.join("\n  ", failures));
            }
            LiquidPhase.LOGGER.info("[gametest] fence redstone phase renders: host + shell quads, non-SOLID "
                    + "layer, shell inside block bounds");
        }
    }

    /**
     * 场景：地板 + 一列含相栅栏 + 紧邻的一列红石线。
     *
     * @param wire true 时栅栏先写红石相（旁路表，0 档），再由真实红石线算出功率（走完整生成链路）；
     *             false 时不放线，栅栏停在 0 档（外壳为暗红，用作对比截图）
     */
    private static void buildScene(MinecraftServer server, BlockPos origin, boolean wire) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            throw new IllegalStateException("no overworld");
        }
        for (int x = -6; x <= 6; x++) {
            for (int z = -3; z <= 12; z++) {
                level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        for (int i = 0; i < FENCE_COUNT; i++) {
            BlockPos pos = origin.offset(0, 0, 2 + i);
            level.setBlockAndUpdate(pos, Blocks.OAK_FENCE.defaultBlockState());
            // 方案 C：红石相写旁路表（服务端权威），不动方块状态。
            RedstoneBlocks.setRedstonePhase(level, pos, 0);
        }
        // 对照组：同一列后面放两段无相栅栏
        for (int i = 0; i < 2; i++) {
            level.setBlockAndUpdate(origin.offset(0, 0, 2 + FENCE_COUNT + i),
                    Blocks.OAK_FENCE.defaultBlockState());
        }
        if (wire) {
            for (int i = 0; i < FENCE_COUNT; i++) {
                level.setBlockAndUpdate(origin.offset(-1, 0, 2 + i), Blocks.REDSTONE_WIRE.defaultBlockState());
            }
            // 电源最后放：每次放置都会 notify 邻居，栅栏于是按真实功率结算
            level.setBlockAndUpdate(origin.offset(-1, 0, 1), Blocks.REDSTONE_BLOCK.defaultBlockState());
        }
    }

    private static List<String> readBackPhases(MinecraftServer server, BlockPos origin) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < FENCE_COUNT + 2; i++) {
            BlockPos pos = origin.offset(0, 0, 2 + i);
            BlockState state = level.getBlockState(pos);
            out.add("z=%d block=%s filling=%s extra=%s".formatted(
                    2 + i, BuiltInRegistries.BLOCK.getKey(state.getBlock()), PhaseHelper.getPhase(state),
                    PhaseExtra.describe(PhaseExtras.get(level, pos))));
        }
        return out;
    }

    private static List<String> inspect(Minecraft mc, BlockPos origin) {
        List<String> out = new ArrayList<>();
        BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
        TextureAtlas atlas = (TextureAtlas) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        SpriteFinder spriteFinder = atlas instanceof FabricSpriteAtlasTexture fabricAtlas
            ? fabricAtlas.spriteFinder() : null;
        MutableMesh mesh;
        try {
            mesh = Renderer.get().mutableMesh();
        } catch (RuntimeException e) {
            mesh = null;
        }
        for (int i = 0; i < FENCE_COUNT + 2; i++) {
            BlockPos pos = origin.offset(0, 0, 2 + i);
            BlockState state = mc.level.getBlockState(pos);
            int extra = PhaseExtras.get(mc.level, pos);
            boolean redstone = PhaseExtra.isRedstone(extra);

            BlockStateModel model = shaper.getBlockModel(state);
            if (model == null) {
                out.add("z=%d FAIL null baked model".formatted(20 + i));
                continue;
            }
            if (spriteFinder == null || mesh == null) {
                out.add("z=%d FAIL dynamic model output unavailable (spriteFinder=%s, renderer=%s)"
                        .formatted(2 + i, spriteFinder != null, mesh != null));
                continue;
            }

            int[] hostQuads = {0};
            int[] shellQuads = {0};
            double[] bounds = {
                Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
            };
            mesh.clear();
            RandomSource random = RandomSource.create();
            // 1.21.11 的动态模型在带坐标的 emitQuads 里按旁路表发射红石外壳/元件几何，
            // 不能用 collectParts（那一层拿不到坐标，只会返回宿主模型）。
            ((FabricBlockStateModel) model).emitQuads(mesh.emitter(), mc.level, pos, state, random,
                direction -> false);
            mesh.forEachMutable(quad -> {
                TextureAtlasSprite sprite = spriteFinder.find(quad);
                if (sprite == null || !sprite.contents().name().toString().contains("redstone_phase_")) {
                    hostQuads[0]++;
                    return;
                }
                shellQuads[0]++;
                for (int v = 0; v < 4; v++) {
                    bounds[0] = Math.min(bounds[0], quad.x(v));
                    bounds[1] = Math.min(bounds[1], quad.y(v));
                    bounds[2] = Math.min(bounds[2], quad.z(v));
                    bounds[3] = Math.max(bounds[3], quad.x(v));
                    bounds[4] = Math.max(bounds[4], quad.y(v));
                    bounds[5] = Math.max(bounds[5], quad.z(v));
                }
            });

            ChunkSectionLayer layer = ItemBlockRenderTypes.getChunkRenderType(state);
            out.add("z=%d extra=%s hostQuads=%d shellQuads=%d layer=%s".formatted(
                    2 + i, PhaseExtra.describe(extra), hostQuads[0], shellQuads[0], layer));

            if (!redstone) {
                continue;   // 无相对照组只打印，不做外壳断言
            }
            if (hostQuads[0] == 0) {
                out.add("z=%d FAIL host model disappeared under redstone phase".formatted(20 + i));
            }
            if (shellQuads[0] == 0) {
                out.add("z=%d FAIL no redstone_phase_* quad (shell invisible)".formatted(20 + i));
            }
            if (layer == ChunkSectionLayer.SOLID) {
                out.add("z=%d FAIL phase host still on SOLID layer (transparent shell pixels dropped)"
                        .formatted(20 + i));
            }
            if (shellQuads[0] > 0 && (bounds[0] < -SHELL_SLACK || bounds[1] < -SHELL_SLACK
                    || bounds[2] < -SHELL_SLACK || bounds[3] > 1 + SHELL_SLACK
                    || bounds[4] > 1 + SHELL_SLACK || bounds[5] > 1 + SHELL_SLACK)) {
                out.add("z=%d FAIL shell quads escape the block: x=[%.4f,%.4f] y=[%.4f,%.4f] z=[%.4f,%.4f]"
                        .formatted(2 + i, bounds[0], bounds[3], bounds[1], bounds[4], bounds[2], bounds[5]));
            }
        }
        return out;
    }

    /** 元件场景：三个下半砖，各放一个侦测器/中继器/比较器元件（方案 C）。 */
    private static void buildElementScene(MinecraftServer server, BlockPos origin) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        org.liquidphase.phase.PhaseExtra.Kind[] kinds = {
            org.liquidphase.phase.PhaseExtra.Kind.REPEATER,
            org.liquidphase.phase.PhaseExtra.Kind.OBSERVER,
            org.liquidphase.phase.PhaseExtra.Kind.COMPARATOR,
        };
        net.minecraft.core.Direction[] facings = {
            net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.EAST,
            net.minecraft.core.Direction.WEST,
        };
        for (int i = 0; i < kinds.length; i++) {
            BlockPos pos = origin.offset(36, 0, 20 + i);
            // 薄地板：只为截图有参照，不影响元件信号。
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.setBlockAndUpdate(pos.offset(dx, -1, dz), Blocks.SMOOTH_STONE.defaultBlockState());
                }
            }
            BlockState slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
            level.setBlockAndUpdate(pos, slab);
            org.liquidphase.element.ElementBlocks.place(level, pos, slab, kinds[i], facings[i]);
        }
        // 第 4 个：下半楼梯（台阶升向北侧）。中继器朝 EAST，两根杆与红条横跨台阶边界，
        // 用来验证"每个 1/16 像素列各自搭在宿主顶面"（北侧杆/红条应抬到 y=1.0）。
        BlockPos stairPos = origin.offset(36, 0, 23);
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        level.setBlockAndUpdate(stairPos.below(), Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(stairPos, stair);
        org.liquidphase.element.ElementBlocks.place(
            level, stairPos, stair, org.liquidphase.phase.PhaseExtra.Kind.REPEATER, Direction.EAST);
        // 中继器输入：facing 侧放红石块，2 tick 后 powered。
        BlockPos repeaterPos = origin.offset(36, 0, 20);
        level.setBlockAndUpdate(repeaterPos.relative(net.minecraft.core.Direction.NORTH),
            Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.updateNeighborsAt(repeaterPos, Blocks.SMOOTH_STONE_SLAB);
        // 侦测器观察面（EAST）邻居变化：updateShape → 2 tick 后 powered。
        // 坐标必须与上面元件场景一致（x+36）；旧值 x+4 差 32 格，邻居变化从未落到侦测器上。
        BlockPos observerPos = origin.offset(36, 0, 21);
        level.setBlockAndUpdate(observerPos.relative(net.minecraft.core.Direction.EAST),
            Blocks.STONE.defaultBlockState());
    }

    /**
     * 透明度分离场景（0.2.5）：一列两种材料相——冰（半透明贴图 alpha=190）落在不透明的下半砖上，
     * 黑曜石（不透明）落在半透明玻璃板上。覆盖层由动态模型逐 quad 选图层，
     * 所以"一相有透明度、一相不透明"应当各自正确显示。
     */
    private static void buildOverlayScene(MinecraftServer server, BlockPos origin) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        BlockPos floor = origin.offset(36, -1, 30);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                level.setBlockAndUpdate(floor.offset(dx, 0, dz), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(origin.offset(36, 0, 30),
            org.liquidphase.phase.PhaseHelper.withPhase(Blocks.SMOOTH_STONE_SLAB.defaultBlockState(),
                org.liquidphase.phase.Phase.ICE));
        level.setBlockAndUpdate(origin.offset(36, 0, 31),
            org.liquidphase.phase.PhaseHelper.withPhase(Blocks.GLASS_PANE.defaultBlockState(),
                org.liquidphase.phase.Phase.OBSIDIAN));
    }

    /**
     * 断言覆盖层几何真的由动态模型发出，并且按相选对了图层。
     * <p>
     * 冰的贴图是半透明的（alpha=190），走 CUTOUT 会被画成不透明死白；黑曜石是不透明贴图，
     * 走 TRANSLUCENT 会被拖进排序层。0.2.4 及以前覆盖层是静态 blockstate part，
     * 整块状态只有一个图层，做不到这一点。
     */
    private static List<String> inspectOverlay(Minecraft mc, BlockPos origin) {
        List<String> out = new ArrayList<>();
        BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
        TextureAtlas atlas = (TextureAtlas) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        SpriteFinder spriteFinder = atlas instanceof FabricSpriteAtlasTexture fabricAtlas
            ? fabricAtlas.spriteFinder() : null;
        if (spriteFinder == null) {
            out.add("overlay FAIL no sprite finder");
            return out;
        }
        MutableMesh mesh = Renderer.get().mutableMesh();
        String[][] cases = {
            {"30", "ice", "TRANSLUCENT"},
            {"31", "obsidian", "CUTOUT"},
        };
        for (String[] c : cases) {
            BlockPos pos = origin.offset(36, 0, Integer.parseInt(c[0]));
            BlockState state = mc.level.getBlockState(pos);
            BlockStateModel model = shaper.getBlockModel(state);
            int[] overlayQuads = {0};
            double[] minY = {Double.MAX_VALUE};
            double[] maxY = {-Double.MAX_VALUE};
            java.util.Set<String> layers = new java.util.HashSet<>();
            mesh.clear();
            ((FabricBlockStateModel) model).emitQuads(mesh.emitter(), mc.level, pos, state,
                RandomSource.create(), direction -> false);
            mesh.forEachMutable(quad -> {
                TextureAtlasSprite sprite = spriteFinder.find(quad);
                String name = sprite == null ? "" : sprite.contents().name().toString();
                if (!name.contains(c[1])) {
                    return;
                }
                overlayQuads[0]++;
                //? if 26.3 {
                /*if (quad.chunkLayer() != null) {
                    layers.add(quad.chunkLayer().name());
                }
                 *///?} else {
                if (quad.renderLayer() != null) {
                    layers.add(quad.renderLayer().name());
                }
                //?}
                for (int v = 0; v < 4; v++) {
                    minY[0] = Math.min(minY[0], quad.y(v));
                    maxY[0] = Math.max(maxY[0], quad.y(v));
                }
            });
            out.add("overlay %s filling=%s quads=%d layers=%s y=[%.4f,%.4f]"
                .formatted(c[1], state.getValue(org.liquidphase.phase.PhaseHelper.FILLING),
                    overlayQuads[0], layers, minY[0], maxY[0]));
            if (overlayQuads[0] == 0) {
                out.add("overlay %s FAIL no overlay quad emitted".formatted(c[1]));
                continue;
            }
            if (!layers.contains(c[2])) {
                out.add("overlay %s FAIL wrong chunk layer (expected %s, got %s)"
                    .formatted(c[1], c[2], layers));
            }
            if (minY[0] < -SHELL_SLACK || maxY[0] > 1 + SHELL_SLACK) {
                out.add("overlay %s FAIL overlay escapes the block (y=[%.4f,%.4f])"
                    .formatted(c[1], minY[0], maxY[0]));
            }
        }
        return out;
    }

    private static List<String> readBackElements(MinecraftServer server, BlockPos origin) {        ServerLevel level = server.getLevel(Level.OVERWORLD);
        List<String> out = new ArrayList<>();
        if (level == null) {
            return out;
        }
        for (int i = 0; i < 4; i++) {
            BlockPos pos = origin.offset(36, 0, 20 + i);
            int extra = PhaseExtras.get(level, pos);
            out.add("element z=%d block=%s extra=%s".formatted(
                20 + i, BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()),
                PhaseExtra.describe(extra)));
            if (!PhaseExtra.isElement(extra)) {
                out.add("element z=%d FAIL no element entry".formatted(20 + i));
            }
            if (i == 0 && !PhaseExtra.powered(extra)) {
                out.add("element z=%d FAIL repeater did not turn on (input redstone block)".formatted(20 + i));
            }
            if (i == 1 && !PhaseExtra.powered(extra)) {
                out.add("element z=%d FAIL observer did not flip on a facing-neighbour change".formatted(20 + i));
            }
        }
        return out;
    }

    private static List<String> inspectElements(Minecraft mc, BlockPos origin) {
        List<String> out = new ArrayList<>();
        BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
        TextureAtlas atlas = (TextureAtlas) mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        SpriteFinder spriteFinder = atlas instanceof FabricSpriteAtlasTexture fabricAtlas
            ? fabricAtlas.spriteFinder() : null;
        MutableMesh mesh = Renderer.get().mutableMesh();
        for (int i = 0; i < 4; i++) {
            BlockPos pos = origin.offset(36, 0, 20 + i);
            BlockState state = mc.level.getBlockState(pos);
            BlockStateModel model = shaper.getBlockModel(state);
            int[] hostQuads = {0};
            int[] elementQuads = {0};
            double[] minY = {Double.MAX_VALUE};
            double[] maxY = {-Double.MAX_VALUE};
            boolean[] hasRod = {false};
            boolean[] hasBar = {false};
            boolean[] hasObserverFront = {false};
            boolean[] hasObserverPort = {false};
            java.util.Set<Integer> levels = new java.util.HashSet<>();
            List<String> hostSprites = new ArrayList<>();
            List<String> elementSprites = new ArrayList<>();
            double[] hostMinY = {Double.MAX_VALUE};
            double[] hostMaxY = {-Double.MAX_VALUE};
            List<String> forbidden = new ArrayList<>();
            mesh.clear();
            ((FabricBlockStateModel) model).emitQuads(mesh.emitter(), mc.level, pos, state,
                RandomSource.create(), direction -> false);
            String[] expected = {"repeater", "observer_", "comparator", "repeater"};
            final int index = i;
            mesh.forEachMutable(quad -> {
                TextureAtlasSprite sprite = spriteFinder.find(quad);
                String name = sprite == null ? "" : sprite.contents().name().toString();
                boolean elementSprite = name.contains(expected[index])
                    || ((index != 1) && name.contains("redstone_torch"));
                if (!elementSprite) {
                    hostQuads[0]++;
                    if (!hostSprites.contains(name)) {
                        hostSprites.add(name);
                    }
                    for (int v = 0; v < 4; v++) {
                        hostMinY[0] = Math.min(hostMinY[0], quad.y(v));
                        hostMaxY[0] = Math.max(hostMaxY[0], quad.y(v));
                    }
                    return;
                }
                elementQuads[0]++;
                if (!elementSprites.contains(name)) {
                    elementSprites.add(name);
                }
                hasRod[0] |= name.contains("redstone_torch");
                hasBar[0] |= name.contains("_bar");
                hasObserverFront[0] |= name.contains("observer_front");
                hasObserverPort[0] |= name.contains("observer_port");
                // 只允许杆/红条/五官/输出口：底板、锁存条、侦测器侧面顶面都不该出现。
                if (name.contains("smooth_stone") || name.contains("bedrock")
                    || name.contains("observer_side") || name.contains("observer_top")) {
                    forbidden.add(name);
                }
                double quadMinY = Double.MAX_VALUE;
                for (int v = 0; v < 4; v++) {
                    minY[0] = Math.min(minY[0], quad.y(v));
                    maxY[0] = Math.max(maxY[0], quad.y(v));
                    quadMinY = Math.min(quadMinY, quad.y(v));
                }
                levels.add((int) Math.round(quadMinY * 16.0));
            });
            int clientExtra = PhaseExtras.get(mc.level, pos);
            out.add(("element z=%d pos=%s clientExtra=%s hostQuads=%d elementQuads=%d y=[%.4f,%.4f]"
                    + " hostSprites=%s elementSprites=%s hostY=[%.4f,%.4f]")
                .formatted(20 + i, pos, PhaseExtra.describe(clientExtra), hostQuads[0],
                    elementQuads[0], minY[0], maxY[0], hostSprites, elementSprites,
                    hostMinY[0], hostMaxY[0]));
            if (elementQuads[0] == 0) {
                out.add("element z=%d FAIL no element_* quad emitted".formatted(20 + i));
            }
            if (!forbidden.isEmpty()) {
                out.add("element z=%d FAIL renders a forbidden base/side face %s"
                    .formatted(20 + i, forbidden));
            }
            if (i == 1) {
                // 侦测器：16×16 面固定在方块内，不做地形抬升。
                if (!hasObserverFront[0] || !hasObserverPort[0]) {
                    out.add("element z=%d FAIL observer must render both 五官 and 输出口 (front=%s port=%s)"
                        .formatted(20 + i, hasObserverFront[0], hasObserverPort[0]));
                }
                if (minY[0] < -SHELL_SLACK || minY[0] > 0.05) {
                    out.add("element z=%d FAIL observer geometry must stay fixed in the block (minY=%.4f)"
                        .formatted(20 + i, minY[0]));
                }
            } else {
                // 中继器/比较器：杆 + 红条，逐像素坐在宿主顶面上。
                if (!hasRod[0] || !hasBar[0]) {
                    out.add("element z=%d FAIL repeater/comparator must render rods and the red bar (rod=%s bar=%s)"
                        .formatted(20 + i, hasRod[0], hasBar[0]));
                }
                if (minY[0] < 0.5 - SHELL_SLACK) {
                    out.add("element z=%d FAIL element geometry sank into the host (minY=%.4f)"
                        .formatted(20 + i, minY[0]));
                }
            }
            if (i == 3) {
                // 下半楼梯：北侧（高半格）的杆/红条必须被抬到 y=1.0，南侧留在 y=0.5。
                if (maxY[0] <= 1.0) {
                    out.add("element z=%d FAIL stair upper-step pixels were not lifted (maxY=%.4f)"
                        .formatted(20 + i, maxY[0]));
                }
                if (levels.size() < 2) {
                    out.add("element z=%d FAIL stair element has a single height level %s"
                        .formatted(20 + i, levels));
                }
                if (maxY[0] > 1.0 + 7.0 / 16.0 + SHELL_SLACK) {
                    out.add("element z=%d FAIL stair element overshoots its rod height (maxY=%.4f)"
                        .formatted(20 + i, maxY[0]));
                }
            } else if (maxY[0] > 1.0 + SHELL_SLACK) {
                out.add("element z=%d FAIL element geometry escapes the block (maxY=%.4f)"
                    .formatted(20 + i, maxY[0]));
            }
        }
        return out;
    }

    /**
     * markDirty 回归：**只写旁路表、不动方块状态**的功率变化必须真的触发该 section 重画。
     * <p>
     * 0.2.0 曾把标脏写成 {@code setBlockDirty(pos, state, state)}：原版
     * {@code ModelManager.requiresRender(old, new)} 对同一状态对象直接返回 {@code false}，
     * 调用静默失效（1.19.4–26.3 全版本都有这道门），表现为"红石相有时显示有时不显示"
     * ——只有恰逢同 section 其它方块状态变化（邻线功率、放/挖方块）或区块重载时才重画。
     * <p>
     * 观测对象是该 section 已上传的 {@link SectionMesh} 引用：重画完成后引用必然被替换。
     * 场景内没有其它重画源——元件场景放在 x+36（隔开两个 section，其侦测器脉冲的邻居
     * 重画不会波及本格），观测窗内时间固定在正午、玩家静止、无方块更新。
     *
     * @return 失败描述；{@code null} = 通过（功率已恢复 15，不影响后续截图）
     */
    private static String runSectionRebuildRegression(ClientGameTestContext context, TestSingleplayerContext sp,
            BlockPos pos) {
        long node = SectionPos.asLong(pos);
        SectionMesh meshBefore = context.computeOnClient(client -> {
            SectionRenderDispatcher.RenderSection section = findSection(client, node);
            return section == null ? null : section.getSectionMesh();
        });
        if (meshBefore == null) {
            return "rebuild FAIL target section not visible (pos=" + pos + ")";
        }
        // 服务端只写旁路表：功率 15 → 7。不通知邻居、不改方块状态。
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) {
                int extra = PhaseExtras.get(level, pos);
                if (PhaseExtra.isRedstone(extra)) {
                    PhaseExtras.set(level, pos, PhaseExtra.withPower(extra, 7));
                }
            }
        });
        context.waitFor(client -> PhaseExtra.isRedstone(PhaseExtras.get(client.level, pos))
            && PhaseExtra.power(PhaseExtras.get(client.level, pos)) == 7);
        context.waitFor(client -> {
            SectionRenderDispatcher.RenderSection section = findSection(client, node);
            return section != null && section.getSectionMesh() != meshBefore;
        }, 100);
        // 恢复 15（同样只走旁路表），等重画稳定后再进入截图环节。
        sp.getServer().runOnServer(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) {
                int extra = PhaseExtras.get(level, pos);
                if (PhaseExtra.isRedstone(extra)) {
                    PhaseExtras.set(level, pos, PhaseExtra.withPower(extra, 15));
                }
            }
        });
        context.waitFor(client -> PhaseExtra.power(PhaseExtras.get(client.level, pos)) == 15);
        context.waitTicks(4);
        LiquidPhase.LOGGER.info("[gametest] table-only bypass write re-rendered its section (markDirty ok)");
        return null;
    }

    private static SectionRenderDispatcher.RenderSection findSection(Minecraft client, long sectionNode) {
        for (SectionRenderDispatcher.RenderSection section : client.levelRenderer.getVisibleSections()) {
            if (section.getSectionNode() == sectionNode) {
                return section;
            }
        }
        return null;
    }

    /** 把玩家放到相对 feet 的偏移处，看向相对 feet 的目标点，然后截图。 */
    private static void shot(ClientGameTestContext context, TestSingleplayerContext sp, BlockPos feet,
                             double dx, double dy, double dz,
                             double tx, double ty, double tz, String name) {
        sp.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            player.teleportTo(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
        });
        context.waitTicks(2);
        context.runOnClient(client -> {
            double ex = feet.getX() + dx;
            double ey = feet.getY() + dy + 1.62;   // 眼高
            double ez = feet.getZ() + dz;
            double vx = feet.getX() + tx - ex;
            double vy = feet.getY() + ty - ey;
            double vz = feet.getZ() + tz - ez;
            double horizontal = Math.sqrt(vx * vx + vz * vz);
            client.player.setYRot((float) (Math.toDegrees(Math.atan2(vz, vx)) - 90.0));
            client.player.setXRot((float) -Math.toDegrees(Math.atan2(vy, horizontal)));
            client.player.setOldPosAndRot();
        });
        context.waitTicks(4);
        sp.getClientWorld().waitForChunksRender();
        LiquidPhase.LOGGER.info("[gametest] screenshot -> {}", context.takeScreenshot(name));
    }
}
//?}
