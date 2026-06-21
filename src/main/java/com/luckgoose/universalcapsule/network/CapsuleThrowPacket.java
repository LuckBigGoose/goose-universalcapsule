package com.luckgoose.universalcapsule.network;

import com.luckgoose.universalcapsule.CapsuleConstants;
import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.luckgoose.universalcapsule.logic.CapsuleScanner;
import com.luckgoose.universalcapsule.task.CapsuleTaskScheduler;
import com.luckgoose.universalcapsule.task.DelayedCaptureTask;
import com.luckgoose.universalcapsule.task.DelayedItemDropTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：确认扫描区域并执行收纳。
 * 投掷表现只由客户端视觉包处理，不再生成有碰撞的抛物线实体。
 */
public class CapsuleThrowPacket {

    private final InteractionHand hand;
    private final BlockPos origin;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int yOffset;
    private final int rotationOrdinal;

    public CapsuleThrowPacket(InteractionHand hand, BlockPos origin, int sizeX, int sizeY, int sizeZ,
                              int yOffset, Rotation rotation) {
        this.hand = hand;
        this.origin = origin;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.yOffset = yOffset;
        this.rotationOrdinal = rotation.ordinal();
    }

    public static void encode(CapsuleThrowPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.hand);
        buf.writeBlockPos(msg.origin);
        buf.writeVarInt(msg.sizeX);
        buf.writeVarInt(msg.sizeY);
        buf.writeVarInt(msg.sizeZ);
        buf.writeVarInt(msg.yOffset);
        buf.writeVarInt(msg.rotationOrdinal);
    }

    public static CapsuleThrowPacket decode(FriendlyByteBuf buf) {
        return new CapsuleThrowPacket(
                buf.readEnum(InteractionHand.class),
                buf.readBlockPos(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                Rotation.values()[Math.floorMod(buf.readVarInt(), Rotation.values().length)]);
    }

    public static void handle(CapsuleThrowPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            ItemStack stack = player.getItemInHand(msg.hand);
            if (!(stack.getItem() instanceof UniversalCapsuleItem)) return;
            CapsuleMode mode = CapsuleItemNbt.getMode(stack);
            if (mode == null) return;
            if (mode != CapsuleMode.EMPTY) return;
            ServerLevel level = player.serverLevel();
            int sx = CapsuleItemNbt.clampScanSize(msg.sizeX > 0 ? msg.sizeX : 5);
            int sy = CapsuleItemNbt.clampScanSize(msg.sizeY > 0 ? msg.sizeY : 5);
            int sz = CapsuleItemNbt.clampScanSize(msg.sizeZ > 0 ? msg.sizeZ : 5);
            BlockPos sizeXYZ = new BlockPos(sx, sy, sz);
            ItemStack original = stack.copyWithCount(1);
            BlockPos dropPos = msg.origin.offset(sizeXYZ.getX() / 2, sizeXYZ.getY(), sizeXYZ.getZ() / 2);

            double cx = msg.origin.getX() + sx / 2.0D;
            double cy = msg.origin.getY() + sy / 2.0D;
            double cz = msg.origin.getZ() + sz / 2.0D;
            if (player.distanceToSqr(cx, cy, cz) > CapsuleConstants.MAX_INTERACT_RANGE_SQ) {
                consumeAndReturnEmpty(player, level, stack, original, msg.origin, sizeXYZ, dropPos);
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.out_of_range"), true);
                return;
            }

            int count = CapsuleScanner.countResolvedCapturable(level, msg.origin, sizeXYZ, player);
            if (count <= 0) {
                consumeAndReturnEmpty(player, level, stack, original, msg.origin, sizeXYZ, dropPos);
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.capture_empty"), true);
                return;
            }

            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            CapsuleVisualEffectPacket.broadcastCapture(player, msg.origin, sizeXYZ, original);
            CapsuleTaskScheduler.submit(new DelayedCaptureTask(player, level, msg.origin, sizeXYZ, dropPos, original,
                    CapsuleVisualEffectPacket.FLIGHT_TICKS));

            level.playSound(null, player.blockPosition(),
                    net.minecraft.sounds.SoundEvents.SNOWBALL_THROW,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.35F, 1.45F);
        });
        ctx.setPacketHandled(true);
    }

    private static void consumeAndReturnEmpty(ServerPlayer player, ServerLevel level, ItemStack stack, ItemStack original,
                                              BlockPos origin, BlockPos sizeXYZ, BlockPos dropPos) {
        boolean consumed = false;
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            consumed = true;
        }
        CapsuleVisualEffectPacket.broadcastCaptureFail(player, origin, sizeXYZ, original);
        if (consumed) {
            CapsuleTaskScheduler.submit(new DelayedItemDropTask(player, level, dropPos, original,
                    CapsuleVisualEffectPacket.FLIGHT_TICKS));
        }
        level.playSound(null, player.blockPosition(),
                net.minecraft.sounds.SoundEvents.SNOWBALL_THROW,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.35F, 1.45F);
    }
}
