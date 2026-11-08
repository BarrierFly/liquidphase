package org.liquidphase.selftest;

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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
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
            checkWireStaysUnreplaceable(level, base);
            checkRedstoneRenderParts();
            checkCauldron(level, base);
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
        assertTrue("none is neither", !Phase.NONE.isSolid() && !Phase.NONE.isRedstone());
    }

    // ------------------------------------------------------------------
    // 红石相 = 宿主方块 + phase 取值（不再有新方块）
    // ------------------------------------------------------------------

    private static void checkPhasePropertyCoverage() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!(block instanceof org.liquidphase.phase.PhaseCapable)) {
                continue;
            }
            if (!block.defaultBlockState().hasProperty(PhaseHelper.PHASE)) {
                missing.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block)));
            }
        }
        assertTrue("every PhaseCapable block has the phase property (missing: " + missing + ")",
            missing.isEmpty());
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
        java.io.InputStream in = LiquidPhase.class.getResourceAsStream(
            "/assets/minecraft/blockstates/oak_stairs.json");
        if (in == null) {
            assertTrue("stair blockstate resource is present", false);
            return;
        }
        String json;
        try (java.io.InputStream stream = in) {
            json = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            assertTrue("stair blockstate resource is readable", false);
            return;
        }
        assertTrue("stair blockstate keeps rendering the host under redstone phase",
            json.contains("\"shape\":\"straight\"")
                && json.contains("redstone_15")
                && hostPhaseConditionCoversRedstone(json));
        assertTrue("stair blockstate adds a redstone shell overlay",
            json.contains("liquidphase:block/redstone_phase"));
    }

    private static boolean hostPhaseConditionCoversRedstone(String json) {
        int idx = json.indexOf("\"shape\"");
        while (idx >= 0) {
            int phase = json.indexOf("\"phase\":\"", idx);
            if (phase >= 0 && phase - idx < 400) {
                int end = json.indexOf('"', phase + 9);
                if (end > 0) {
                    String value = json.substring(phase + 9, end);
                    if (value.contains("redstone_15")) {
                        return true;
                    }
                }
            }
            idx = json.indexOf("\"shape\"", idx + 1);
        }
        return false;
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
        for (int x = -1; x <= 18; x++) {
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
