package com.luckgoose.universalcapsule.network;

import com.luckgoose.universalcapsule.CapsuleConfig;
import com.luckgoose.universalcapsule.CapsuleConstants;
import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.luckgoose.universalcapsule.logic.CapsuleScanner;
import com.luckgoose.universalcapsule.task.CaptureTask;
import com.luckgoose.universalcapsule.task.CapsuleTaskScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：以 origin 为最低角，对 size×size×size 立方体执行收取。
 *
 * 小尺寸（size³ <= SYNC_THRESHOLD_BLOCKS）直接写回手中物品；
 * 大尺寸提交后台结算任务，但真正收集仍单 tick 完成，保证多方块结构完整。
 */
public class CapsuleCapturePacket {

    private final InteractionHand hand;
    private final BlockPos origin;
    private final int size;

    public CapsuleCapturePacket(InteractionHand hand, BlockPos origin, int size) {
        this.hand = hand;
        this.origin = origin;
        this.size = size;
    }

    public static void encode(CapsuleCapturePacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.hand);
        buf.writeBlockPos(msg.origin);
        buf.writeVarInt(msg.size);
    }

    public static CapsuleCapturePacket decode(FriendlyByteBuf buf) {
        return new CapsuleCapturePacket(buf.readEnum(InteractionHand.class), buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(CapsuleCapturePacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            ItemStack stack = player.getItemInHand(msg.hand);
            if (!(stack.getItem() instanceof UniversalCapsuleItem)) return;
            if (CapsuleItemNbt.getMode(stack) != CapsuleMode.EMPTY) return;

            int size = CapsuleItemNbt.clampScanSize(msg.size);
            ServerLevel level = player.serverLevel();

            // 距离校验
            double cx = msg.origin.getX() + size / 2.0D;
            double cy = msg.origin.getY() + size / 2.0D;
            double cz = msg.origin.getZ() + size / 2.0D;
            if (player.distanceToSqr(cx, cy, cz) > CapsuleConstants.MAX_INTERACT_RANGE_SQ) {
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.out_of_range"), true);
                return;
            }

            int count = CapsuleScanner.countCapturable(level, msg.origin, size);
            if (count <= 0) {
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.capture_empty"), true);
                return;
            }

            int volume = size * size * size;
            if (volume <= CapsuleConfig.SYNC_THRESHOLD_BLOCKS) {
                // 同步执行
                CapsuleTemplate template = CapsuleScanner.captureAndRemove(level, msg.origin, size, player);
                if (template.isEmpty()) {
                    player.displayClientMessage(
                            Component.translatable("message.goose_universalcapsule.capsule.capture_empty"), true);
                    return;
                }
                UniversalCapsuleItem.writeCapturedTemplate(stack, template, player);
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.capture_success",
                                template.getBlockCount()), true);
                level.playSound(null, player.blockPosition(),
                        net.minecraft.sounds.SoundEvents.ENDER_EYE_LAUNCH,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.4F);
            } else {
                // 后台结算任务（捕获 → UNNAMED 胶囊掉在落点）；收集本身不拆 tick。
                BlockPos sizeXYZ = new BlockPos(size, size, size);
                ItemStack original = stack.copyWithCount(1);
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                CapsuleTaskScheduler.submit(new CaptureTask(player, level, msg.origin, sizeXYZ,
                        msg.origin, original));
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.capture_started", volume), true);
            }
        });
        ctx.setPacketHandled(true);
    }
}
