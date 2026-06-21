package com.luckgoose.universalcapsule.task;

import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.luckgoose.universalcapsule.logic.CapsuleScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 飞行动画结束后的收集任务。
 *
 * 延迟只用于视觉表现；真正收集仍在单个 tick 内完成，避免多方块结构被半收取。
 */
public class DelayedCaptureTask extends CapsuleTask {

    private final ServerLevel level;
    private final BlockPos origin;
    private final BlockPos size;
    private final BlockPos dropPos;
    private final ItemStack capsuleStack;
    private int ticks;

    public DelayedCaptureTask(ServerPlayer player, ServerLevel level, BlockPos origin, BlockPos size,
                              BlockPos dropPos, ItemStack capsuleStack, int delayTicks) {
        super(player);
        this.level = level;
        this.origin = origin;
        this.size = size;
        this.dropPos = dropPos;
        this.capsuleStack = capsuleStack.copyWithCount(1);
        this.ticks = Math.max(0, delayTicks);
    }

    @Override
    public boolean tick(int budget) {
        if (ticks-- > 0) return false;
        if (!isValid()) {
            drop(capsuleStack);
            return true;
        }
        int count = CapsuleScanner.countResolvedCapturable(level, origin, size, player);
        if (count <= 0) {
            player.displayClientMessage(Component.translatable("message.goose_universalcapsule.capsule.capture_empty"), true);
            drop(capsuleStack);
            return true;
        }
        CapsuleTemplate template = CapsuleScanner.captureAndRemove(level, origin, size, player);
        if (template.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.goose_universalcapsule.capsule.capture_empty"), true);
            drop(capsuleStack);
            return true;
        }
        ItemStack captured = capsuleStack.copy();
        UniversalCapsuleItem.writeCapturedTemplate(captured, template, player);
        drop(captured);
        player.displayClientMessage(
                Component.translatable("message.goose_universalcapsule.capsule.capture_success", template.getBlockCount()), true);
        return true;
    }

    private void drop(ItemStack stack) {
        ItemEntity entity = new ItemEntity(level,
                dropPos.getX() + 0.5D, dropPos.getY() + 0.7D, dropPos.getZ() + 0.5D, stack.copy());
        entity.setDeltaMovement(0.0D, 0.08D, 0.0D);
        entity.setPickUpDelay(10);
        level.addFreshEntity(entity);
        level.playSound(null, dropPos,
                net.minecraft.sounds.SoundEvents.CHICKEN_EGG,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.35F, 1.35F);
    }

    @Override
    public String describe() {
        return "DelayedCaptureTask(origin=" + origin + ")";
    }
}
