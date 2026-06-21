package com.luckgoose.universalcapsule.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.List;

/**
 * 多方块扩展器 SPI。其他模块（包括跨 mod 兼容补丁）可以注册实现，
 * 让 CapsuleScanner / CapsulePlacer 在捕获 / 部署时识别多方块并做正确的处理。
 *
 * 注意：本接口仅依赖原版类型，对其他 mod 没有编译期依赖；
 *      具体的 embers / aetherworks 适配实现通过类名反射在运行时注册。
 */
public interface IMultiblockExpander {

    /**
     * 判定指定方块状态是否属于该 expander 管辖的多方块结构。
     */
    boolean isPart(BlockState state);

    /**
     * 判定指定方块是否是多方块结构的“中心块”。
     * 中心块在 capture/place 流程里会被当成"主块"，决定整体相对坐标。
     */
    boolean isCenter(BlockState state);

    /**
     * 给定一个中心块在世界中的位置，返回该多方块结构占用的所有世界坐标。
     * 返回值必须包含 centerWorldPos 本身。
     */
    default List<BlockPos> getOccupiedWorldPositions(LevelAccessor level, BlockPos centerWorldPos, BlockState centerState) {
        return Collections.singletonList(centerWorldPos);
    }

    /**
     * 部署阶段对“中心块部分自展开（onPlace 自动 spawn 边缘）”的结构有用：
     * 返回 true 表示部署时只需要写入中心块，边缘块由 onPlace 自己处理。
     */
    default boolean isSelfExpandingOnPlace() {
        return false;
    }

    /**
     * 部署阶段对“需要手动调用 setPlacedBy 才能展开”的结构有用：
     * 在中心块被 setBlock 之后，框架会调用本方法，由 expander 决定如何展开。
     * 默认空操作。
     */
    default void postPlaceCenter(LevelAccessor level, BlockPos centerWorldPos, BlockState centerState) {
        // 默认无需后置展开。
    }

    /**
     * 给本 expander 一个友好名字，便于调试与日志。
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
