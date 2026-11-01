
// 由 tools/gen_redstone_composites.py 生成（对照 guardian 192e7132b4）——不要手改，重跑生成器。
package org.liquidphase.redstone.composites;

import java.util.function.Supplier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
public class WirePoweredRailBlock extends PoweredRailBlock implements org.liquidphase.redstone.RedstonePhaseBlock {

    public WirePoweredRailBlock(BlockBehaviour.Properties p0) {
        super(p0);
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block, net.minecraft.world.level.block.state.BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(org.liquidphase.redstone.RedstoneBlocks.POWER);
    }

    @Override
    public boolean isSignalSource(net.minecraft.world.level.block.state.BlockState state) {
        return true;
    }

    @Override
    public int getSignal(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction direction) {
        return org.liquidphase.redstone.RedstoneBlocks.getSignal(state, level, pos, direction);
    }

    @Override
    public int getDirectSignal(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction direction) {
        return org.liquidphase.redstone.RedstoneBlocks.getDirectSignal(state, level, pos, direction);
    }
}
