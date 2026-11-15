//? if 1.21.11 {
package org.liquidphase.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.liquidphase.LiquidPhase;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;

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
 *       （外壳没丢，宿主也没被外壳顶掉）</li>
 *   <li>含相状态的区块图层不是 {@code SOLID}（外壳是透明底贴图，SOLID 会丢弃透明像素）</li>
 *   <li>外壳 quad 顶点不越出本格（方块空间 0..1，允许 0.01px 外扩）</li>
 * </ol>
 * 同时落三张截图到运行目录的 {@code screenshots/}：斜视、俯视、0 档对照。
 * <p>
 * 跑法：{@code ./gradlew :1.21.11:runClientGameTest}（run config 见 {@code build.gradle.kts}）。
 * 运行目录 {@code versions/<版本>/run-clientgametest/} 与常规 {@code run/} 刻意分开：
 * 新目录里没有 {@code resourcepacks/}，等于"无资源包"的干净客户端。
 * <p>
 * 只在 1.21.11 接线：{@code BakedQuad.position} / {@code BlockStateModel.collectParts} /
 * {@code ChunkSectionLayer} 是这一版起的 API（1.21.10 的 quad 顶点还打包在 {@code int[]} 里）。
 * 其它版本的渲染覆盖由服务端自检与 {@code tools/verify_jars.py} 承担。
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
            });
            context.waitTicks(12);

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

            shot(context, sp, bright, -3.5, 1.0, 1.2, 0.5, 1.0, 4.5, "fence_redstone_oblique");
            shot(context, sp, bright, 0.5, 3.2, 4.5, 0.5, 0.0, 4.5, "fence_redstone_topdown");
            shot(context, sp, dark, -3.5, 1.0, 1.2, 0.5, 1.0, 4.5, "fence_redstone_power0");

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
     * @param wire true 时栅栏相先写 {@code redstone_0}，再由真实红石线算出功率（走完整生成链路）；
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
            level.setBlockAndUpdate(origin.offset(0, 0, 2 + i),
                    PhaseHelper.withPhase(Blocks.OAK_FENCE.defaultBlockState(), Phase.REDSTONE_0));
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
            BlockState state = level.getBlockState(origin.offset(0, 0, 2 + i));
            out.add("z=%d block=%s filling=%s".formatted(
                    2 + i, BuiltInRegistries.BLOCK.getKey(state.getBlock()), PhaseHelper.getPhase(state)));
        }
        return out;
    }

    private static List<String> inspect(Minecraft mc, BlockPos origin) {
        List<String> out = new ArrayList<>();
        BlockModelShaper shaper = mc.getBlockRenderer().getBlockModelShaper();
        for (int i = 0; i < FENCE_COUNT + 2; i++) {
            BlockPos pos = origin.offset(0, 0, 2 + i);
            BlockState state = mc.level.getBlockState(pos);
            Phase phase = PhaseHelper.getPhase(state);

            int hostQuads = 0;
            int shellQuads = 0;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

            BlockStateModel model = shaper.getBlockModel(state);
            if (model == null) {
                out.add("z=%d FAIL null baked model".formatted(2 + i));
                continue;
            }
            RandomSource random = RandomSource.create();
            for (var part : model.collectParts(random)) {
                List<BakedQuad> quads = new ArrayList<>(part.getQuads(null));
                for (Direction direction : Direction.values()) {
                    quads.addAll(part.getQuads(direction));
                }
                for (BakedQuad quad : quads) {
                    if (!quad.sprite().toString().contains("redstone_phase_")) {
                        hostQuads++;
                        continue;
                    }
                    shellQuads++;
                    for (int v = 0; v < 4; v++) {
                        var p = quad.position(v);
                        minX = Math.min(minX, p.x());
                        minY = Math.min(minY, p.y());
                        minZ = Math.min(minZ, p.z());
                        maxX = Math.max(maxX, p.x());
                        maxY = Math.max(maxY, p.y());
                        maxZ = Math.max(maxZ, p.z());
                    }
                }
            }

            ChunkSectionLayer layer = ItemBlockRenderTypes.getChunkRenderType(state);
            out.add("z=%d phase=%s hostQuads=%d shellQuads=%d layer=%s".formatted(
                    2 + i, phase, hostQuads, shellQuads, layer));

            if (!phase.isRedstone()) {
                continue;   // 无相对照组只打印，不做外壳断言
            }
            if (hostQuads == 0) {
                out.add("z=%d FAIL host model disappeared under redstone phase".formatted(2 + i));
            }
            if (shellQuads == 0) {
                out.add("z=%d FAIL no redstone_phase_* quad (shell invisible)".formatted(2 + i));
            }
            if (layer == ChunkSectionLayer.SOLID) {
                out.add("z=%d FAIL redstone phase still on SOLID layer (transparent shell pixels dropped)"
                        .formatted(2 + i));
            }
            if (shellQuads > 0 && (minX < -SHELL_SLACK || minY < -SHELL_SLACK || minZ < -SHELL_SLACK
                    || maxX > 1 + SHELL_SLACK || maxY > 1 + SHELL_SLACK || maxZ > 1 + SHELL_SLACK)) {
                out.add("z=%d FAIL shell quads escape the block: x=[%.4f,%.4f] y=[%.4f,%.4f] z=[%.4f,%.4f]"
                        .formatted(2 + i, minX, maxX, minY, maxY, minZ, maxZ));
            }
        }
        return out;
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
