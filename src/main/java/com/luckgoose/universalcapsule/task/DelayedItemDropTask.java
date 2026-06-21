package com.luckgoose.universalcapsule.task;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 延迟返还物品任务。
 *
 * 用于捕获失败或超距后的飞行动画结算，只返还胶囊，不再次执行收集逻辑。
 */
public class DelayedItemDropTask extends CapsuleTask {

    private final ServerLevel level;
    private final BlockPos dropPos;
    private final ItemStack stack;
    private int ticks;

    public DelayedItemDropTask(ServerPlayer player, ServerLevel level, BlockPos dropPos,
                               ItemStack stack, int delayTicks) {
        super(player);
        this.level = level;
        this.dropPos = dropPos;
        this.stack = stack.copyWithCount(1);
        this.ticks = Math.max(0, delayTicks);
    }

    @Override
    public boolean tick(int budget) {
        if (ticks-- > 0) return false;
        ItemEntity entity = new ItemEntity(level,
                dropPos.getX() + 0.5D, dropPos.getY() + 0.7D, dropPos.getZ() + 0.5D, stack.copy());
        entity.setDeltaMovement(0.0D, 0.08D, 0.0D);
        entity.setPickUpDelay(10);
        level.addFreshEntity(entity);
        level.playSound(null, dropPos,
                net.minecraft.sounds.SoundEvents.CHICKEN_EGG,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.35F, 1.35F);
        return true;
    }

    @Override
    public String describe() {
        return "DelayedItemDropTask(pos=" + dropPos + ")";
    }
}
