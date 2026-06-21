package com.luckgoose.universalcapsule.network;

import com.luckgoose.universalcapsule.client.CapsuleVisualEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 服务端广播给客户端的投掷视觉效果包。
 *
 * 只驱动动画、声音和粒子；真正的收集/摆放仍由服务端任务结算，客户端效果不参与判定。
 */
public class CapsuleVisualEffectPacket {

    /** 胶囊从玩家手中飞到目标点的客户端动画时长。 */
    public static final int FLIGHT_TICKS = 18;
    /** 整个客户端效果的最长生命周期。 */
    public static final int TOTAL_TICKS = 42;

    /** 视觉效果类型，与服务端结算结果对应。 */
    public enum EffectType {
        CAPTURE,
        CAPTURE_FAIL,
        PLACE
    }

    private final EffectType type;
    private final Vec3 start;
    private final BlockPos origin;
    private final BlockPos size;
    private final ItemStack stack;

    public CapsuleVisualEffectPacket(EffectType type, Vec3 start, BlockPos origin, BlockPos size, ItemStack stack) {
        this.type = type;
        this.start = start;
        this.origin = origin;
        this.size = size;
        this.stack = stack.copyWithCount(1);
    }

    public static void encode(CapsuleVisualEffectPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.type);
        buf.writeDouble(msg.start.x);
        buf.writeDouble(msg.start.y);
        buf.writeDouble(msg.start.z);
        buf.writeBlockPos(msg.origin);
        buf.writeBlockPos(msg.size);
        buf.writeItem(msg.stack);
    }

    public static CapsuleVisualEffectPacket decode(FriendlyByteBuf buf) {
        EffectType type = buf.readEnum(EffectType.class);
        Vec3 start = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        BlockPos origin = buf.readBlockPos();
        BlockPos size = buf.readBlockPos();
        ItemStack stack = buf.readItem();
        return new CapsuleVisualEffectPacket(type, start, origin, size, stack);
    }

    public static void handle(CapsuleVisualEffectPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        // 包类会在服务端类加载，客户端实现必须通过 DistExecutor 延迟触达。
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CapsuleVisualEffects.add(msg.type, msg.start, msg.origin, msg.size, msg.stack)));
        ctx.setPacketHandled(true);
    }

    public static void broadcastCapture(ServerPlayer player, BlockPos origin, BlockPos size, ItemStack stack) {
        broadcast(player, EffectType.CAPTURE, origin, size, stack);
    }

    public static void broadcastCaptureFail(ServerPlayer player, BlockPos origin, BlockPos size, ItemStack stack) {
        broadcast(player, EffectType.CAPTURE_FAIL, origin, size, stack);
    }

    public static void broadcastPlace(ServerPlayer player, BlockPos origin, BlockPos size, ItemStack stack) {
        broadcast(player, EffectType.PLACE, origin, size, stack);
    }

    private static void broadcast(ServerPlayer player, EffectType type, BlockPos origin, BlockPos size, ItemStack stack) {
        // 动画起点略低于视线方向，视觉上更像从玩家手中抛出。
        Vec3 start = player.getEyePosition().add(player.getLookAngle().scale(0.45D)).add(0.0D, -0.25D, 0.0D);
        UniversalCapsuleNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new CapsuleVisualEffectPacket(type, start, origin, size, stack));
    }
}
