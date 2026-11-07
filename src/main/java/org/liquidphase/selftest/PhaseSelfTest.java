package org.liquidphase.selftest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
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
 * 覆盖的是"无玩家参与"的确定性行为：红石相 = phase 取值、宿主形状不变、信号衰减、
 * 供电即时性、向红石线格放置、炼药锅取水门槛。
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
            checkShapeAndBlockIdentity(level, base);
            checkSignal(level, base);
            checkInstantUpdate(level, base);
            checkPlaceIntoWire(level, base);
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
    // 信号：向红石线 -1，向固相满功率，DOWN 不输出，被覆盖面不输出
    // ------------------------------------------------------------------

    private static void checkSignal(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(0, 1, 0);
        level.setBlock(pos, PhaseHelper.withPhase(Blocks.OAK_STAIRS.defaultBlockState(), Phase.REDSTONE_15),
            Block.UPDATE_ALL);

        // 默认橡木楼梯朝北：北面被台阶踏面盖满（按设计不输出），南面才是露出的。
        BlockState stairs = level.getBlockState(pos);
        assertTrue("stair host hides NORTH face when facing north", !RedstoneBlocks.exposed(stairs, Direction.NORTH));
        assertTrue("stair host exposes SOUTH face when facing north", RedstoneBlocks.exposed(stairs, Direction.SOUTH));

        // 信号是即时结算的：必须放真实电源，且只能放在露出的南面。
        level.setBlock(pos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("redstone phase keeps power 15 (got " + RedstoneBlocks.getPower(level.getBlockState(pos)) + ")",
            RedstoneBlocks.getPower(level.getBlockState(pos)) == 15);

        // 南侧换成红石线 → 以 NORTH 查询（接收方在南）→ 15 - 1 = 14
        level.setBlock(pos.south(), Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        int toWire = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into wire is power-1 (got " + toWire + ")", toWire == 14);

        // 换成固相 → 同一方向满功率
        level.setBlock(pos.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        int toStone = level.getSignal(pos, Direction.NORTH);
        assertTrue("signal into solid is full power (got " + toStone + ")", toStone == 15);

        assertTrue("signal DOWN is 0", level.getSignal(pos, Direction.DOWN) == 0);

        // 双台阶（整格宿主）被完全覆盖 → 不输出
        BlockPos slabPos = base.offset(2, 1, 0);
        level.setBlock(slabPos, PhaseHelper.withPhase(
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE),
            Phase.REDSTONE_15), Block.UPDATE_ALL);
        level.setBlock(slabPos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(slabPos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("covered face does not output", level.getSignal(slabPos, Direction.NORTH) == 0);
    }

    // ------------------------------------------------------------------
    // 供电即时性：邻居变化后同一 tick 内功率就已更新（不走计划刻）
    // ------------------------------------------------------------------

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
    // 向红石线所在格放置宿主方块
    // ------------------------------------------------------------------

    private static void checkPlaceIntoWire(ServerLevel level, BlockPos base) {
        BlockPos pos = base.offset(6, 1, 0);
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, Blocks.REDSTONE_WIRE.defaultBlockState(), Block.UPDATE_ALL);
        // 电源放南侧：默认楼梯朝北，北面被盖住（按设计不供电），南面才是露出的。
        level.setBlock(pos.south().below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        assertTrue("wire placed for placement test", level.getBlockState(pos).is(Blocks.REDSTONE_WIRE));
        int wirePower = level.getBlockState(pos).getValue(BlockStateProperties.POWER);
        assertTrue("wire powered by redstone block (got " + wirePower + ")", wirePower == 15);

        boolean placed = RedstoneBlocks.placeHostIntoWire(level, pos,
            Blocks.OAK_STAIRS.defaultBlockState(), wirePower);
        assertTrue("host placed into wire tile", placed);
        BlockState actual = level.getBlockState(pos);
        assertTrue("tile became the host block with redstone phase",
            actual.is(Blocks.OAK_STAIRS) && RedstoneBlocks.isRedstonePhase(actual));
        assertTrue("carried the wire power (wire=" + wirePower + ", got "
                + RedstoneBlocks.getPower(actual) + ")",
            RedstoneBlocks.getPower(actual) == wirePower);
    }

    // ------------------------------------------------------------------
    // 炼药锅：只有满锅才是取水目标
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

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void clearArea(ServerLevel level, BlockPos base) {
        for (int x = -1; x <= 8; x++) {
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
