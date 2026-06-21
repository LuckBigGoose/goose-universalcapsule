package com.luckgoose.universalcapsule.multiblock.expander;

import com.luckgoose.universalcapsule.multiblock.IMultiblockExpander;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Arrays;
import java.util.List;

/**
 * 通用双高方块扩展器：支持任何使用原版 {@code DOUBLE_BLOCK_HALF} 属性的方块
 * （门、向日葵、玫瑰丛、高草、大型蕨、以及大量 mod 的 1×2 机器方块如 melter）。
 *
 * 注意：仅认原版 DoubleBlockHalf 属性；带其他 enum 的 mod 双高需要各自的扩展器。
 */
public class DoubleHalfExpander implements IMultiblockExpander {

    @Override
    public boolean isPart(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
    }

    @Override
    public boolean isCenter(BlockState state) {
        // 把 LOWER 视为中心，便于扩展器决定向上扩 1 格。
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
    }

    @Override
    public List<BlockPos> getOccupiedWorldPositions(LevelAccessor level, BlockPos pos, BlockState state) {
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return List.of(pos);
        }
        DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
        if (half == DoubleBlockHalf.LOWER) {
            return Arrays.asList(pos, pos.above());
        } else {
            return Arrays.asList(pos.below(), pos);
        }
    }

    @Override
    public String name() {
        return "vanilla_double_half";
    }
}
