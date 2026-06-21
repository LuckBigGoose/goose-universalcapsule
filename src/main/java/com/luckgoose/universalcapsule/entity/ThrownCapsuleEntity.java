package com.luckgoose.universalcapsule.entity;

import com.luckgoose.universalcapsule.CapsuleConstants;
import com.luckgoose.universalcapsule.CapsuleRegistry;
import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.luckgoose.universalcapsule.logic.CapsulePlacer;
import com.luckgoose.universalcapsule.logic.CapsuleScanner;
import com.luckgoose.universalcapsule.task.CaptureTask;
import com.luckgoose.universalcapsule.task.CapsuleTaskScheduler;
import com.luckgoose.universalcapsule.task.PlaceTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 可投掷的胶囊实体。落地后由 {@link CaptureTask} / {@link PlaceTask} 完成结算。
 *
 * 收集和摆放保持单 tick 完整提交，避免多方块结构跨 tick 出现半收取或半放置。
 */
public class ThrownCapsuleEntity extends ThrowableItemProjectile {

    private int sizeX = CapsuleConstants.DEFAULT_SCAN_SIZE;
    private int sizeY = CapsuleConstants.DEFAULT_SCAN_SIZE;
    private int sizeZ = CapsuleConstants.DEFAULT_SCAN_SIZE;
    private int yOffset = 0;
    private int rotationOrdinal = 0;

    public ThrownCapsuleEntity(EntityType<? extends ThrownCapsuleEntity> type, Level level) {
        super(type, level);
    }

    public ThrownCapsuleEntity(Level level, LivingEntity shooter, ItemStack stack,
                               int sizeX, int sizeY, int sizeZ, int yOffset, Rotation rotation) {
        super(CapsuleRegistry.THROWN_CAPSULE.get(), shooter, level);
        this.setItem(stack.copyWithCount(1));
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.yOffset = yOffset;
        this.rotationOrdinal = rotation.ordinal();
    }

    @Override
    protected Item getDefaultItem() {
        return CapsuleRegistry.UNIVERSAL_CAPSULE.get();
    }

    @Override
    protected float getGravity() {
        return 0.03F;
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("SizeX", sizeX);
        tag.putInt("SizeY", sizeY);
        tag.putInt("SizeZ", sizeZ);
        tag.putInt("YOffset", yOffset);
        tag.putInt("RotOrd", rotationOrdinal);
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        sizeX = tag.getInt("SizeX");
        sizeY = tag.getInt("SizeY");
        sizeZ = tag.getInt("SizeZ");
        yOffset = tag.getInt("YOffset");
        rotationOrdinal = tag.getInt("RotOrd");
    }

    @Override
    protected void onHit(HitResult result) {
        if (level().isClientSide) {
            super.onHit(result);
            return;
        }
        if (result.getType() == HitResult.Type.BLOCK) {
            handleLanding((BlockHitResult) result);
            return;
        }
        super.onHit(result);
    }

    private void handleLanding(BlockHitResult hit) {
        ItemStack capsule = getItem();
        if (!(capsule.getItem() instanceof UniversalCapsuleItem)) {
            dropAsItem();
            discard();
            return;
        }
        ServerLevel server = (ServerLevel) level();
        CapsuleMode mode = CapsuleItemNbt.getMode(capsule);
        BlockPos landing = hit.getBlockPos().relative(hit.getDirection());

        LivingEntity shooter = (LivingEntity) getOwner();
        ServerPlayer player = shooter instanceof ServerPlayer sp ? sp : null;

        // 先 discard 自己，避免被算作 placement 区域内的"阻挡实体"
        discard();

        if (player == null) {
            dropAsItem();
            return;
        }

        if (mode == CapsuleMode.EMPTY) {
            BlockPos sizeXYZ = new BlockPos(
                    Math.max(1, sizeX), Math.max(1, sizeY), Math.max(1, sizeZ));
            BlockPos origin = CapsuleScanner.calcOriginAnchored(landing, sizeXYZ);
            origin = origin.offset(0, yOffset, 0);
            int count = CapsuleScanner.countResolvedCapturable(server, origin, sizeXYZ, player);
            if (count <= 0) {
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.capture_empty"), true);
                dropAsItem();
                return;
            }
            CapsuleTaskScheduler.submit(new CaptureTask(player, server, origin, sizeXYZ, landing, capsule));
            server.playSound(null, landing,
                    net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.6F);
        } else if (mode.hasContent()) {
            CapsuleTemplate template = UniversalCapsuleItem.readTemplate(capsule);
            if (template == null || template.isEmpty()) {
                dropAsItem();
                return;
            }
            Rotation rotation = Rotation.values()[Math.floorMod(rotationOrdinal, Rotation.values().length)];
            BlockPos rotSize = CapsulePlacer.rotatedSize(template.getSize(), rotation);
            BlockPos origin = landing.offset(-rotSize.getX() / 2, yOffset, -rotSize.getZ() / 2);
            // 距离校验
            if (origin.distSqr(player.blockPosition()) > CapsuleConstants.MAX_INTERACT_RANGE_SQ * 4) {
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.out_of_range"), true);
                dropAsItem();
                return;
            }
            CapsuleTaskScheduler.submit(new PlaceTask(player, server, template, origin, rotation, landing, capsule));
            server.playSound(null, landing,
                    net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 0.9F);
        } else {
            dropAsItem();
        }
    }

    private void dropAsItem() {
        if (!level().isClientSide) {
            ItemStack stack = getItem();
            ItemEntity drop = new ItemEntity(level(), getX(), getY(), getZ(), stack.copy());
            drop.setDeltaMovement(Vec3.ZERO);
            level().addFreshEntity(drop);
        }
    }
}
