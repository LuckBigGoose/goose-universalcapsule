package com.luckgoose.universalcapsule.network;

import com.luckgoose.universalcapsule.CapsuleConstants;
import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.luckgoose.universalcapsule.logic.CapsulePlacer;
import com.luckgoose.universalcapsule.task.CapsuleTaskScheduler;
import com.luckgoose.universalcapsule.task.DelayedPlaceTask;
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
 * 客户端 → 服务端：以 origin 为最低角放置胶囊内容（带旋转）。
 *
 * 摆放通过飞行动画后的后台结算任务执行；真正写入仍单 tick 完成，保证多方块结构完整。
 */
public class CapsulePlacePacket {

    private final InteractionHand hand;
    private final BlockPos origin;
    private final int rotationOrdinal;

    public CapsulePlacePacket(InteractionHand hand, BlockPos origin, Rotation rotation) {
        this.hand = hand;
        this.origin = origin;
        this.rotationOrdinal = rotation.ordinal();
    }

    public static void encode(CapsulePlacePacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.hand);
        buf.writeBlockPos(msg.origin);
        buf.writeVarInt(msg.rotationOrdinal);
    }

    public static CapsulePlacePacket decode(FriendlyByteBuf buf) {
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        BlockPos pos = buf.readBlockPos();
        int rot = buf.readVarInt();
        return new CapsulePlacePacket(hand, pos, Rotation.values()[Math.floorMod(rot, Rotation.values().length)]);
    }

    public static void handle(CapsulePlacePacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            ItemStack stack = player.getItemInHand(msg.hand);
            if (!(stack.getItem() instanceof UniversalCapsuleItem)) return;
            CapsuleMode mode = CapsuleItemNbt.getMode(stack);
            if (!mode.hasContent()) return;

            CapsuleTemplate template = UniversalCapsuleItem.readTemplate(stack);
            if (template == null || template.isEmpty()) {
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.place_no_template"), true);
                return;
            }

            Rotation rotation = Rotation.values()[Math.floorMod(msg.rotationOrdinal, Rotation.values().length)];
            ServerLevel level = player.serverLevel();

            // 距离校验
            BlockPos rotSize = CapsulePlacer.rotatedSize(template.getSize(), rotation);
            double cx = msg.origin.getX() + rotSize.getX() / 2.0D;
            double cy = msg.origin.getY() + rotSize.getY() / 2.0D;
            double cz = msg.origin.getZ() + rotSize.getZ() / 2.0D;
            if (player.distanceToSqr(cx, cy, cz) > CapsuleConstants.MAX_INTERACT_RANGE_SQ) {
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.out_of_range"), true);
                return;
            }

            BlockPos obstacle = CapsulePlacer.findObstruction(level, template, msg.origin, rotation, player);
            if (obstacle != null) {
                player.displayClientMessage(
                        Component.translatable("message.goose_universalcapsule.capsule.place_blocked",
                                obstacle.getX(), obstacle.getY(), obstacle.getZ()), true);
                return;
            }

            ItemStack thrownVisual = stack.copyWithCount(1);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            CapsuleVisualEffectPacket.broadcastPlace(player, msg.origin, rotSize, thrownVisual);
            CapsuleTaskScheduler.submit(new DelayedPlaceTask(player, level, template, msg.origin, rotation,
                    msg.origin, thrownVisual, CapsuleVisualEffectPacket.FLIGHT_TICKS));
            level.playSound(null, msg.origin,
                    net.minecraft.sounds.SoundEvents.SNOWBALL_THROW,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.35F, 1.45F);
        });
        ctx.setPacketHandled(true);
    }
}
