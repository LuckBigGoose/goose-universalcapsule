package com.luckgoose.universalcapsule.task;

import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.luckgoose.universalcapsule.logic.CapsuleScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 完整收集结算任务。
 *
 * 收集不按 tick 分段执行；扫描、模板写入、统一移除必须在同一 tick 完成，
 * 以兼容双高和自展开多方块结构，避免只收取一半。
 */
public class CaptureTask extends CapsuleTask {

    private final ServerLevel level;
    private final BlockPos origin;
    private final BlockPos sizeXYZ;
    private final BlockPos dropPos;
    private final ItemStack capsuleStack;

    public CaptureTask(ServerPlayer player, ServerLevel level, BlockPos origin, BlockPos sizeXYZ,
                       BlockPos dropPos, ItemStack capsuleStack) {
        super(player);
        this.level = level;
        this.origin = origin;
        this.sizeXYZ = sizeXYZ;
        this.dropPos = dropPos;
        this.capsuleStack = capsuleStack;
    }

    @Override
    public boolean tick(int budget) {
        if (!isValid()) return commitFallback();
        CapsuleTemplate template = CapsuleScanner.captureAndRemove(level, origin, sizeXYZ, player);
        if (template.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.goose_universalcapsule.capsule.capture_empty"), true);
            dropAsItem();
            return true;
        }
        spawnCaptureParticles();
        ItemStack out = capsuleStack.copy();
        UniversalCapsuleItem.writeCapturedTemplate(out, template, player);
        ItemEntity entity = new ItemEntity(level,
                dropPos.getX() + 0.5, dropPos.getY() + 0.4, dropPos.getZ() + 0.5, out);
        entity.setDeltaMovement(0, 0.05, 0);
        level.addFreshEntity(entity);
        level.playSound(null, dropPos,
                net.minecraft.sounds.SoundEvents.ENDER_EYE_DEATH,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 1.5F);
        player.displayClientMessage(
                Component.translatable("message.goose_universalcapsule.capsule.capture_success",
                        template.getBlockCount()), true);
        return true;
    }

    private void spawnCaptureParticles() {
        double x = dropPos.getX() + 0.5;
        double y = dropPos.getY() + 0.5;
        double z = dropPos.getZ() + 0.5;
        level.sendParticles(ParticleTypes.PORTAL, x, y, z, 48,
                sizeXYZ.getX() * 0.08D, sizeXYZ.getY() * 0.08D, sizeXYZ.getZ() * 0.08D, 0.7D);
        level.sendParticles(ParticleTypes.ENCHANT, x, y, z, 32,
                sizeXYZ.getX() * 0.06D, sizeXYZ.getY() * 0.06D, sizeXYZ.getZ() * 0.06D, 0.35D);
    }

    private boolean commitFallback() {
        // 玩家下线：把胶囊原样掉在落点
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
        return "CaptureTask(origin=" + origin + ", size=" + sizeXYZ + ")";
    }
}
