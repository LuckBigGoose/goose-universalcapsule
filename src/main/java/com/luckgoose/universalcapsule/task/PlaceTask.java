package com.luckgoose.universalcapsule.task;

import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.logic.CapsulePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;

/**
 * 完整摆放结算任务。
 *
 * 摆放不按 tick 分段执行；阻挡校验、方块写入和多方块展开必须在同一 tick 完成，
 * 以兼容自展开结构，避免只放置中心块或只放置一半。
 */
public class PlaceTask extends CapsuleTask {

    private final ServerLevel level;
    private final CapsuleTemplate template;
    private final BlockPos origin;
    private final Rotation rotation;
    private final BlockPos dropPos;
    private final ItemStack capsuleStack;

    public PlaceTask(ServerPlayer player, ServerLevel level, CapsuleTemplate template,
                     BlockPos origin, Rotation rotation, BlockPos dropPos, ItemStack capsuleStack) {
        super(player);
        this.level = level;
        this.template = template;
        this.origin = origin;
        this.rotation = rotation;
        this.dropPos = dropPos;
        this.capsuleStack = capsuleStack;
    }

    @Override
    public boolean tick(int budget) {
        if (!isValid()) return commitFallback();
        BlockPos obstacle = CapsulePlacer.findObstruction(level, template, origin, rotation, player);
        if (obstacle != null) {
            player.displayClientMessage(
                    Component.translatable("message.goose_universalcapsule.capsule.place_blocked",
                            obstacle.getX(), obstacle.getY(), obstacle.getZ()), true);
            dropAsItem();
            return true;
        }
        int placed = CapsulePlacer.place(level, template, origin, rotation, player);
        if (placed <= 0 && template.getEntityCount() <= 0) {
            player.displayClientMessage(
                    Component.translatable("message.goose_universalcapsule.capsule.place_no_template"), true);
            dropAsItem();
            return true;
        }
        spawnReleaseParticles();
        level.playSound(null, origin,
                net.minecraft.sounds.SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(),
                net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.1F);
        player.displayClientMessage(
                Component.translatable("message.goose_universalcapsule.capsule.place_success", placed), true);
        return true;
    }

    private void spawnReleaseParticles() {
        double sx = dropPos.getX() + 0.5;
        double sy = dropPos.getY() + 0.5;
        double sz = dropPos.getZ() + 0.5;
        BlockPos size = CapsulePlacer.rotatedSize(template.getSize(), rotation);
        level.sendParticles(ParticleTypes.PORTAL, sx, sy, sz, 48,
                size.getX() * 0.08D, size.getY() * 0.08D, size.getZ() * 0.08D, 0.5D);
        level.sendParticles(ParticleTypes.END_ROD, sx, sy, sz, 36,
                size.getX() * 0.06D, size.getY() * 0.06D, size.getZ() * 0.06D, 0.05D);
    }

    private boolean commitFallback() {
        dropAsItem();
        return true;
    }

    private void dropAsItem() {
        ItemEntity drop = new ItemEntity(level,
                dropPos.getX() + 0.5, dropPos.getY() + 0.4, dropPos.getZ() + 0.5,
                capsuleStack.copy());
        drop.setDeltaMovement(0, 0.05, 0);
        level.addFreshEntity(drop);
    }

    @Override
    public String describe() {
        return "PlaceTask(origin=" + origin + ")";
    }
}
