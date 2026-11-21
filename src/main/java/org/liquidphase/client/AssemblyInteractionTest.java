//? if 1.21.11 {
package org.liquidphase.client;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.liquidphase.LiquidPhase;
import org.liquidphase.phase.PhaseExtra;
import org.liquidphase.phase.PhaseExtras;
import org.liquidphase.redstone.RedstoneBlocks;

/**
 * 组装交互回归（0.2.1）：**真实玩家点击链路**的组装必须成立。
 * <p>
 * 服务端自检与栅栏渲染回归都是直接调 mod 内部 API，绕过了玩家交互层；
 * 这里用 {@code gameMode.useItemOn}（客户端预测 + 网络包 + 服务端执行，与真人
 * 点击同一条链）把"基本组装方式"钉住：
 * <ol>
 *   <li>红石粉右键相容宿主（楼梯）→ 该格写入红石相条目，不放置粉、不消耗失败；</li>
 *   <li>相容宿主方块放进红石线格 → 宿主落格并继承线功率；</li>
 *   <li>潜行右键下半砖放中继器/比较器/侦测器 → 元件条目 + 中继器真实输出到线；</li>
 *   <li>反向断言：非宿主上红石粉照常放置、不潜行放中继器照常放在宿主上面一格。</li>
 * </ol>
 * 断言失败逐条打印并让 run 失败。跑法：{@code ./gradlew :1.21.11:runClientGameTest}。
 */
public final class AssemblyInteractionTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitTicks(20);
        try (TestSingleplayerContext sp = context.worldBuilder()
                .create()) {
            sp.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null);
            BlockPos feet = context.computeOnClient(client -> client.player.blockPosition());
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("weather clear");

            BlockPos stairsPos = feet.offset(0, 0, 3);
            BlockPos wirePos = feet.offset(3, 0, 3);
            BlockPos slabPos = feet.offset(-3, 0, 3);
            sp.getServer().runOnServer(server ->
                buildScene(server, feet, stairsPos, wirePos, slabPos));
            context.waitTicks(2);

            List<String> failures = new ArrayList<>();
            List<List<String>> reports = List.of(
                testDustOnStairs(context, sp, stairsPos),
                testStairsIntoWire(context, sp, wirePos, feet),
                testPlainClickElements(context, sp, slabPos),
                testVanillaFallbacks(context, sp, feet));
            for (List<String> report : reports) {
                for (String line : report) {
                    if (line.contains("FAIL")) {
                        LiquidPhase.LOGGER.error("[assembly] {}", line);
                        failures.add(line);
                    } else {
                        LiquidPhase.LOGGER.info("[assembly] {}", line);
                    }
                }
            }

            if (!failures.isEmpty()) {
                throw new AssertionError("basic assembly broken:\n  " + String.join("\n  ", failures));
            }
            LiquidPhase.LOGGER.info("[assembly] real-click assembly: dust->host, host->wire, "
                + "plain element x3, vanilla fallbacks all pass");
        }
    }

    private static void buildScene(MinecraftServer server, BlockPos feet,
            BlockPos stairsPos, BlockPos wirePos, BlockPos slabPos) {
        Level level = server.getLevel(Level.OVERWORLD);
        for (int x = -8; x <= 8; x++) {
            for (int z = -3; z <= 8; z++) {
                level.setBlockAndUpdate(feet.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        clearColumn(level, stairsPos);
        level.setBlockAndUpdate(stairsPos, Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH));
        clearColumn(level, wirePos);
        level.setBlockAndUpdate(wirePos, Blocks.REDSTONE_WIRE.defaultBlockState());
        clearColumn(level, slabPos);
        level.setBlockAndUpdate(slabPos, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        // 元件场景 2/3 的下半砖
        for (BlockPos cell : new BlockPos[]{slabPos.offset(0, 0, -2), slabPos.offset(0, 0, -4)}) {
            clearColumn(level, cell);
            level.setBlockAndUpdate(cell, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        }
    }

    private static void clearColumn(Level level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
        PhaseExtras.set(level, pos, PhaseExtra.EMPTY);
        PhaseExtras.set(level, pos.above(), PhaseExtra.EMPTY);
    }

    /** 场景 1：红石粉右键楼梯 → 楼梯写入红石相，红石粉不落地。 */
    private static List<String> testDustOnStairs(ClientGameTestContext context, TestSingleplayerContext sp,
            BlockPos stairsPos) {
        giveHeld(context, sp, new ItemStack(Items.REDSTONE));
        InteractionResult result = click(context, sp, stairsPos, Direction.UP, false);
        context.waitTicks(3);
        List<String> out = new ArrayList<>();
        for (String line : sp.getServer().computeOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            BlockState state = level.getBlockState(stairsPos);
            int extra = PhaseExtras.get(level, stairsPos);
            return List.of(
                "dust->stairs block=" + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    + " extra=" + PhaseExtra.describe(extra)
                    + " above=" + BuiltInRegistries.BLOCK.getKey(level.getBlockState(stairsPos.above()).getBlock()));
        })) {
            out.add("dust->stairs result=" + result + " | " + line);
        }
        sp.getServer().runOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            if (!PhaseExtra.isRedstone(PhaseExtras.get(level, stairsPos))) {
                out.add("FAIL dust->stairs: no redstone entry after real click");
            }
            BlockState above = level.getBlockState(stairsPos.above());
            if (!above.isAir()) {
                out.add("FAIL dust->stairs: dust got placed on top instead of absorbing (" + above.getBlock() + ")");
            }
        });
        return out;
    }

    /** 场景 2：楼梯物品放进红石线格 → 宿主落格 + 继承功率。 */
    private static List<String> testStairsIntoWire(ClientGameTestContext context, TestSingleplayerContext sp,
            BlockPos wirePos, BlockPos feet) {
        // 电源放在线旁让线有 15 档，验证功率继承；楼梯朝向不挡输入面。
        sp.getServer().runOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(wirePos.offset(1, 0, 0), Blocks.REDSTONE_BLOCK.defaultBlockState());
            level.updateNeighborsAt(wirePos, Blocks.REDSTONE_WIRE);
        });
        context.waitTicks(2);
        giveHeld(context, sp, new ItemStack(Items.OAK_STAIRS));
        InteractionResult result = click(context, sp, wirePos, Direction.UP, false);
        context.waitTicks(3);
        List<String> out = new ArrayList<>();
        sp.getServer().computeOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            BlockState state = level.getBlockState(wirePos);
            int extra = PhaseExtras.get(level, wirePos);
            out.add("stairs->wire result=" + result + " | block="
                + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " extra=" + PhaseExtra.describe(extra));
            if (!(state.getBlock() instanceof org.liquidphase.phase.PhaseCapable)
                || state.getBlock() == Blocks.REDSTONE_WIRE) {
                out.add("FAIL stairs->wire: wire cell not replaced by host (got " + state.getBlock() + ")");
            }
            if (!PhaseExtra.isRedstone(extra)) {
                out.add("FAIL stairs->wire: no redstone entry inherited");
            } else if (PhaseExtra.power(extra) != 15) {
                out.add("FAIL stairs->wire: power not inherited from wire (got " + PhaseExtra.power(extra) + ")");
            }
            return null;
        });
        return out;
    }

    /** 场景 3：普通右键（不潜行）下半砖装配中继器/比较器/侦测器 + 中继器真实输出。 */
    private static List<String> testPlainClickElements(ClientGameTestContext context, TestSingleplayerContext sp,
            BlockPos slabPos) {
        List<String> out = new ArrayList<>();
        BlockPos[] cells = {slabPos, slabPos.offset(0, 0, -2), slabPos.offset(0, 0, -4)};
        // 侦测器 facing = 玩家看向方向；让玩家面朝东（观察面东，输出西）。
        context.runOnClient(client -> {
            client.player.setYRot(-90.0F);
            client.player.setOldPosAndRot();
        });
        sp.getServer().runOnServer(server -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            p.setYRot(-90.0F);
            p.setOldPosAndRot();
        });
        PhaseExtra.Kind[] kinds = {PhaseExtra.Kind.REPEATER, PhaseExtra.Kind.COMPARATOR, PhaseExtra.Kind.OBSERVER};
        for (int i = 0; i < kinds.length; i++) {
            BlockPos cell = cells[i];
            setSneak(context, sp, false);
            giveHeld(context, sp, switch (kinds[i]) {
                case REPEATER -> new ItemStack(Items.REPEATER);
                case COMPARATOR -> new ItemStack(Items.COMPARATOR);
                default -> new ItemStack(Items.OBSERVER);
            });
            InteractionResult result = click(context, sp, cell, Direction.UP, false);
            context.waitTicks(2);
            int index = i;
            sp.getServer().computeOnServer(server -> {
                Level level = server.getLevel(Level.OVERWORLD);
                BlockState state = level.getBlockState(cell);
                int extra = PhaseExtras.get(level, cell);
                out.add("element " + kinds[index] + " result=" + result + " | block="
                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " extra=" + PhaseExtra.describe(extra));
                if (PhaseExtra.kind(extra) != kinds[index]) {
                    out.add("FAIL element " + kinds[index] + ": wrong/absent entry (" + PhaseExtra.describe(extra) + ")");
                }
                return null;
            });
        }
        // 中继器输出端到端：输入面（西）放红石块，输出面（东）放线，等延迟后线应 15。
        BlockPos repeaterCell = cells[0];
        sp.getServer().runOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            int extra = PhaseExtras.get(level, repeaterCell);
            Direction facing = PhaseExtra.facing(extra);
            level.setBlockAndUpdate(repeaterCell.relative(facing), Blocks.REDSTONE_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(repeaterCell.relative(facing.getOpposite()), Blocks.REDSTONE_WIRE.defaultBlockState());
            level.updateNeighborsAt(repeaterCell, Blocks.SMOOTH_STONE_SLAB);
        });
        context.waitTicks(6);
        sp.getServer().computeOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            int extra = PhaseExtras.get(level, repeaterCell);
            Direction facing = PhaseExtra.facing(extra);
            BlockState frontWire = level.getBlockState(repeaterCell.relative(facing.getOpposite()));
            boolean powered = PhaseExtra.powered(extra);
            int wirePower = frontWire.is(Blocks.REDSTONE_WIRE)
                ? frontWire.getValue(BlockStateProperties.POWER) : -1;
            out.add("repeater output: facing=" + facing + " powered=" + powered + " wire=" + wirePower);
            if (!powered) {
                out.add("FAIL repeater: did not turn on with redstone block behind input face");
            }
            if (wirePower != 15) {
                out.add("FAIL repeater: front wire did not receive 15 (got " + wirePower + ")");
            }
            return null;
        });
        return out;
    }

    /** 场景 4：反向断言——原版行为不被劫持。 */
    private static List<String> testVanillaFallbacks(ClientGameTestContext context, TestSingleplayerContext sp,
            BlockPos feet) {
        List<String> out = new ArrayList<>();
        // (a) 非宿主（石头）上放红石粉 → 线正常放在石头上面，石头格无条目。
        // 注意距离：服务端校验交互距离（canInteractWithBlock），点击必须落在玩家
        // 交互范围内（≤4 格左右），否则点击被静默忽略——不是装配逻辑的问题。
        BlockPos stonePos = feet.offset(3, 0, -2);
        sp.getServer().runOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(stonePos, Blocks.SMOOTH_STONE.defaultBlockState());
            PhaseExtras.set(level, stonePos, PhaseExtra.EMPTY);
        });
        giveHeld(context, sp, new ItemStack(Items.REDSTONE));
        click(context, sp, stonePos, Direction.UP, false);
        context.waitTicks(2);
        sp.getServer().computeOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            BlockState above = level.getBlockState(stonePos.above());
            if (!above.is(Blocks.REDSTONE_WIRE)) {
                out.add("FAIL vanilla dust on stone: wire not placed on top (got " + above.getBlock() + ")");
            }
            if (!PhaseExtra.isEmpty(PhaseExtras.get(level, stonePos))) {
                out.add("FAIL vanilla dust on stone: stone got a bypass entry");
            }
            return null;
        });
        // (b) 右键**整格方块**放中继器（非宿主）→ 原版放在其上面一格，无元件条目。
        // （原版中继器要求下方 RIGID 支撑，下半砖顶面不合格，原版本来就不能放，
        // 所以基块必须用整格石头。）
        BlockPos slab2 = feet.offset(-2, 0, -3);
        sp.getServer().runOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            level.setBlockAndUpdate(slab2, Blocks.SMOOTH_STONE.defaultBlockState());
            PhaseExtras.set(level, slab2, PhaseExtra.EMPTY);
        });
        setSneak(context, sp, false);
        giveHeld(context, sp, new ItemStack(Items.REPEATER));
        InteractionResult repeaterResult = click(context, sp, slab2, Direction.UP, false);
        context.waitTicks(2);
        out.add("vanilla repeater on stone: click result=" + repeaterResult
            + " sneak=" + context.computeOnClient(client -> client.player.isShiftKeyDown()));
        sp.getServer().computeOnServer(server -> {
            Level level = server.getLevel(Level.OVERWORLD);
            BlockState above = level.getBlockState(slab2.above());
            if (!above.is(Blocks.REPEATER)) {
                out.add("FAIL vanilla repeater on stone: repeater block not placed on top (got " + above.getBlock() + ")");
            }
            if (!PhaseExtra.isEmpty(PhaseExtras.get(level, slab2))) {
                out.add("FAIL vanilla repeater on stone: stone got an element entry without sneak");
            }
            return null;
        });
        return out;
    }

    // ------------------------------------------------------------------
    // 真实点击链路工具
    // ------------------------------------------------------------------

    private static void giveHeld(ClientGameTestContext context, TestSingleplayerContext sp, ItemStack stack) {
        context.runOnClient(client ->
            client.player.getInventory().setSelectedItem(stack.copy()));
        sp.getServer().runOnServer(server -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            p.getInventory().setSelectedItem(stack.copy());
            p.getInventory().setChanged();
        });
        context.waitTicks(1);
    }

    private static void setSneak(ClientGameTestContext context, TestSingleplayerContext sp, boolean sneak) {
        // 不能直接 player.setShiftKeyDown：LocalPlayer.tick 的键盘输入每帧都会按
        // options.keyShift 重算潜行标志，直接设值在下一个 tick 就被抹掉（真实点击
        // 之前就失效）。程序化按下/松开潜行键 = 与真人潜行完全同机制，标志稳定，
        // 且输入包会把服务端玩家的状态同步成一致。
        context.runOnClient(client -> client.options.keyShift.setDown(sneak));
        context.waitTicks(1);
    }

    /** 客户端发起一次真实的"右键方块"（预测 + 网络包 + 服务端执行）。 */
    private static InteractionResult click(ClientGameTestContext context, TestSingleplayerContext sp,
            BlockPos pos, Direction face, boolean sneak) {
        return context.computeOnClient(client -> {
            LocalPlayer player = client.player;
            Vec3 location = Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(location, face, pos, false);
            return client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        });
    }
}
//?}
