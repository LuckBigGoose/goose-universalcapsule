package com.luckgoose.universalcapsule.task;

import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.logic.CapsulePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;

/**
 * 飞行动画结束后的摆放任务。
 *
 * 延迟只用于视觉表现；真正摆放仍在单个 tick 内完成，避免多方块结构被半放置。
 */
public class DelayedPlaceTask extends CapsuleTask {

    private final ServerLevel level;
    private final CapsuleTemplate template;
    private final BlockPos origin;
    private final Rotation rotation;
    private final BlockPos dropPos;
    private final ItemStack capsuleStack;
    private int ticks;

    public DelayedPlaceTask(ServerPlayer player, ServerLevel level, CapsuleTemplate template, BlockPos origin,
                            Rotation rotation, BlockPos dropPos, ItemStack capsuleStack, int delayTicks) {
        super(player);
        this.level = level;
        this.template = template;
        this.origin = origin;
        this.rotation = rotation;
        this.dropPos = dropPos;
        this.capsuleStack = capsuleStack.copyWithCount(1);
        this.ticks = Math.max(0, delayTicks);
    }

    @Override
    public boolean tick(int budget) {
        if (ticks-- > 0) return false;
        if (!isValid()) {
            dropCapsule();
            return true;
        }
        BlockPos obstacle = CapsulePlacer.findObstruction(level, template, origin, rotation, player);
        if (obstacle != null) {
            player.displayClientMessage(
                    Component.translatable("message.goose_universalcapsule.capsule.place_blocked",
                            obstacle.getX(), obstacle.getY(), obstacle.getZ()), true);
            dropCapsule();
            return true;
        }
        int placed = CapsulePlacer.place(level, template, origin, rotation, player);
        if (placed <= 0 && template.getEntityCount() <= 0) {
            player.displayClientMessage(Component.translatable("message.goose_universalcapsule.capsule.place_no_template"), true);
            dropCapsule();
            return true;
        }
        player.displayClientMessage(Component.translatable("message.goose_universalcapsule.capsule.place_success", placed), true);
        return true;
    }

    private void dropCapsule() {
        ItemEntity drop = new ItemEntity(level,
                dropPos.getX() + 0.5D, dropPos.getY() + 0.4D, dropPos.getZ() + 0.5D, capsuleStack.copy());
        drop.setDeltaMovement(0.0D, 0.05D, 0.0D);
        drop.setPickUpDelay(10);
        level.addFreshEntity(drop);
    }

    @Override
    public String describe() {
        return "DelayedPlaceTask(origin=" + origin + ")";
    }
}
