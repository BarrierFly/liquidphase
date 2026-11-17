package org.liquidphase.selftest;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.server.packs.resources.ResourceManager;
import org.liquidphase.LiquidPhase;
import org.liquidphase.phase.Phase;
import org.liquidphase.phase.PhaseHelper;
import org.liquidphase.phase.PhaseTargeting;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 红石相 / 炼药锅的自动化自检（默认不运行）。
 * <p>
 * 用 {@code -Dliquidphase.selftest=true} 启动服务端时，等服务器启动完成后在出生点附近
 * 造一小块测试场地逐项断言，把 {@code PASS}/{@code FAIL} 打进日志，最后清理场地。
 * <p>
 * 放置类断言分两层：
 * <ol>
 *   <li><b>无玩家层</b>：直接 {@link BlockItem#place}（{@code getPlayer()} 为 {@code null}），
 *       证明能力不依赖玩家实体；</li>
 *   <li><b>真实玩家层</b>：FakePlayer → {@code ServerPlayerGameMode.useItemOn}，
 *       走客户端实际落到的服务端入口。</li>
 * </ol>
 * 两层都要覆盖"物品自己搜索落点"的那一类（脚手架的 {@code updatePlacementContext}），
 * 这类路径不经过放置上下文改写，只在入口动手是测不出来的。
 */
public final class PhaseSelfTest {

    private static int passed;
    private static int failed;

    private PhaseSelfTest() {
    }

    public static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        // 固定高空坐标：六个版本的世界高度都覆盖 y=200，且不影响地形。
        BlockPos base = new BlockPos(0, 200, 0);
        passed = 0;
        failed = 0;
        LiquidPhase.LOGGER.info("[Liquid Phase][selftest] begin at {}", base);
        try {
            checkPhaseEnum();
            checkPhasePropertyCoverage();
            checkShapeAndBlockIdentity(level, base);
            checkSignal(level, base);
            checkInstantUpdate(level, base);
            checkPlaceIntoWire(level, base);
            checkPlaceIntoWireByPlayer(level, base);
            checkScaffoldingStacking(level, base);
            checkFallingBlockIntoWire(level, base);
            checkWireStaysUnreplaceable(level, base);
            checkRedstoneRenderParts();
            checkPhaseRenderCulling(level, base);
            checkSyncState();
            checkCauldron(level, base);
            // 相容宿主覆盖面（docs/host-coverage-audit.md §4 的逐项回归，0.1.13）
            checkSolidPhaseCollision(level, base);
            checkBreakRetainEverywhere(level, base);
            checkAdjacentLavaFilling(level, base);
            checkPlacementAbsorption(level, base);
            checkBucketInteraction(level, base);
            checkDispenserHostFilling(level, base);
            checkLavaReflowWiring(level, base);
            checkIndirectPhaseAbsorption(level, base);
            checkUniversalFillingPlacement(level, base);
            checkAbsorptionGateConsistency(level, base);
            checkPackMergeFragments(level, base);
            checkSlabVariantRendering();
        } catch (Throwable t) {
            failed++;
            LiquidPhase.LOGGER.error("[Liquid Phase][selftest] crashed", t);
        } finally {
            clearArea(level, base);
        }
        LiquidPhase.LOGGER.info("[Liquid Phase][selftest] result: {} passed, {} failed", passed, failed);
    }

    // ------------------------------------------------------------------
    // 枚举语义
    // ------------------------------------------------------------------

    private static void checkPhaseEnum() {
        assertTrue("phase count = 1 + 7 + 16", Phase.values().length == 24);
        assertTrue("FILLED excludes NONE", Phase.FILLED.length == 23);
        assertTrue("redstone_0 is redstone", Phase.REDSTONE_0.isRedstone());
        assertTrue("redstone power roundtrip", Phase.redstone(7).redstonePower() == 7);
        assertTrue("redstone power clamped", Phase.redstone(99).redstonePower() == 15
            && Phase.redstone(-5).redstonePower() == 0);
        assertTrue("frosted stays frosted", Phase.FROSTED_3.isFrosted() && !Phase.FROSTED_3.isRedstone());
        assertTrue("frosted age works", Phase.FROSTED_2.frostedAge() == 2);
        assertTrue("redstone is not solid", !Phase.REDSTONE_15.isSolid());
        assertTrue("obsidian is solid", Phase.OBSIDIAN.isSolid());
        // 可替换填充相（0.1.17）：只有熔岩相可被新的填充材料替换；水（含水态）在
        // canAcceptPhase 里单独算可替换，不进这个方法。未来加可替换相改 Phase 枚举。
        assertTrue("lava is the only replaceable filling",
            Phase.LAVA.isReplaceableFilling()
                && !Phase.OBSIDIAN.isReplaceableFilling()
                && !Phase.ICE.isReplaceableFilling()
                && !Phase.FROSTED_0.isReplaceableFilling()
                && !Phase.REDSTONE_15.isReplaceableFilling()
                && !Phase.NONE.isReplaceableFilling());
        assertTrue("none is neither", !Phase.NONE.isSolid() && !Phase.NONE.isRedstone());
    }

    // ------------------------------------------------------------------
    // 红石相 = 宿主方块 + phase 取值（不再有新方块）
    // ------------------------------------------------------------------

    private static void checkPhasePropertyCoverage() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        java.util.List<String> unrendered = new java.util.ArrayList<>();
        // 运行时真实集合：所有 instanceof PhaseCapable 的方块（= 真的拿到 phase 属性的方块）。
        java.util.Set<String> runtime = new java.util.LinkedHashSet<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)) {
                continue;
            }
            String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
            runtime.add(id);
            if (!block.defaultBlockState().hasProperty(PhaseHelper.FILLING)) {
                missing.add(id);
                continue;
            }
            // 每一个拿到 phase 属性的方块都必须有一份被我们改写过的 blockstate：
            // 改写后的那份带 16 条 `phase=redstone_N` 的外壳 part。原版 blockstate 里
            // 不会有 `liquidphase:block/redstone_phase_`，所以它就是"这个方块含相时
            // 画得出东西"的直接证据。
            //
            // 这是 0.1.12 的根因：`tools/phase-blocks/*.ids` 漏登记时，方块照样能在运行时
            // 拿到 phase 属性、照样能被写入 phase=redstone_*，但客户端仍用原版 blockstate，
            // 没有任何 part 匹配这个状态 —— 表现为"含红石相了但什么都不显示"。
            String json = liquidphase$blockState(id);
            if (json == null || !json.contains("liquidphase:block/redstone_phase_")) {
                unrendered.add(id);
            }
        }
        assertTrue("every PhaseCapable block has the phase property (missing: " + missing + ")",
            missing.isEmpty());
        assertTrue("every phased block has a blockstate that can draw the redstone shell "
                + "(missing: " + unrendered + ")",
            unrendered.isEmpty());
        checkPhaseBlockListMatchesRuntime(runtime);
    }

    /**
     * 生成清单（`tools/phase-blocks/&lt;版本&gt;.ids`，随 jar 一起发布）必须与运行时真实集合
     * **完全相等**。
     * <p>
     * 只比数量是不够的：清单两边各错一个、数量仍然相同，漏掉的那个方块就是"含红石相
     * 却什么都不显示"。这里做双向集合差，并直接打印差集，方便一眼定位。
     * <p>
     * 清单来自构建产物（`phase-blocks.ids`）；读不到时不断言，避免把"资源没打进 jar"
     * 误报成"清单不一致"。
     */
    private static void checkPhaseBlockListMatchesRuntime(java.util.Set<String> runtime) {
        String raw = liquidphase$resource("/phase-blocks.ids");
        if (raw == null) {
            assertTrue("shipped phase-block list is present", false);
            return;
        }
        java.util.Set<String> listed = new java.util.LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String id = line.trim();
            if (id.isEmpty() || id.startsWith("#")) {
                continue;
            }
            listed.add(id.startsWith("minecraft:") ? id : "minecraft:" + id);
        }
        java.util.Set<String> runtimeOnly = new java.util.LinkedHashSet<>(runtime);
        runtimeOnly.removeAll(listed);
        java.util.Set<String> listedOnly = new java.util.LinkedHashSet<>(listed);
        listedOnly.removeAll(runtime);
        assertTrue("shipped phase-block list == runtime phase-capable set "
                + "(phased but not listed: " + runtimeOnly + "; listed but not phased: " + listedOnly + ")",
            runtimeOnly.isEmpty() && listedOnly.isEmpty());
    }

    /** {@return 该方块改写后的 blockstate 内容；不是 minecraft 命名空间或读不到时返回 null} */
    private static String liquidphase$blockState(String blockId) {
        if (!blockId.startsWith("minecraft:")) {
            return null;
        }
        return liquidphase$resource("/assets/minecraft/blockstates/"
            + blockId.substring("minecraft:".length()) + ".json");
    }

    private static void checkShapeAndBlockIdentity(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        BlockState host = Blocks.OAK_STAIRS.defaultBlockState();
        BlockState redstone = PhaseHelper.withPhase(host, Phase.REDSTONE_15);
        level.setBlock(pos, redstone, Block.UPDATE_ALL);

        BlockState actual = level.getBlockState(pos);
        assertTrue("redstone phase is the same block as host",
            actual.getBlock() == Blocks.OAK_STAIRS);
        assertTrue("redstone phase carried by phase property",
            PhaseHelper.getPhase(actual) == Phase.REDSTONE_15);
        assertTrue("no extra liquidphase redstone blocks registered",
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().stream()
                .noneMatch(id -> id.getNamespace().equals("liquidphase") && id.getPath().startsWith("redstone_")));
        assertTrue("host outline unchanged (stairs are not a full cube)",
            !Block.isShapeFullBlock(actual.getShape(level, pos, CollisionContext.empty())));
        assertTrue("collision unchanged (not a solid phase)",
            !Block.isShapeFullBlock(actual.getCollisionShape(level, pos, CollisionContext.empty())));
        assertTrue("redstone power readable", RedstoneBlocks.getPower(actual) == 15);
        assertTrue("stair UP face exposed", RedstoneBlocks.exposed(actual, Direction.UP));

        BlockState doubleSlab = PhaseHelper.withPhase(
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE),
            Phase.REDSTONE_15);
        assertTrue("double slab hides every face",
            !RedstoneBlocks.exposed(doubleSlab, Direction.UP)
                && !RedstoneBlocks.exposed(doubleSlab, Direction.NORTH));

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 信号
    // ------------------------------------------------------------------

    private static void checkSignal(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.REDSTONE_15),
            Block.UPDATE_ALL);

        BlockState stairs = level.getBlockState(pos);
        assertTrue("stair host hides NORTH face when facing north", !RedstoneBlocks.exposed(stairs, Direction.NORTH));
        assertTrue("stair host exposes SOUTH face when facing north", RedstoneBlocks.exposed(stairs, Direction.SOUTH));

        level.setBlock(pos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("redstone phase keeps power 15 (got " + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 15);

        level.setBlock(pos.south(), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        int toWire = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into wire is power-1 (got " + toWire + ")", toWire == 14);

        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        int toStone = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into solid is full power (got " + toStone + ")", toStone == 15);

        assertTrue("signal DOWN is 0", level.getSignal(pos, Direction.DOWN) == 0);

        BlockPos slabPos = base.offset(2, 1, 0);
        level.setBlock(slabPos, PhaseHelper.withPhase(
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE),
            Phase.REDSTONE_15), Block.UPDATE_ALL);
        level.setBlock(slabPos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(slabPos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("covered face does not output", level.getSignal(slabPos, Direction.NORTH) == 0);
    }

    private static void checkInstantUpdate(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(4, 1, 0);
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.REDSTONE_0),
            Block.UPDATE_ALL);
        BlockPos source = pos.south();
        level.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("power updates immediately on neighbour change (got "
                + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 15);
        level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("power drops immediately when source removed (got "
                + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 0);
    }

    // ------------------------------------------------------------------
    // 第 1 层：无玩家，直接走 BlockItem.place
    // ------------------------------------------------------------------

    private static void checkPlaceIntoWire(ServerLevel level, BlockPos base) {
        // (1) 原版落点算到线格：对着线下面支撑它的方块 ↩ 的顶面放
        BlockPos wirePos = base.offset(6, 1, 0);
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("scene(1): wire present at target tile",
            level.getBlockState(wirePos).is(Blocks.REDSTONE_WIRE));
        int wirePower = level.getBlockState(wirePos).getValue(BlockStateProperties.POWER);
        assertTrue("scene(1): wire powered (got " + wirePower + ")", wirePower == 15);

        BlockState placed = liquidphase$placeBlockInto(level, Blocks.OAK_STAIRS, wirePos.below(), Direction.UP);
        BlockState after = level.getBlockState(wirePos);
        assertTrue("scene(1): placement pipeline accepted the wire tile (returned " + placed + ")", placed != null);
        assertTrue("scene(1): wire tile became the host (got " + after.getBlock() + ")", after.is(Blocks.OAK_STAIRS));
        assertTrue("scene(1): host carries the redstone phase (got " + PhaseHelper.getPhase(after) + ")",
            RedstoneBlocks.isRedstonePhase(after));
        assertTrue("scene(1): host carried the wire power (wire=" + wirePower + ", got "
                + RedstoneBlocks.getPower(after) + ")",
            RedstoneBlocks.getPower(after) == wirePower);

        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (3) 非相容宿主不享受这个口子
        BlockPos stoneWire = base.offset(10, 1, 0);
        level.setBlock(stoneWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stoneWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState stonePlaced = liquidphase$placeBlockInto(level, Blocks.STONE, stoneWire.below(), Direction.UP);
        assertTrue("scene(3): non-host still cannot replace a wire (returned " + stonePlaced + ")",
            stonePlaced == null);
        assertTrue("scene(3): wire survived the rejected placement",
            level.getBlockState(stoneWire).is(Blocks.REDSTONE_WIRE));
        level.setBlock(stoneWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (4) 普通放置没被影响
        BlockPos dustPos = base.offset(11, 1, 0);
        level.setBlock(dustPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState dustPlaced = liquidphase$placeBlockInto(level, Blocks.REDSTONE_WIRE, dustPos.below(), Direction.UP);
        assertTrue("scene(4): redstone dust still places on a plain block (returned " + dustPlaced + ")",
            dustPlaced != null && dustPlaced.is(Blocks.REDSTONE_WIRE));
        level.setBlock(dustPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(dustPos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 第 2 层：真实玩家入口（FakePlayer → ServerPlayerGameMode.useItemOn）
    // ------------------------------------------------------------------

    /**
     * 客户端右键在服务端最终落到 {@code ServerPlayerGameMode.useItemOn}，这里走同一条路。
     * <p>
     * 覆盖三种真实姿势：点线下面方块、点线旁方块朝线的侧面、点线上方方块底面。
     * 这条链路会先经过 {@code BlockState.useItemOn → useWithoutItem}，如果被方块交互吃掉，
     * 放置根本不会发生——这一层能测出来，直接调 {@code BlockItem.place} 测不出来。
     */
    private static void checkPlaceIntoWireByPlayer(ServerLevel level, BlockPos base) {
        net.minecraft.server.level.ServerPlayer player = liquidphase$fakePlayer(level);

        // (1) 点线下面的方块
        assertTrue("player scene(1): host lands in the wire tile", liquidphase$playerGesture(level, player,
            base.offset(7, 1, 0).below(), Direction.UP, base.offset(7, 1, 0), Blocks.OAK_STAIRS));
        assertTrue("player scene(1): keeps 15 power",
            RedstoneBlocks.getPower(level.getBlockState(base.offset(7, 1, 0))) == 15);
        liquidphase$clearGesture(level, base.offset(7, 1, 0), base.offset(7, 1, 0).below());

        // (2) 点线旁方块朝线的侧面
        BlockPos sideWire = base.offset(8, 1, 0);
        BlockPos sideGround = sideWire.east();
        assertTrue("player scene(2): host lands in the wire tile", liquidphase$playerGesture(level, player,
            sideGround, Direction.WEST, sideWire, Blocks.OAK_STAIRS));
        assertTrue("player scene(2): keeps 15 power",
            RedstoneBlocks.getPower(level.getBlockState(sideWire)) == 15);
        liquidphase$clearGesture(level, sideWire, sideGround);

        // (3) 点线上方方块底面
        BlockPos upWire = base.offset(9, 1, 0);
        BlockPos upGround = upWire.above();
        assertTrue("player scene(3): host lands in the wire tile", liquidphase$playerGesture(level, player,
            upGround, Direction.DOWN, upWire, Blocks.OAK_STAIRS));
        liquidphase$clearGesture(level, upWire, upGround);

        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    // ------------------------------------------------------------------
    // 脚手架堆叠：物品自己搜索落点（updatePlacementContext）的那一类
    // ------------------------------------------------------------------

    /**
     * {@code ScaffoldingBlockItem.updatePlacementContext} 会自己沿方向搜索
     * "能放"的格子，搜索时问的是 {@code BlockState.canBeReplaced(context)}。
     * 只在 {@code BlockItem.place} 入口上做手脚时，红石线格会被它跳过、方块落到别处——
     * 这条断言就是防这个回归。
     */
    private static void checkScaffoldingStacking(ServerLevel level, BlockPos base) {
        net.minecraft.server.level.ServerPlayer player = liquidphase$fakePlayer(level);
        BlockPos wirePos = base.offset(12, 1, 0);
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        // 线格上方立一段脚手架柱（堆叠场景），玩家点柱顶继续往上堆
        level.setBlock(wirePos.above(), Blocks.SCAFFOLDING.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos columnTop = wirePos.above();

        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Blocks.SCAFFOLDING));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(columnTop), Direction.UP, columnTop, false);
        player.gameMode.useItemOn(player, level, player.getMainHandItem(),
            net.minecraft.world.InteractionHand.MAIN_HAND, hit);

        // 脚手架堆叠会往柱子上面放；关键是不能把线格本身搞坏，且线格的相逻辑不受影响
        assertTrue("scaffolding stack: wire tile untouched while stacking the column",
            level.getBlockState(wirePos).is(Blocks.REDSTONE_WIRE));

        // 反向：脚手架直接堆进线格（把柱底换掉）——这条走 canBeReplaced 搜索
        level.setBlock(wirePos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("scaffolding stack: can replace the wire tile when the item is a host",
            level.getBlockState(wirePos).canBeReplaced(liquidphase$context(level, Blocks.SCAFFOLDING,
                wirePos.below(), Direction.UP)));

        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /**
     * 红石线自身的"可替换"语义要保持精确：只对相容宿主物品放行，
     * 对石头/红石粉等其它物品仍然是不可替换。
     */
    private static void checkWireStaysUnreplaceable(ServerLevel level, BlockPos base) {
        BlockPos wirePos = base.offset(14, 1, 0);
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        BlockState wire = level.getBlockState(wirePos);

        assertTrue("wire state itself is still not replaceable", !wire.canBeReplaced());

        assertTrue("plain placement context refuses the wire tile (stone)",
            !wire.canBeReplaced(liquidphase$context(level, Blocks.STONE, wirePos.below(), Direction.UP)));
        assertTrue("plain placement context refuses the wire tile (dust)",
            !wire.canBeReplaced(liquidphase$context(level, Blocks.REDSTONE_WIRE, wirePos.below(), Direction.UP)));
        assertTrue("host placement context accepts the wire tile",
            wire.canBeReplaced(liquidphase$context(level, Blocks.OAK_STAIRS, wirePos.below(), Direction.UP)));

        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 下落方块（FallingBlockEntity）落位
    // ------------------------------------------------------------------

    /**
     * 下落方块落位不经过 {@code BlockItem.place}：{@code FallingBlockEntity.tick()}
     * 自己调 {@code landing.canBeReplaced(new DirectionalPlaceContext(..., ItemStack.EMPTY, ...))}
     * 再直接 {@code setBlock}。这条断言覆盖两个方向：
     * <ul>
     *   <li>相容宿主（钟乳石）能落进线格，并带上原线功率；</li>
     *   <li>非宿主（沙子）保持原版行为，落在线上会弹成物品、线格不变。</li>
     * </ul>
     */
    private static void checkFallingBlockIntoWire(ServerLevel level, BlockPos base) {
        // ---- (1) 相容宿主下落：应落进线格并带红石相 ----
        BlockPos hostWire = base.offset(16, 1, 0);
        level.setBlock(hostWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire.west().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        int hostPower = level.getBlockState(hostWire).getValue(BlockStateProperties.POWER);

        BlockState dripstone = Blocks.POINTED_DRIPSTONE.defaultBlockState();
        net.minecraft.world.entity.item.FallingBlockEntity hostEntity =
            net.minecraft.world.entity.item.FallingBlockEntity.fall(level, hostWire.above(3), dripstone);
        // 直接把它放到线格正上方，避免等它自己掉（自检不推进 tick）
        hostEntity.setPos(hostWire.getX() + 0.5, hostWire.getY() + 0.02, hostWire.getZ() + 0.5);
        hostEntity.setOnGround(true);
        hostEntity.tick();

        BlockState hostAfter = level.getBlockState(hostWire);
        assertTrue("falling host: landed in the wire tile (got " + hostAfter.getBlock() + ")",
            hostAfter.is(Blocks.POINTED_DRIPSTONE));
        assertTrue("falling host: carried the redstone phase (got " + PhaseHelper.getPhase(hostAfter) + ")",
            RedstoneBlocks.isRedstonePhase(hostAfter));
        assertTrue("falling host: power followed the wire (wire=" + hostPower + ", got "
                + RedstoneBlocks.getPower(hostAfter) + ")",
            RedstoneBlocks.getPower(hostAfter) == hostPower);

        hostEntity.discard();
        level.setBlock(hostWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(hostWire.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // ---- (2) 非宿主下落（沙子）：保持原版，红石线不被覆盖 ----
        BlockPos sandWire = base.offset(17, 1, 0);
        level.setBlock(sandWire.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sandWire, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);

        net.minecraft.world.entity.item.FallingBlockEntity sand =
            net.minecraft.world.entity.item.FallingBlockEntity.fall(level, sandWire.above(3),
                Blocks.SAND.defaultBlockState());
        sand.setPos(sandWire.getX() + 0.5, sandWire.getY() + 0.02, sandWire.getZ() + 0.5);
        sand.setOnGround(true);
        sand.tick();

        assertTrue("falling sand: wire tile not replaced (got " + level.getBlockState(sandWire).getBlock() + ")",
            level.getBlockState(sandWire).is(Blocks.REDSTONE_WIRE));

        sand.discard();
        level.setBlock(sandWire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(sandWire.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static net.minecraft.server.level.ServerPlayer liquidphase$fakePlayer(ServerLevel level) {
        return net.fabricmc.fabric.api.entity.FakePlayer.get(level, new com.mojang.authlib.GameProfile(
            java.util.UUID.fromString("11111111-2222-3333-4444-555555555555"), "[liquidphase-selftest]"));
    }

    /**
     * 一次真实右键：点 {@code clickPos} 的 {@code face} 面，期望宿主落进 {@code wirePos}。
     * <p>
     * 通电用"线格西侧的方块 + 红石块"保证不挡住落点，功率稳定为 15。
     */
    private static boolean liquidphase$playerGesture(ServerLevel level,
                                                    net.minecraft.server.level.ServerPlayer player,
                                                    BlockPos clickPos, Direction face,
                                                    BlockPos wirePos, Block block) {
        level.setBlock(wirePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        if (!clickPos.equals(wirePos) && !clickPos.equals(wirePos.below())) {
            level.setBlock(clickPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(wirePos.west().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        if (!level.getBlockState(wirePos).is(Blocks.REDSTONE_WIRE)) {
            LiquidPhase.LOGGER.warn("[selftest] gesture scene broke: wire missing at {}", wirePos);
            return false;
        }

        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(block));
        Vec3 hitVec = new Vec3(clickPos.getX() + 0.5 + face.getStepX() * 0.5,
            clickPos.getY() + 0.5 + face.getStepY() * 0.5,
            clickPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, clickPos, false);
        player.gameMode.useItemOn(player, level, player.getMainHandItem(),
            net.minecraft.world.InteractionHand.MAIN_HAND, hit);

        BlockState after = level.getBlockState(wirePos);
        boolean ok = after.is(block) && RedstoneBlocks.isRedstonePhase(after);
        if (!ok) {
            LiquidPhase.LOGGER.warn("[selftest] gesture failed at {}: click={} face={} -> {} / {}",
                wirePos, clickPos, face, after.getBlock(), PhaseHelper.getPhase(after));
        }
        return ok;
    }

    private static void liquidphase$clearGesture(ServerLevel level, BlockPos wirePos, BlockPos clickPos) {
        level.setBlock(wirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(wirePos.west(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        if (!clickPos.equals(wirePos) && !clickPos.equals(wirePos.below())) {
            level.setBlock(clickPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static BlockState liquidphase$placeBlockInto(ServerLevel level, Block block,
                                                         BlockPos clickedPos, Direction face) {
        ItemStack stack = new ItemStack(block);
        if (!(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        BlockPlaceContext context = liquidphase$context(level, block, clickedPos, face);
        net.minecraft.world.InteractionResult result = item.place(context);
        if (!result.consumesAction()) {
            return null;
        }
        BlockState state = level.getBlockState(context.getClickedPos());
        return state.isAir() ? null : state;
    }

    /** {@return 无玩家的标准放置上下文（落点由原版 {@link BlockPlaceContext} 自己算）} */
    private static BlockPlaceContext liquidphase$context(ServerLevel level, Block block,
                                                         BlockPos clickedPos, Direction face) {
        Vec3 hitVec = new Vec3(
            clickedPos.getX() + 0.5 + face.getStepX() * 0.5,
            clickedPos.getY() + 0.5 + face.getStepY() * 0.5,
            clickedPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, clickedPos, false);
        return new BlockPlaceContext(level, null, net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(block), hit);
    }

    private static void checkRedstoneRenderParts() {
        // 全量：**每一个**被我们改写过的 blockstate 都必须同时满足
        //   (a) 16 个功率档位各有一条外壳 part（0 档熄灭、15 档满亮互不相同）；
        //   (b) 不是全格宿主时，宿主 part 不带 phase 条件（否则红石相下宿主消失）。
        // 只抽查 oak_stairs/oak_leaves/glass_pane 曾经放过了整批漏登记的方块（0.1.12 的根因）。
        String raw = liquidphase$resource("/phase-blocks.ids");
        assertTrue("shipped phase-block list is present", raw != null);
        java.util.List<String> noShell = new java.util.ArrayList<>();
        java.util.List<String> badHost = new java.util.ArrayList<>();
        java.util.List<String> fullCubeBroken = new java.util.ArrayList<>();
        java.util.List<String> fullCubeOverlayReferenced = new java.util.ArrayList<>();
        int scanned = 0;
        if (raw != null) {
            for (String line : raw.split("\\R")) {
                String id = line.trim();
                if (id.isEmpty() || id.startsWith("#")) {
                    continue;
                }
                String json = liquidphase$blockState(id.startsWith("minecraft:") ? id : "minecraft:" + id);
                if (json == null) {
                    noShell.add(id + " (no blockstate)");
                    continue;
                }
                scanned++;
                if (!liquidphase$hasShellForEveryPower(json)) {
                    noShell.add(id);
                }
                // 0.1.18 起**所有**宿主（含整格/混合）的宿主 part 都不带相条件：
                // 相只作为内缩叠加层，宿主始终可见（与含熔岩一致）。
                if (!hostPartsCarryNoPhaseCondition(json)) {
                    badHost.add(id);
                }
                // 每个宿主的宿主 part 都必须在红石相下保留 —— 一旦屏蔽，含红石相就整块
                // 隐形（0.1.8 的实机 bug），或变成"只剩外壳、宿主消失"。
                if (!json.contains("redstone_15")) {
                    fullCubeBroken.add(id);
                }
                // 0.1.18 起不再使用整格相材质（与宿主模型共面会 z-fighting）。
                if (json.contains("liquidphase:block/phase_obsidian_full")
                    || json.contains("liquidphase:block/phase_ice_full")) {
                    fullCubeOverlayReferenced.add(id);
                }
            }
        }
        assertTrue("scanned more than just a handful of blockstates (" + scanned + " scanned)", scanned > 10);
        assertTrue("every phased blockstate has one shell part per power level (broken: " + noShell + ")",
            noShell.isEmpty());
        assertTrue("no host part carries a phase condition — every host stays visible (broken: " + badHost + ")",
            badHost.isEmpty());
        assertTrue("every host part still renders under redstone phases "
                + "(broken: " + fullCubeBroken + ")", fullCubeBroken.isEmpty());

        // 整格宿主（树叶/铜格栅/红树根）单独确认：宿主 part 在红石相下仍然渲染。
        String leaves = liquidphase$resource("/assets/minecraft/blockstates/oak_leaves.json");
        assertTrue("leaves blockstate resource is present", leaves != null);
        if (leaves != null) {
            assertTrue("leaves host part still renders under redstone phase",
                leaves.contains("redstone_15") && leaves.contains("minecraft:block/oak_leaves"));
        }

        // Transparent hosts previously got no shell at all (only the 41 redstone hosts did),
        // which is exactly why "some transparent blocks still show no redstone phase".
        String pane = liquidphase$resource("/assets/minecraft/blockstates/glass_pane.json");
        assertTrue("glass pane blockstate resource is present", pane != null);
        if (pane != null) {
            assertTrue("transparent host (glass pane) gets a redstone shell too",
                pane.contains("liquidphase:block/redstone_phase_") && pane.contains("redstone_15"));
        }

        // ---- regression for the 0.1.11 bugs ----
        // 整格宿主的相用**内缩**覆盖层（0.1.18 起）：宿主模型是 0..16 整格，
        // 若相也用 0..16 会与之共面 z-fighting，含相时树叶与相随机互相遮挡。
        // 内缩后树叶稳定在外层、相从贴图缝隙透出，与含熔岩的观感一致。
        String poplar = liquidphase$resource("/assets/minecraft/blockstates/orange_poplar_leaves.json");
        if (poplar != null) {
            assertTrue("full-cube host (orange poplar leaves) uses the inset overlay, not the full one",
                poplar.contains("liquidphase:block/phase_ice")
                    && !poplar.contains("phase_ice_full"));
            assertTrue("full-cube host keeps its own model visible (no phase gating on the host part)",
                poplar.contains("minecraft:block/orange_poplar_leaves")
                    && hostPartsCarryNoPhaseCondition(poplar));
        }
        // 树叶同样：宿主 part 无条件、相用内缩覆盖层。
        assertTrue("leaves host part is unconditional and the overlay is inset",
            leaves != null
                && leaves.contains("\"model\":\"minecraft:block/oak_leaves\"")
                && !leaves.contains("phase_ice_full"));
        // 反向：部分覆盖的宿主必须留在内缩路径（整格替换会把它的空隙吃掉）。
        String stairsRes = liquidphase$resource("/assets/minecraft/blockstates/oak_stairs.json");
        assertTrue("partial host (oak stairs) keeps the inset overlay",
            stairsRes != null && !stairsRes.contains("phase_ice_full"));
        // (a) The inset phase overlay / outer shell must NOT carry cullface: vanilla cullface
        // compares "does the neighbour cover the block-boundary face" and misfires against
        // partial neighbours (slabs/stairs), eating faces the overlay does cover.
        for (String model : new String[]{
            "phase_obsidian", "phase_ice", "phase_frosted_0", "phase_frosted_1",
            "phase_frosted_2", "phase_frosted_3", "redstone_phase_0", "redstone_phase_15"}) {
            String modelJson = liquidphase$resource("/assets/liquidphase/models/block/" + model + ".json");
            assertTrue("model " + model + " exists", modelJson != null);
            if (modelJson != null) {
                assertTrue("model " + model + " must not use cullface (inset/oversized geometry)",
                    !modelJson.contains("\"cullface\""));
            }
        }
        // 外壳按功率分成 16 份：0 档（熄灭）与 15 档（满功率）必须是不同贴图。
        String dimShell = liquidphase$resource("/assets/liquidphase/models/block/redstone_phase_0.json");
        String brightShell = liquidphase$resource("/assets/liquidphase/models/block/redstone_phase_15.json");
        assertTrue("redstone shell has one model per power level",
            dimShell != null && brightShell != null && !dimShell.equals(brightShell));
        assertTrue("redstone shell power models use distinct textures",
            dimShell != null && brightShell != null
                && dimShell.contains("redstone_phase_0") && brightShell.contains("redstone_phase_15"));
        // 0.1.18 起所有宿主的相都用内缩覆盖层（含整格宿主），因此 _full 系列模型
        // 不再被任何 blockstate 引用；仍留在资源里只为兼容旧存档/旧包引用。
        // 这里改为断言"产物里不出现 phase_*_full"——一旦回退到整格替换，
        // 树叶/铜格栅会与宿主共面 z-fighting，必须由断言拦住。
        assertTrue("no generated blockstate uses the full-cube phase material any more",
            fullCubeOverlayReferenced.isEmpty());

        // (a2) 宿主 part 不能再带"覆盖全部 24 个相"的恒真条件：原版
        // KeyValueCondition.instantiate 会为它打 `Condition ... is always true` 警告，
        // 1.21.10+ 每个方块状态各一条，加载日志被刷屏。
        String stairs = liquidphase$resource("/assets/minecraft/blockstates/oak_stairs.json");
        assertTrue("host parts carry no phase condition at all (no always-true warnings)",
            stairs != null && !stairs.contains("\"filling\":\"none|lava|obsidian"));

        // (b) The render layer fix lives in a mixin (the BlockRenderLayerMap table is rebuilt
        // by the resource reload and would silently lose an init-time putBlock). Assert the
        // mixin config is shipped and wired.
        //? if !(26.3) {
        //# The mixin config must ship and the mixin class must be present; jar-level wiring is
        //# asserted by tools/verify_jars.py (the dev-server classpath copy of fabric.mod.json can
        //# lag behind a fresh processResources run, so don't assert on it here).
        String mixinCfg = liquidphase$resource("/liquidphase.layer.mixins.json");
        assertTrue("render-layer mixin config is shipped", mixinCfg != null);
        if (mixinCfg != null) {
            assertTrue("render-layer mixin is registered in the config",
                mixinCfg.contains("PhaseRenderLayerMixin"));
        }
        //?}

        checkPhasedRenderShape();
    }

    /**
     * 含相状态必须按 {@code RenderShape.MODEL} 渲染。
     * <p>
     * 区块构建器只在"该渲染方块模型"时才请求模型。`barrier` / `light` 与告示牌家族原生是
     * `INVISIBLE`；1.21.1 起的判据是 `== MODEL`，所以连 `ENTITYBLOCK_ANIMATED`
     * （1.19.4–1.21.1 的箱子/附魔神像等）也会被跳过。不修这一处，
     * 给它们写入相以后界面上什么都不显示（相的数据在，模型从没被请求过）。
     */
    private static void checkPhasedRenderShape() {
        // 1.19.4 / 1.20.1 的 barrier 不再是相容宿主（audit §4.2：这两个版本的 BarrierBlock
        // 没有 WATERLOGGED 属性，注入 PhaseCapable 会在默认方法里抛 IllegalArgumentException），
        // 改用同为 INVISIBLE 且全版本相容的 light 顶上；1.21.1+ 继续用 barrier
        // （它是 0.1.12 清单修复的回归样本）。
        //? if 1.19.4 || 1.20.1 {
        /*Block invisibleHost = Blocks.LIGHT;
        String hostName = "light";
         *///?} else {
        Block invisibleHost = Blocks.BARRIER;
        String hostName = "barrier";
        //?}
        BlockState phasedHost = PhaseHelper.withPhase(invisibleHost.defaultBlockState(), Phase.REDSTONE_15);
        assertTrue(hostName + " is invisible without a phase",
            invisibleHost.defaultBlockState().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL);
        assertTrue("phased " + hostName + " renders its model (otherwise the phase would be invisible)",
            phasedHost.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL);
        assertTrue("phased solid-state " + hostName + " also renders",
            PhaseHelper.withPhase(invisibleHost.defaultBlockState(), Phase.ICE).getRenderShape()
                == net.minecraft.world.level.block.RenderShape.MODEL);
        // 反向：不带相的状态必须保持原值（否则宿主会凭空显出方块模型）。
        assertTrue("unphased states keep their vanilla render shape",
            invisibleHost.defaultBlockState().getRenderShape()
                != net.minecraft.world.level.block.RenderShape.MODEL);

        // 原生不是 MODEL 的宿主，含相后必须能请求到方块模型，否则相完全不可见。
        // 不写死候选清单（那样只会覆盖当时想到的几个）：遍历**全部** phase 方块，
        // 凡是原生 RenderShape 不是 MODEL 的，逐个断言"带相 → MODEL、不带相 → 原值"。
        liquidphase$assertNonModelHostsRenderPhases();
    }

    /**
     * 遍历**全部** phase 方块，对"原生不是 MODEL"的那些逐个断言：带相后 RenderShape 变成
     * MODEL，不带相时保持原值。本版本里原生就是 MODEL 的宿主跳过（原版自己已经会渲染模型）。
     * <p>
     * 用全量扫描而不是写死几个候选：告示牌/悬挂告示牌全家、箱子系列、`decorated_pot`、
     * `conduit`、`barrier`、`light` 等等在每个版本里清单都不一样，写死必然漏
     * （写死版本只覆盖了 6 个方块，而实际原生非 MODEL 的 phase 宿主远多于此）。
     */
    private static void liquidphase$assertNonModelHostsRenderPhases() {
        int checked = 0;
        java.util.List<String> broken = new java.util.ArrayList<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)) {
                continue;
            }
            BlockState plain = block.defaultBlockState();
            if (!PhaseHelper.hasPhaseProperty(plain)
                || plain.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
                continue;
            }
            checked++;
            String name = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
            boolean phasedRedstone = PhaseHelper.withPhase(plain, Phase.REDSTONE_15).getRenderShape()
                == net.minecraft.world.level.block.RenderShape.MODEL;
            boolean phasedIce = PhaseHelper.withPhase(plain, Phase.ICE).getRenderShape()
                == net.minecraft.world.level.block.RenderShape.MODEL;
            boolean vanillaKept = plain.getRenderShape()
                != net.minecraft.world.level.block.RenderShape.MODEL;
            if (!phasedRedstone || !phasedIce || !vanillaKept) {
                broken.add(name);
            }
        }
        assertTrue("every host whose vanilla RenderShape is not MODEL renders its phase "
                + "(" + checked + " checked, broken: " + broken + ")",
            broken.isEmpty());
        // 这条能确保扫描真的命中了东西（barrier/light 全版本都是 INVISIBLE）。
        assertTrue("the non-MODEL RenderShape scan actually inspected some hosts (" + checked + " checked)",
            checked > 0);
    }

    private static String liquidphase$resource(String path) {
        try (java.io.InputStream stream = LiquidPhase.class.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * {@return 是否每个功率档位都有自己的一条外壳 part}
     * <p>
     * 外壳按功率分成 16 份（0 档熄灭、15 档满功率）。只留一条 part 指向同一个模型时，
     * 所有功率的外观会完全一样——0 档看起来就像通电了。
     */
    private static boolean liquidphase$hasShellForEveryPower(String json) {
        for (int power = 0; power < 16; power++) {
            if (!json.contains("\"filling\":\"redstone_" + power + "\"")) {
                return false;
            }
            if (!json.contains("liquidphase:block/redstone_phase_" + power + "\"")) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return 宿主 part 是否完全不带 phase 条件}
     * <p>
     * 普通宿主的覆盖层与外壳都是叠加层，宿主照常渲染，所以不需要 phase 条件。
     * 一旦给宿主 part 补上"全部 24 个相"的条件，原版 {@code KeyValueCondition}
     * 会判定它恒真并打 {@code Condition ... is always true} 警告（每个状态一条）。
     */
    private static boolean hostPartsCarryNoPhaseCondition(String json) {
        return !json.contains("\"filling\":\"none|lava|obsidian");
    }

    // ------------------------------------------------------------------
    // 渲染剔除 + 双端同步
    // ------------------------------------------------------------------

    /**
     * 相邻面剔除：含相状态**不**需要任何干预。
     * <p>
     * 相覆盖层与红石外壳都不带 {@code cullface}（几何内缩/外扩，不在方块边界上），
     * 所以它们根本不会进入 {@code Block.shouldRenderFace} 的剔除判定；宿主的带
     * {@code cullface} 面属于宿主自己的事，原版怎么剔就怎么剔。
     * <p>
     * 这里断言的是"我们生成的模型里确实没有 {@code cullface}"这条前提——
     * 一旦有人给覆盖层/外壳加回 {@code cullface}，相才会开始被错误剔除。
     * 曾经为此加过 {@code PhaseSkipRenderingMixin}（强制含相状态不参与剔除），
     * 那只会让含相楼梯/栅栏对本该剔除的邻居面也照画，属于改坏原版语义，已删除。
     */
    private static void checkPhaseRenderCulling(ServerLevel level, BlockPos base) {
        for (String model : new String[]{
            "phase_obsidian", "phase_ice", "phase_frosted_0", "phase_frosted_3",
            "redstone_phase_0", "redstone_phase_15"}) {
            String json = liquidphase$resource("/assets/liquidphase/models/block/" + model + ".json");
            assertTrue("phase model " + model + " exists", json != null);
            if (json != null) {
                assertTrue("phase model " + model + " carries no cullface (so vanilla never culls it)",
                    !json.contains("\"cullface\""));
            }
        }
    }

    /**
     * 双端同步：客户端侧功能必须等服务器握手。
     * <p>
     * 三种情形：单人（集成服务器）、连有 mod 的服务器、连原版服务器。
     * 这里只验证"默认不支持"这一条最关键的语义——只要它成立，
     * 原版服务器就不会被客户端单方面预测的状态覆盖。
     */
    private static void checkSyncState() {
        // 玩家进服那一刻（握手包到达前）必须是 false。
        assertTrue("client phase sync starts disabled until the server handshake arrives",
            !org.liquidphase.sync.PhaseSyncState.serverSupportsPhases());
        int bits = org.liquidphase.sync.PhaseHandshake.bits(true, true);
        assertTrue("handshake mask advertises phases and liquid interaction (got " + bits + ")",
            org.liquidphase.sync.PhaseHandshake.hasPhase(bits)
                && org.liquidphase.sync.PhaseHandshake.hasLiquidInteraction(bits));
        assertTrue("phase-disabled config advertises nothing",
            !org.liquidphase.sync.PhaseHandshake.hasPhase(
                org.liquidphase.sync.PhaseHandshake.bits(false, false)));
    }

    // ------------------------------------------------------------------
    // 相容宿主覆盖面（docs/host-coverage-audit.md §4 的逐项回归，0.1.13）
    // ------------------------------------------------------------------

    /**
     * 固相"整格碰撞"（决策点 D7）对**全部**相容宿主成立（audit §4.4，0.1.13 修）。
     * 0.1.12 及以前挂在块级 {@code BlockBehaviour.getCollisionShape}，被
     * {@code CrossCollisionBlock}（栅栏/玻璃板/铁栏杆/铜栏杆）、{@code WallBlock}、
     * {@code WallHangingSignBlock}、{@code ScaffoldingBlock}、{@code SculkShriekerBlock}、
     * {@code BigDripleafBlock} 自己覆写且不转调 super，这 66–88 个宿主的固相碰撞仍是宿主形状。
     * 现在挂在状态级 {@code BlockStateBase.getCollisionShape} 的 RETURN 上：
     * 三参覆盖实体碰撞主路径，两参覆盖 {@code Cache.collisionShape} 缓存路径
     * （{@code isCollisionShapeFullBlock} 走它）。
     */
    private static void checkSolidPhaseCollision(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        // 代表宿主逐个断言：形状表宿主（栅栏/墙/墙悬挂告示牌）、自建形状宿主（脚手架/大垂滴叶）、
        // 以及基类分发的普通宿主（楼梯/台阶）。既查三参（实体碰撞），也查两参（缓存路径）。
        for (Block block : new Block[]{Blocks.OAK_FENCE, Blocks.BRICK_WALL, Blocks.OAK_WALL_HANGING_SIGN,
                Blocks.SCAFFOLDING, Blocks.BIG_DRIPLEAF, Blocks.OAK_STAIRS, Blocks.STONE_SLAB}) {
            BlockState solid = PhaseHelper.withPhase(block.defaultBlockState(), Phase.OBSIDIAN);
            assertTrue("solid phase collision is a full cube via the 3-arg query (" + block + ")",
                Block.isShapeFullBlock(solid.getCollisionShape(level, pos, CollisionContext.empty())));
            assertTrue("solid phase collision is a full cube via the cached 2-arg query (" + block + ")",
                Block.isShapeFullBlock(solid.getCollisionShape(level, pos)));
        }
        // 全量：每个相容宿主的固相碰撞都必须是整格（新增宿主自动被扫进来）。
        java.util.List<String> broken = new java.util.ArrayList<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)
                || !PhaseHelper.hasPhaseProperty(block.defaultBlockState())) {
                continue;
            }
            BlockState solid = PhaseHelper.withPhase(block.defaultBlockState(), Phase.OBSIDIAN);
            if (!Block.isShapeFullBlock(solid.getCollisionShape(level, pos, CollisionContext.empty()))
                || !Block.isShapeFullBlock(solid.getCollisionShape(level, pos))) {
                broken.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block)));
            }
        }
        assertTrue("every phase host has full-cube solid-phase collision (broken: " + broken + ")",
            broken.isEmpty());
        // 反向：红石相不参与整格碰撞，保持宿主形状。
        BlockState redstoneFence = PhaseHelper.withPhase(Blocks.OAK_FENCE.defaultBlockState(), Phase.REDSTONE_15);
        assertTrue("redstone phase keeps the host collision shape",
            !Block.isShapeFullBlock(redstoneFence.getCollisionShape(level, pos, CollisionContext.empty())));
    }

    /**
     * 破坏保留对**全部**相容宿主成立，包括带 offsetType 的 4–5 个
     * （audit §4.5，0.1.13 修：0.1.12 及以前 {@code !state.hasOffsetFunction()} 把
     * hanging_roots / mangrove_propagule / pointed_dripstone / small_dripleaf
     * （26.3 另有 sulfur_spike）排除在外，破坏它们相直接消失）。
     */
    private static void checkBreakRetainEverywhere(ServerLevel level, BlockPos base) {
        // 对照：普通宿主（楼梯）破坏 → 原地留冰。
        BlockPos pos = base.offset(20, 1, 0);
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.ICE), Block.UPDATE_ALL);
        level.destroyBlock(pos, false);
        assertTrue("breaking a phased stairs leaves the ice behind (got "
                + level.getBlockState(pos).getBlock() + ")",
            level.getBlockState(pos).is(Blocks.ICE));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // 带 offsetType 的宿主逐个验证：破坏后必须留下相实体化的方块。
        java.util.List<String> broken = new java.util.ArrayList<>();
        int checked = 0;
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)
                || !PhaseHelper.hasPhaseProperty(block.defaultBlockState())
                || !block.defaultBlockState().hasOffsetFunction()) {
                continue;
            }
            checked++;
            BlockState solid = PhaseHelper.withPhase(block.defaultBlockState(), Phase.ICE);
            level.setBlock(pos, solid, Block.UPDATE_ALL);
            level.destroyBlock(pos, false);
            if (!level.getBlockState(pos).is(Blocks.ICE)) {
                broken.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block))
                    + " (got " + level.getBlockState(pos).getBlock() + ")");
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        assertTrue("offset-type hosts still retain their phase on break ("
                + checked + " checked, broken: " + broken + ")",
            broken.isEmpty());
        // 反向：这条扫描必须真的命中了 offset 宿主（每版本 4–5 个），否则断言落空。
        assertTrue("the offset-host scan actually inspected some hosts (" + checked + " checked)",
            checked >= 4);
    }

    /**
     * 台阶/蜡烛/营火（1.21.10+ 另有干化恶魂）自己覆写 {@code canPlaceLiquid}/{@code placeLiquid}，
     * 遮住了 {@code PhaseCapable} 的熔岩分支（audit §4.3，0.1.13 修）：
     * "相邻熔岩按形状灌入"必须重新成立。这里直接调宿主的遮蔽方法——
     * 与 {@code FlowingFluid.canHoldFluid}/{@code spreadTo} 同一条引擎路径。
     */
    private static void checkAdjacentLavaFilling(ServerLevel level, BlockPos base) {
        // (1) 台阶：canPlaceLiquid 对熔岩放行（0.1.12 及以前恒拒 → 引擎层面熔岩灌不进）。
        BlockPos slabPos = base.offset(22, 1, 0);
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
        level.setBlock(slabPos, slab, Block.UPDATE_ALL);
        assertTrue("slab accepts adjacent lava (canPlaceLiquid is no longer shadowed)",
            liquidphase$canPlaceLava((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.STONE_SLAB,
                level, slabPos, slab));
        assertTrue("slab placeLiquid fills the lava phase (got "
                + PhaseHelper.getPhase(level.getBlockState(slabPos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.STONE_SLAB)
                .placeLiquid(level, slabPos, slab, Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(slabPos)));
        level.setBlock(slabPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (2) 蜡烛 / 营火：placeLiquid 遮蔽只处理水、对熔岩返回 false → 引擎每 tick 重试、永久灌不进。
        BlockPos candlePos = base.offset(23, 1, 0);
        BlockState candle = Blocks.CANDLE.defaultBlockState();
        level.setBlock(candlePos, candle, Block.UPDATE_ALL);
        assertTrue("candle placeLiquid fills the lava phase (got "
                + PhaseHelper.getPhase(level.getBlockState(candlePos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.CANDLE)
                .placeLiquid(level, candlePos, candle, Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(candlePos)));
        level.setBlock(candlePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        BlockPos campfirePos = base.offset(24, 1, 0);
        BlockState campfire = Blocks.CAMPFIRE.defaultBlockState();
        level.setBlock(campfirePos, campfire, Block.UPDATE_ALL);
        assertTrue("campfire placeLiquid fills the lava phase (got "
                + PhaseHelper.getPhase(level.getBlockState(campfirePos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.CAMPFIRE)
                .placeLiquid(level, campfirePos, campfire, Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(campfirePos)));
        level.setBlock(campfirePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        //? if 1.21.10 || 1.21.11 || 26.3 {
        /*BlockPos ghastPos = base.offset(25, 1, 0);
        BlockState ghast = Blocks.DRIED_GHAST.defaultBlockState();
        level.setBlock(ghastPos, ghast, Block.UPDATE_ALL);
        assertTrue("dried ghast placeLiquid fills the lava phase (got "
                + PhaseHelper.getPhase(level.getBlockState(ghastPos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.DRIED_GHAST)
                .placeLiquid(level, ghastPos, ghast, Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(ghastPos)));
        level.setBlock(ghastPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
         *///?}

        // 反向（0.1.17 反转）：含水台阶是"现有填充相=水（可替换）"——熔岩替换水，
        // 台阶变为熔岩相且不再含水。水的流体容器路径对"写入水"仍只进空宿主。
        BlockPos wetSlabPos = base.offset(22, 1, 2);
        BlockState wetSlab = Blocks.STONE_SLAB.defaultBlockState()
            .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        level.setBlock(wetSlabPos, wetSlab, Block.UPDATE_ALL);
        assertTrue("waterlogged slab accepts lava (water is a replaceable filling; got phase "
                + PhaseHelper.getPhase(level.getBlockState(wetSlabPos)) + ")",
            ((net.minecraft.world.level.block.LiquidBlockContainer) Blocks.STONE_SLAB)
                .placeLiquid(level, wetSlabPos, level.getBlockState(wetSlabPos), Fluids.LAVA.getSource(false))
                && PhaseHelper.isLava(level.getBlockState(wetSlabPos))
                && !level.getBlockState(wetSlabPos)
                    .getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED));
        level.setBlock(wetSlabPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** {@return 宿主能否接受熔岩（签名按版本分支，与 {@code FlowingFluid.canHoldFluid} 同参）} */
    private static boolean liquidphase$canPlaceLava(net.minecraft.world.level.block.LiquidBlockContainer container,
                                                    net.minecraft.world.level.BlockGetter level, BlockPos pos,
                                                    BlockState state) {
        //? if 1.19.4 || 1.20.1 {
        /*return container.canPlaceLiquid(level, pos, state, Fluids.LAVA);
         *///?} elif 1.21.1 {
        /*return container.canPlaceLiquid(null, level, pos, state, Fluids.LAVA);
         *///?} else {
        return container.canPlaceLiquid(null, level, pos, state, Fluids.LAVA);
        //?}
    }

    /**
     * glow_lichen / sculk_vein 的 {@code getStateForPlacement} 由 {@code MultifaceBlock} 声明
     * （全版本如此），PlacementMixin 0.1.12 及以前只在 1.21.10+ 挂它 → 1.19.4–1.21.1
     * 放进熔岩源格不吸收相（audit §4.6，0.1.13 修）。
     */
    /**
     * glow_lichen / sculk_vein 的 {@code getStateForPlacement} 由 {@code MultifaceBlock} 声明
     * （全版本如此），PlacementMixin 0.1.12 及以前只在 1.21.10+ 挂它 → 1.19.4–1.21.1
     * 放进熔岩源格不吸收相（audit §4.6，0.1.13 修）。
     * <p>
     * 场景按真实放置姿势摆：点熔岩源格**旁边**的石头侧面（原版 context 的 clickedPos
     * 会落到熔岩源格上），宿主状态算出来后应带上熔岩相。
     */
    private static void checkPlacementAbsorption(ServerLevel level, BlockPos base) {
        BlockPos lavaPos = base.offset(26, 1, 0);
        BlockPos stonePos = lavaPos.west();
        level.setBlock(lavaPos, Fluids.LAVA.defaultFluidState().createLegacyBlock(), Block.UPDATE_ALL);
        level.setBlock(stonePos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        net.minecraft.server.level.ServerPlayer player = liquidphase$fakePlayer(level);
        for (Block block : new Block[]{Blocks.GLOW_LICHEN, Blocks.SCULK_VEIN}) {
            // 点石头的东侧面（朝熔岩格）：clicked 块不可替换 → context 落点 = 熔岩格。
            Vec3 hitVec = new Vec3(stonePos.getX() + 1.5, stonePos.getY() + 0.5, stonePos.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.EAST, stonePos, false);
            BlockPlaceContext context = new BlockPlaceContext(level, player,
                net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(block), hit);
            assertTrue("placement context targets the lava source tile (got " + context.getClickedPos() + ")",
                context.getClickedPos().equals(lavaPos));
            BlockState placed = block.getStateForPlacement(context);
            assertTrue(block + " absorbs the lava phase when placed into a lava source (got "
                    + (placed == null ? "null" : PhaseHelper.getPhase(placed)) + ")",
                placed != null && PhaseHelper.getPhase(placed) == Phase.LAVA);
        }
        level.setBlock(lavaPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stonePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 熔岩桶右键按**原版倒水**的交互优先级（audit §4.7，0.1.13 修）：
     * 原版交互不消费右键的宿主（楼梯）→ 灌入；原版交互消费右键的宿主（活板门开关、
     * 光源方块调亮度）→ 原版行为优先、不灌入。0.1.12 及以前 {@code BucketUseMixin} 在
     * use/useItemOn 上强制灌入（连箱子/告示牌都灌），≤1.20.1 与 ≥1.21.1 的目标清单还不一致；
     * 0.1.13 删除该 mixin，让灌入完全走原版桶路径（useItemOn 通过 → useItem →
     * emptyContents → BucketItemMixin），与"用水桶点活板门只会开关它"的语义对齐。
     * 潜行右键（原版绕过方块交互）仍可灌入，发射器/放置吸收不受影响。
     */
    private static void checkBucketInteraction(ServerLevel level, BlockPos base) {
        net.minecraft.server.level.ServerPlayer player = liquidphase$fakePlayer(level);

        // (1) 楼梯：原版不消费右键 → 熔岩灌入。useItemOn 返回 PASS 时，
        // 真实客户端会补发 UseItem 包（useItem → BucketItem.use），这里走同一条链。
        BlockPos stairPos = base.offset(18, 1, 0);
        level.setBlock(stairPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairPos, Blocks.OAK_STAIRS.defaultBlockState(), Block.UPDATE_ALL);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET));
        liquidphase$lookAt(player, stairPos);
        BlockHitResult stairHit = new BlockHitResult(Vec3.atCenterOf(stairPos), Direction.UP, stairPos, false);
        net.minecraft.world.InteractionResult interactionResult = player.gameMode.useItemOn(player, level,
            player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
            net.minecraft.world.InteractionHand.MAIN_HAND, stairHit);
        if (!interactionResult.consumesAction()) {
            player.gameMode.useItem(player, level,
                player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
                net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        assertTrue("lava bucket pours into stairs (vanilla interaction does not consume the click; got "
                + PhaseHelper.getPhase(level.getBlockState(stairPos)) + ")",
            PhaseHelper.isLava(level.getBlockState(stairPos)));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(stairPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stairPos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (2) 活板门：原版 useWithoutItem 消费右键（开关）→ 不灌入、方块被开关。
        BlockPos trapPos = base.offset(18, 1, 2);
        level.setBlock(trapPos, Blocks.OAK_TRAPDOOR.defaultBlockState(), Block.UPDATE_ALL);
        boolean openBefore = level.getBlockState(trapPos)
            .getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET));
        liquidphase$lookAt(player, trapPos);
        BlockHitResult trapHit = new BlockHitResult(Vec3.atCenterOf(trapPos), Direction.UP, trapPos, false);
        player.gameMode.useItemOn(player, level,
            player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
            net.minecraft.world.InteractionHand.MAIN_HAND, trapHit);
        BlockState trapAfter = level.getBlockState(trapPos);
        assertTrue("lava bucket on a trapdoor toggles it instead of pouring (got phase "
                + PhaseHelper.getPhase(trapAfter) + ")",
            !PhaseHelper.isLava(trapAfter)
                && trapAfter.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN) != openBefore);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(trapPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // (3) 光源方块：原版 useWithoutItem 消费右键（非管理员 → CONSUME）→ 不灌入、亮度不变。
        BlockPos lightPos = base.offset(18, 1, 4);
        level.setBlock(lightPos, Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new ItemStack(net.minecraft.world.item.Items.LAVA_BUCKET));
        liquidphase$lookAt(player, lightPos);
        BlockHitResult lightHit = new BlockHitResult(Vec3.atCenterOf(lightPos), Direction.UP, lightPos, false);
        player.gameMode.useItemOn(player, level,
            player.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND),
            net.minecraft.world.InteractionHand.MAIN_HAND, lightHit);
        BlockState lightAfter = level.getBlockState(lightPos);
        assertTrue("lava bucket on a light block keeps the vanilla interaction (got phase "
                + PhaseHelper.getPhase(lightAfter) + ")",
            !PhaseHelper.isLava(lightAfter));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.setBlock(lightPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** 把假玩家放到目标正上方、俯视它（桶的 use 路径按玩家视角射线取目标格）。 */
    private static void liquidphase$lookAt(net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
        // 1.21.10+ 的 Entity.moveTo 改名 snapTo（guardian 逐版本核对）。
        //? if 1.21.10 || 1.21.11 || 26.3 {
        player.snapTo(pos.getX() + 0.5, pos.getY() + 3.0, pos.getZ() + 0.5, 0.0F, 90.0F);
        //?} else {
        /*player.moveTo(pos.getX() + 0.5, pos.getY() + 3.0, pos.getZ() + 0.5, 0.0F, 90.0F);
         *///?}
    }

    /**
     * 发射器灌宿主 + 空桶保宿主（audit v2 缺口 A/B，0.1.15 修）：
     * 0.1.14 及以前发射器液体桶对宿主整桶弹射、空桶把含水宿主连方块一起毁。
     * 直接调 {@code LiquidInteractions.dispenseIntoHost} / {@code collectWithEmptyBucket}
     * （发射器行为类的共享核心，与真实触发链同参）。
     */
    private static void checkDispenserHostFilling(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(24, 1, 0);
        for (Block host : new Block[]{Blocks.OAK_FENCE, Blocks.OAK_LEAVES}) {
            // (A) 熔岩灌入宿主
            level.setBlock(pos, host.defaultBlockState(), Block.UPDATE_ALL);
            assertTrue("dispenser lava fills " + host,
                Boolean.TRUE.equals(org.liquidphase.interact.LiquidInteractions.dispenseIntoHost(
                    level, pos, level.getBlockState(pos), true))
                    && PhaseHelper.isLava(level.getBlockState(pos)));
            // (A) 反向 1（0.1.17 反转）：熔岩相可替换 → 再灌熔岩幂等成功（状态不变）。
            assertTrue("dispenser lava idempotently re-fills the lava phase of " + host,
                Boolean.TRUE.equals(org.liquidphase.interact.LiquidInteractions.dispenseIntoHost(
                    level, pos, level.getBlockState(pos), true))
                    && PhaseHelper.isLava(level.getBlockState(pos)));
            // (A) 反向 2（0.1.17 反转）：含水宿主接受熔岩——水是可替换填充相，
            // 熔岩替换水，宿主变熔岩相且不再含水。
            level.setBlock(pos, host.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
            assertTrue("dispenser lava replaces the water in waterlogged " + host,
                Boolean.TRUE.equals(org.liquidphase.interact.LiquidInteractions.dispenseIntoHost(
                    level, pos, level.getBlockState(pos), true))
                    && PhaseHelper.isLava(level.getBlockState(pos))
                    && !level.getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED));
            level.setBlock(pos, host.defaultBlockState(), Block.UPDATE_ALL);
            // (A) 水灌入宿主
            assertTrue("dispenser water waterlogs " + host,
                Boolean.TRUE.equals(org.liquidphase.interact.LiquidInteractions.dispenseIntoHost(
                    level, pos, level.getBlockState(pos), false))
                    && level.getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED));
            // (B) 空桶收含水宿主：只收水、保宿主
            ItemStack bucket = org.liquidphase.interact.LiquidInteractions.collectWithEmptyBucket(
                level, pos, level.getBlockState(pos));
            assertTrue("dispenser empty bucket scoops water from " + host + " and keeps the block",
                bucket != null && bucket.is(net.minecraft.world.item.Items.WATER_BUCKET)
                    && level.getBlockState(pos).is(host)
                    && !level.getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED));
            // (B) 反向：干宿主无可收目标
            assertTrue("dispenser empty bucket ignores dry " + host,
                org.liquidphase.interact.LiquidInteractions.collectWithEmptyBucket(
                    level, pos, level.getBlockState(pos)) == null);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // (B) 熔岩相宿主：收相、保宿主
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_FENCE.defaultBlockState(), Phase.LAVA),
            Block.UPDATE_ALL);
        ItemStack lavaBucket = org.liquidphase.interact.LiquidInteractions.collectWithEmptyBucket(
            level, pos, level.getBlockState(pos));
        assertTrue("dispenser empty bucket scoops the lava phase and keeps the host",
            lavaBucket != null && lavaBucket.is(net.minecraft.world.item.Items.LAVA_BUCKET)
                && level.getBlockState(pos).is(Blocks.OAK_FENCE)
                && !PhaseHelper.isLava(level.getBlockState(pos)));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 熔岩相重流接线（audit v2 缺口 C，0.1.15 修）：scheduleLavaFlowTick 此前是死代码，
     * 邻居变化后源不再重排流体 tick。这里用 hasScheduledTick 做同步判别：
     * 放置本身不排 tick，邻居更新后必须排上。
     */
    private static void checkLavaReflowWiring(ServerLevel level, BlockPos base) {
        BlockPos fencePos = base.offset(28, 1, 0);
        BlockPos leavesPos = base.offset(29, 1, 0);
        for (Block host : new Block[]{Blocks.OAK_FENCE, Blocks.OAK_LEAVES}) {
            BlockPos pos = host == Blocks.OAK_FENCE ? fencePos : leavesPos;
            // Fluids.LAVA 的 scheduled tick 会随存档持久化——历次自检/探针用过的坐标都可能带
            // 未到期的残留 tick。先清空该位置的流体 tick，判别才可复现。
            clearFluidTicks(level, pos);
            clearFluidTicks(level, pos.east());
            level.setBlock(pos, PhaseHelper.withPhase(host.defaultBlockState(), Phase.LAVA),
                Block.UPDATE_CLIENTS);
            // 放置后把 tick 再清一次：这样后面出现的 tick 只可能来自
            // "邻居变化 → handleNeighborChanged → scheduleLavaFlowTick"这条接线（缺口 C）。
            clearFluidTicks(level, pos);
            level.setBlock(pos.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            assertTrue("lava phase " + host + " reschedules its flow tick on neighbour change",
                level.getFluidTicks().hasScheduledTick(pos, Fluids.LAVA));
            level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * 非直接点击吸收（0.1.16 新增手势）：点**旁边**的方块让红石粉/冰"落进"相容宿主格——
     * 原版对不可替换的落点格直接 FAIL，这条路径此前从未被接管（用户实测缺口）。
     * 反向：落点格是空气时走原版放置（红石粉落成线、冰落成方块），宿主不受影响。
     */
    private static void checkIndirectPhaseAbsorption(ServerLevel level, BlockPos base) {
        // 注意坐标语义：32 是被点击的石头，33/34 是落点格；破坏保留会把含固相的方块
        // 实体化成相材料（冰/黑曜石），所以每个子场景结束时必须把材料方块一并清掉。
        BlockPos stonePos = base.offset(32, 1, 0);
        BlockPos targetA = base.offset(33, 1, 0);
        BlockPos targetB = base.offset(34, 1, 0);
        level.setBlock(stonePos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // (1) 红石粉落进栅栏格 → 红石相
        level.setBlock(targetA, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.REDSTONE_WIRE, stonePos, Direction.EAST);
        assertTrue("indirect dust lands into fence as redstone phase",
            RedstoneBlocks.isRedstonePhase(level.getBlockState(targetA)));
        level.setBlock(targetA, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // (2) 冰落进树叶格 → 冰相
        level.setBlock(targetA, Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.ICE, stonePos, Direction.EAST);
        assertTrue("indirect ice lands into leaves as ice phase",
            PhaseHelper.getPhase(level.getBlockState(targetA)) == Phase.ICE);
        // 冰相破坏保留会把冰实体化，换用 targetB 做原版对照
        level.setBlock(targetA, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // (3) 反向：落点格是空气 → 原版放置（红石粉落成线）
        level.setBlock(targetB.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.REDSTONE_WIRE, targetA, Direction.EAST);
        assertTrue("dust into air cell stays vanilla wire",
            level.getBlockState(targetB).is(Blocks.REDSTONE_WIRE));
        level.setBlock(targetB, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(targetB.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // (4) 反向：非相材料（石头）落进栅栏格 → 原版 FAIL，栅栏不变
        level.setBlock(targetA, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        liquidphase$placeBlockInto(level, Blocks.STONE, stonePos, Direction.EAST);
        assertTrue("stone item does not replace the host cell",
            level.getBlockState(targetA).is(Blocks.OAK_FENCE));
        level.setBlock(targetA, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(stonePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 通用放置改写层（0.1.16）：任意来源经 {@code Level.setBlock} 向无相未含水宿主格
     * 写填充相材料（冰/黑曜石/红石线）→ 改写为宿主 + 填充相。同时断言排除口径：
     * 非材料方块、水、已有相的宿主都不改写。
     */
    private static void checkUniversalFillingPlacement(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(35, 1, 0);
        // (1) 冰写进栅栏格 → 栅栏 + 冰相
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("universal: ice setBlock into fence becomes ice phase",
            level.getBlockState(pos).is(Blocks.OAK_FENCE)
                && PhaseHelper.getPhase(level.getBlockState(pos)) == Phase.ICE);
        // (2) 黑曜石写进含冰栅栏格 → 互斥，原版覆盖
        level.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("universal: obsidian setBlock replaces the phased host (mutex)",
            level.getBlockState(pos).is(Blocks.OBSIDIAN));
        // (3) 红石线写进栅栏格 → 栅栏 + 红石相（功率随线）
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("universal: wire setBlock into fence becomes redstone phase",
            level.getBlockState(pos).is(Blocks.OAK_FENCE)
                && RedstoneBlocks.isRedstonePhase(level.getBlockState(pos)));
        // (4) 水写进栅栏格 → 不在材料口径（走容器路径），原版覆盖
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, net.minecraft.world.level.material.Fluids.WATER.defaultFluidState().createLegacyBlock(),
            Block.UPDATE_ALL);
        assertTrue("universal: water setBlock is not converted (container path owns water)",
            level.getBlockState(pos).is(Blocks.WATER));
        // (5) 接受层：冰物品的放置上下文里栅栏"可替换"；石头物品不可；
        // 含水栅栏可替换（水=可替换填充相，0.1.17 反转）且改写后不再含水。
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPlaceContext iceCtx = liquidphase$context(level, Blocks.ICE, pos.west(), Direction.EAST);
        BlockPlaceContext stoneCtx = liquidphase$context(level, Blocks.STONE, pos.west(), Direction.EAST);
        assertTrue("acceptance: fence is replaceable for an ice item placement",
            level.getBlockState(pos).canBeReplaced(iceCtx));
        assertTrue("acceptance: fence is NOT replaceable for a stone item placement",
            !level.getBlockState(pos).canBeReplaced(stoneCtx));
        level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
        assertTrue("acceptance: waterlogged fence is replaceable for an ice item placement "
                    + "(water is a replaceable filling)",
            level.getBlockState(pos).canBeReplaced(iceCtx));
        level.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("universal: ice setBlock into waterlogged fence becomes ice phase without water",
            level.getBlockState(pos).is(Blocks.OAK_FENCE)
                && PhaseHelper.getPhase(level.getBlockState(pos)) == Phase.ICE
                && !level.getBlockState(pos).getValue(BlockStateProperties.WATERLOGGED));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /**
     * 吸收门控的**防漂移对账**（0.1.17）。可替换填充相规则只有唯一判定源
     * {@code PhaseHelper.canAcceptPhase}，但接线分四层，各自都能被后来人单独改坏：
     * <ol>
     *   <li>A 判定源：{@code PhaseHelper.canAcceptPhase}；</li>
     *   <li>B 接受层：{@code BlockState.canBeReplaced(BlockPlaceContext)}
     *       （{@code FillingReplaceMixin}）；</li>
     *   <li>C 改写层：{@code Level.setBlock(材料)}（{@code FillingPlacementMixin}）；</li>
     *   <li>D 交互层：{@code BlockItem.place} 落点吸收（{@code BlockItemMixin}）。</li>
     * </ol>
     * 用例：一座楼梯 × 现有填充相 {空/含水/熔岩/冰/黑曜石/红石} × 写入材料
     * {冰/黑曜石/红石粉} 全矩阵，A==B==C==D 且接受时终态一致（相写上、水清掉）；
     * 熔岩/水作为写入材料走容器路径（E），对 A 同样对账，水对任何含相宿主保持互斥。
     * 另有全量扫描：每个相容宿主的默认态（及含水态）上 A==B。
     */
    private static void checkAbsorptionGateConsistency(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(31, 1, 0);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Block hostBlock = Blocks.OAK_STAIRS;

        // 现有填充相 → 预期接受（空/含水/熔岩可替换；冰/黑曜石/红石不可替换）。
        Object[][] currents = {
            {"empty", hostBlock.defaultBlockState(), true},
            {"waterlogged", hostBlock.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true), true},
            {"lava", PhaseHelper.withPhase(hostBlock.defaultBlockState(), Phase.LAVA), true},
            {"ice", PhaseHelper.withPhase(hostBlock.defaultBlockState(), Phase.ICE), false},
            {"obsidian", PhaseHelper.withPhase(hostBlock.defaultBlockState(), Phase.OBSIDIAN), false},
            {"redstone", PhaseHelper.withPhase(hostBlock.defaultBlockState(), Phase.REDSTONE_15), false},
        };
        Block[] materials = {Blocks.ICE, Blocks.OBSIDIAN, Blocks.REDSTONE_WIRE};

        for (Object[] current : currents) {
            String currentName = (String) current[0];
            BlockState currentState = (BlockState) current[1];
            boolean expected = (Boolean) current[2];

            for (Block material : materials) {
                Phase incoming = material == Blocks.REDSTONE_WIRE ? Phase.REDSTONE_15
                    : org.liquidphase.phase.PhaseAbsorption.phaseOfItem(material);
                String label = currentName + "->" + material;

                // A 判定源
                boolean a = PhaseHelper.canAcceptPhase(currentState, level, pos, incoming);
                assertTrue("gate A matches the replaceability rule for " + label, a == expected);

                // B 接受层：canBeReplaced 与 A 一致
                BlockPlaceContext ctx = liquidphase$context(level, material, pos, Direction.DOWN);
                boolean b = currentState.canBeReplaced(ctx);
                assertTrue("gate B (canBeReplaced) agrees with A for " + label, b == a);

                // C 改写层：Level.setBlock(材料) 与 A 一致，接受时终态一致
                level.setBlock(pos, currentState, Block.UPDATE_ALL);
                level.setBlock(pos, material.defaultBlockState(), Block.UPDATE_ALL);
                BlockState afterC = level.getBlockState(pos);
                boolean c = afterC.getBlock() == hostBlock && phaseMatches(afterC, incoming);
                assertTrue("gate C (setBlock rewrite) agrees with A for " + label, c == a);
                if (a) {
                    assertTrue("gate C final state carries the incoming phase for " + label,
                        (incoming == Phase.REDSTONE_15 ? RedstoneBlocks.isRedstonePhase(afterC)
                            : PhaseHelper.getPhase(afterC) == incoming)
                            && !afterC.getValue(BlockStateProperties.WATERLOGGED));
                }

                // D 交互层：BlockItem.place 落点吸收与 A 一致
                level.setBlock(pos, currentState, Block.UPDATE_ALL);
                BlockState placedD = liquidphase$placeBlockInto(level, material, pos, Direction.DOWN);
                boolean d = placedD != null && placedD.getBlock() == hostBlock
                    && phaseMatches(placedD, incoming);
                assertTrue("gate D (BlockItem.place) agrees with A for " + label, d == a);
            }

            // E 容器路径（熔岩作为写入材料）：placeLiquid(LAVA) 与 A 一致
            level.setBlock(pos, currentState, Block.UPDATE_ALL);
            boolean aLava = PhaseHelper.canAcceptPhase(currentState, level, pos, Phase.LAVA);
            clearFluidTicks(level, pos);
            boolean e = ((net.minecraft.world.level.block.LiquidBlockContainer) hostBlock)
                .placeLiquid(level, pos, level.getBlockState(pos), Fluids.LAVA.getSource(false));
            assertTrue("gate E (container lava) agrees with A for " + currentName, e == aLava);
            if (aLava) {
                assertTrue("gate E fills the lava phase for " + currentName,
                    PhaseHelper.isLava(level.getBlockState(pos)));
            }
            clearFluidTicks(level, pos);

            // 水作为写入材料：互斥不变——只有空宿主接受水，任何含相宿主拒绝（0.1.17 决策）。
            level.setBlock(pos, currentState, Block.UPDATE_ALL);
            boolean waterAccepted = ((net.minecraft.world.level.block.LiquidBlockContainer) hostBlock)
                .placeLiquid(level, pos, level.getBlockState(pos), Fluids.WATER.getSource(false));
            assertTrue("water stays on the container path and only enters empty hosts for "
                    + currentName,
                waterAccepted == currentName.equals("empty"));
            if (waterAccepted) {
                assertTrue("water waterlogs the empty host", level.getBlockState(pos)
                    .getValue(BlockStateProperties.WATERLOGGED));
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // 全量扫描：每个相容宿主的默认态与含水态上 A==B（新宿主自动被扫进来）。
        BlockPos probePos = base.offset(32, 1, 0);
        level.setBlock(probePos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        java.util.List<String> broken = new java.util.ArrayList<>();
        int scanned = 0;
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)
                || !PhaseHelper.hasPhaseProperty(block.defaultBlockState())) {
                continue;
            }
            scanned++;
            BlockState def = block.defaultBlockState();
            BlockState[] variants = def.hasProperty(BlockStateProperties.WATERLOGGED)
                ? new BlockState[]{def, def.setValue(BlockStateProperties.WATERLOGGED, true)}
                : new BlockState[]{def};
            for (BlockState variant : variants) {
                boolean a = PhaseHelper.canAcceptPhase(variant, level, probePos, Phase.ICE);
                BlockPlaceContext ctx = liquidphase$context(level, Blocks.ICE, probePos, Direction.DOWN);
                boolean b = variant.canBeReplaced(ctx);
                if (a != b) {
                    broken.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block))
                        + " (A=" + a + ", B=" + b + ")");
                }
            }
        }
        assertTrue("every phase host agrees between canAcceptPhase and canBeReplaced ("
                + scanned + " hosts scanned, broken: " + broken + ")",
            broken.isEmpty() && scanned >= 300);
    }

    /** {@return 状态的填充相是否等于给定材料对应的相（红石相按类型判）} */
    private static boolean phaseMatches(BlockState state, Phase incoming) {
        return incoming == Phase.REDSTONE_15
            ? RedstoneBlocks.isRedstonePhase(state)
            : PhaseHelper.getPhase(state) == incoming;
    }

    /**
     * 资源包兼容合并（0.1.17）：片段完整性 + 合并核心对"仿第三方包 winner"的行为 +
     * 代理包装的基础行为。合并核心 {@code PhasePackMergeCore} 是纯 Gson 实现，
     * 服务端也能直接跑；服务端资源管理器只有 data 域拿不到 blockstate，
     * 所以 winner 用 mod 自带的仿第三方 fixtures
     * （{@code assets/liquidphase/packmerge_test/}，variants 形式、变体键与原版一致）。
     * 客户端注入点（{@code PhaseLoaderMixin}）的真实覆盖需要客户端资源重载，
     * 不在服务端自检范围（README 记录手测项）。
     */
    private static void checkPackMergeFragments(ServerLevel level, BlockPos base) {
        // (1) 全量：每个相容宿主都有片段，且片段带红石外壳（与烘焙 blockstate 同判据）。
        java.util.List<String> missing = new java.util.ArrayList<>();
        int fragments = 0;
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)
                || !PhaseHelper.hasPhaseProperty(block.defaultBlockState())) {
                continue;
            }
            String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block));
            if (!id.startsWith("minecraft:")) {
                continue;
            }
            String fragment = liquidphase$resource("/assets/liquidphase/phase_parts/"
                + id.substring("minecraft:".length()) + ".json");
            fragments++;
            if (fragment == null || !fragment.contains("liquidphase:block/redstone_phase_")) {
                missing.add(id);
            }
        }
        assertTrue("every phase host ships a pack-merge fragment with the redstone shell ("
                + fragments + " checked, missing: " + missing + ")",
            missing.isEmpty() && fragments >= 300);

        // (2) 标记扫描：烘焙定义引用 liquidphase → 判定为"自带相部件"，不会被二次合并。
        String baked = liquidphase$resource("/assets/minecraft/blockstates/stone_slab.json");
        assertTrue("baked stone_slab blockstate ships in the jar", baked != null);
        if (baked == null) {
            return;
        }
        assertTrue("referencesLiquidphase detects the baked definition",
            org.liquidphase.client.PhasePackMergeCore.referencesLiquidphase(
                com.google.gson.JsonParser.parseString(baked)));
        assertTrue("referencesLiquidphase ignores foreign definitions",
            !org.liquidphase.client.PhasePackMergeCore.referencesLiquidphase(
                com.google.gson.JsonParser.parseString(liquidphase$foreignFixture("stone_slab"))));

        // (3) 合并核心 × 仿第三方包（variants 形式）：混合宿主（台阶）按变体门控。
        JsonObject slabWinner = com.google.gson.JsonParser.parseString(
            liquidphase$foreignFixture("stone_slab")).getAsJsonObject();
        JsonObject slabFragment = com.google.gson.JsonParser.parseString(
            liquidphase$resource("/assets/liquidphase/phase_parts/stone_slab.json")).getAsJsonObject();
        JsonObject slabMerged = org.liquidphase.client.PhasePackMergeCore.merge(slabWinner, slabFragment);
        assertTrue("merge converts the variants winner into multipart", slabMerged != null);
        if (slabMerged == null) {
            return;
        }
        String slabJson = slabMerged.toString();
        // 0.1.18 起不再按变体门控宿主：所有宿主 part 一律保持渲染，相只用内缩覆盖层。
        assertTrue("merged slab keeps every host variant ungated (host always visible)",
            slabJson.contains("\"type\":\"double\"") && !slabJson.contains("\"type\":\"double\",\"filling\""));
        assertTrue("merged slab keeps the bottom/top variants ungated",
            slabJson.contains("\"type\":\"bottom\"") && !slabJson.contains("\"type\":\"bottom\",\"filling\""));
        assertTrue("merged slab appends the inset overlays and the redstone shell",
            slabJson.contains("liquidphase:block/phase_ice")
                && !slabJson.contains("phase_ice_full")
                && slabJson.contains("liquidphase:block/redstone_phase_0")
                && slabJson.contains("liquidphase:block/redstone_phase_15"));

        // (4) 合并核心 × 仿第三方包：内缩宿主（楼梯）无 gate，宿主 part 原样。
        JsonObject stairsWinner = com.google.gson.JsonParser.parseString(
            liquidphase$foreignFixture("oak_stairs")).getAsJsonObject();
        JsonObject stairsFragment = com.google.gson.JsonParser.parseString(
            liquidphase$resource("/assets/liquidphase/phase_parts/oak_stairs.json")).getAsJsonObject();
        JsonObject stairsMerged = org.liquidphase.client.PhasePackMergeCore.merge(stairsWinner, stairsFragment);
        assertTrue("merge handles the inset host", stairsMerged != null);
        if (stairsMerged == null) {
            return;
        }
        String stairsJson = stairsMerged.toString();
        assertTrue("inset host parts stay ungated (no host-visibility gate)",
            !stairsJson.contains("\"filling\":\"none|lava|"));
        assertTrue("inset host keeps its variant keys after variants→multipart conversion",
            stairsJson.contains("\"facing\":\"east\"") && stairsJson.contains("\"half\":\"bottom\""));
        assertTrue("inset overlays and shell are appended",
            stairsJson.contains("liquidphase:block/phase_obsidian")
                && stairsJson.contains("liquidphase:block/redstone_phase_8"));

        // (4b) 合并核心 × 整格宿主（树叶）：宿主 part 一律保持渲染，相用内缩覆盖层。
        // 原始树叶是无条件单一变体，第三方包常拆成 per-distance 变体；
        // 两种形态下宿主都必须可见、相都必须内缩（否则与 0..16 的树叶共面 z-fighting）。
        JsonObject leavesWinner = com.google.gson.JsonParser.parseString(
            liquidphase$foreignFixture("oak_leaves")).getAsJsonObject();
        JsonObject leavesFragment = com.google.gson.JsonParser.parseString(
            liquidphase$resource("/assets/liquidphase/phase_parts/oak_leaves.json")).getAsJsonObject();
        JsonObject leavesMerged = org.liquidphase.client.PhasePackMergeCore.merge(leavesWinner, leavesFragment);
        assertTrue("merge handles the full-cube host", leavesMerged != null);
        if (leavesMerged == null) {
            return;
        }
        String leavesJson = leavesMerged.toString();
        assertTrue("split leaf variants stay ungated (host visible under every phase)",
            leavesJson.contains("\"distance\":\"1\",\"persistent\":\"false\"")
                && leavesJson.contains("\"distance\":\"3\",\"persistent\":\"true\"")
                && !leavesJson.contains("\"persistent\":\"false\",\"filling\"")
                && !leavesJson.contains("\"persistent\":\"true\",\"filling\""));
        assertTrue("full-cube host uses the inset phase material (no z-fighting with its 0..16 model)",
            leavesJson.contains("liquidphase:block/phase_ice")
                && !leavesJson.contains("phase_ice_full"));

        // (5) 代理包装：幂等 + 透传（用 ResourceManager.Empty 做最小可调用的 RM）。
        // 合并开关强制打开，避免本机配置文件影响自检结论（内存态，不落盘）。
        org.liquidphase.LiquidPhaseConfig.get().packMerge = true;
        ResourceManager empty = net.minecraft.server.packs.resources.ResourceManager.Empty.INSTANCE;
        ResourceManager proxied = org.liquidphase.client.PhasePackMerge.wrap(empty);
        assertTrue("wrap wraps a plain manager", proxied != empty);
        assertTrue("wrap is idempotent", org.liquidphase.client.PhasePackMerge.wrap(proxied) == proxied);
        assertTrue("proxy preserves empty blockstate listings",
            proxied.listResourceStacks("blockstates", id -> true).isEmpty());

        // (6) 分派识别回归（0.1.20）：识别必须只看**运行时实参/结果形状**，与方法名无关。
        // 生产环境方法名是 intermediary（1.21.11 实测 listResourceStacks = method_41265），
        // 0.1.19 及以前按 Yarn 名分派在生产环境静默失配——表现正是本 bug：
        // 日志有 "pack merge active" 却没有 "blockstate merge scan"，相部件整体丢失。
        // 断言参数里没有 Method 对象，结构上杜绝"再改回按名分派"。
        assertTrue("blockstates stack call is recognized by args alone",
            org.liquidphase.client.PhasePackMerge.isBlockstateStacksCall(
                new Object[]{"blockstates", new Object()}));
        assertTrue("other directories are not intercepted",
            !org.liquidphase.client.PhasePackMerge.isBlockstateStacksCall(
                new Object[]{"models", new Object()}));
        assertTrue("non-string first args are not intercepted",
            !org.liquidphase.client.PhasePackMerge.isBlockstateStacksCall(
                new Object[]{new Object(), new Object()}));
        assertTrue("wrong arity is not intercepted",
            !org.liquidphase.client.PhasePackMerge.isBlockstateStacksCall(new Object[]{"blockstates"}));
        assertTrue("stack maps (List values) get merged",
            org.liquidphase.client.PhasePackMerge.isStackMap(
                Map.of("minecraft:blockstates/oak_fence.json", List.of())));
        assertTrue("single-resource maps are passed through",
            !org.liquidphase.client.PhasePackMerge.isStackMap(
                Map.of("minecraft:blockstates/oak_fence.json", new Object())));
        assertTrue("empty maps are passed through",
            !org.liquidphase.client.PhasePackMerge.isStackMap(Map.of()));
    }

    /** {@return 仿第三方包的 winner fixture 文本（assets/liquidphase/packmerge_test/）} */
    private static String liquidphase$foreignFixture(String blockName) {
        String fixture = liquidphase$resource("/assets/liquidphase/packmerge_test/"
            + blockName + "_foreign.json");
        assertTrue("foreign-winner fixture ships for " + blockName, fixture != null);
        return fixture;
    }

    /** 清空某位置全部流体 scheduled tick（自检场地复用坐标，残留 tick 会污染 hasScheduledTick）。 */
    private static void clearFluidTicks(ServerLevel level, BlockPos pos) {
        ((net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.material.Fluid>) level.getChunkAt(pos).getFluidTicks())
            .removeIf(tick -> tick.pos().equals(pos));
    }

    /**
     * 台阶的相渲染（0.1.18 起统一）：所有变体都用**内缩覆盖层**，宿主 part 一律保持渲染。
     * <p>
     * 0.1.13–0.1.17 按变体拆分（type=double 换 phase_*_full 并屏蔽宿主）。问题有两个：
     * 屏蔽宿主让双台阶含固相变成"一整块冰/黑曜石"，与含熔岩（流体渲染、宿主照常可见）不一致；
     * phase_*_full 与 double 变体的整块模型共面，会 z-fighting。
     * 现在双台阶与半砖外观一致，宿主始终可见。
     */
    private static void checkSlabVariantRendering() {
        String slab = liquidphase$resource("/assets/minecraft/blockstates/stone_slab.json");
        assertTrue("stone slab blockstate resource is present", slab != null);
        if (slab == null) {
            return;
        }
        assertTrue("double slab uses the inset phase material (not the full one)",
            !slab.contains("phase_ice_full") && !slab.contains("phase_obsidian_full"));
        // 三个阶段变体（bottom/double/top）都保持宿主 part 常驻，不带相条件。
        assertTrue("every slab variant keeps its host part ungated",
            slab.contains("\"type\":\"bottom\"")
                && slab.contains("\"type\":\"double\"")
                && slab.contains("\"type\":\"top\"")
                && !slab.contains("\"type\":\"double\",\"filling\"")
                && !slab.contains("\"type\":\"bottom\",\"filling\"")
                && !slab.contains("\"type\":\"top\",\"filling\""));
        // 内缩覆盖层：条件只有 filling，不再按变体拆分（也没有 "!double" 取反）。
        assertTrue("the inset overlay is unconditional on slab variant (only filling gates it)",
            slab.contains("{\"when\":{\"filling\":\"obsidian\"}")
                && !slab.contains("!double"));
        // 反向：部分覆盖宿主（楼梯）不应突然获得整格层（现有断言已在 checkRedstoneRenderParts 覆盖）。
    }

    // ------------------------------------------------------------------
    // 炼药锅
    // ------------------------------------------------------------------

    private static void checkCauldron(ServerLevel level, BlockPos base) {
        BlockPos full = base.offset(0, 1, 4);
        BlockPos partial = base.offset(0, 1, 6);
        BlockPos lava = base.offset(0, 1, 8);

        level.setBlock(full, Blocks.WATER_CAULDRON.defaultBlockState()
            .setValue(LayeredCauldronBlock.LEVEL, LayeredCauldronBlock.MAX_FILL_LEVEL), Block.UPDATE_ALL);
        level.setBlock(partial, Blocks.WATER_CAULDRON.defaultBlockState()
            .setValue(LayeredCauldronBlock.LEVEL, 1), Block.UPDATE_ALL);
        level.setBlock(lava, Blocks.LAVA_CAULDRON.defaultBlockState(), Block.UPDATE_ALL);

        assertTrue("full water cauldron is a liquid mine target", PhaseTargeting.isLiquidMineTarget(level, full));
        assertTrue("partial water cauldron is NOT a liquid mine target",
            !PhaseTargeting.isLiquidMineTarget(level, partial));
        assertTrue("lava cauldron is a liquid mine target", PhaseTargeting.isLiquidMineTarget(level, lava));
    }

    private static void clearArea(ServerLevel level, BlockPos base) {
        for (int x = -1; x <= 35; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -1; z <= 9; z++) {
                    level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            passed++;
            LiquidPhase.LOGGER.info("[Liquid Phase][selftest] PASS {}", name);
        } else {
            failed++;
            LiquidPhase.LOGGER.error("[Liquid Phase][selftest] FAIL {}", name);
        }
    }
}
