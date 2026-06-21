package com.luckgoose.universalcapsule.network;

import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 万能胶囊网络通道。
 *
 * 所有消息都显式标注方向，服务端只接受交互请求，客户端只接收视觉效果。
 */
public final class UniversalCapsuleNetwork {

    /** 协议版本。改动包结构或语义时需要同步递增。 */
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(UniversalCapsuleMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private UniversalCapsuleNetwork() {
    }

    public static void register() {
        // SimpleChannel 的 id 必须稳定递增；新增包只能追加，不能插入旧 id 中间。
        int id = 0;
        CHANNEL.registerMessage(id++, CapsuleThrowPacket.class,
                CapsuleThrowPacket::encode,
                CapsuleThrowPacket::decode,
                CapsuleThrowPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, CapsuleCapturePacket.class,
                CapsuleCapturePacket::encode,
                CapsuleCapturePacket::decode,
                CapsuleCapturePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, CapsulePlacePacket.class,
                CapsulePlacePacket::encode,
                CapsulePlacePacket::decode,
                CapsulePlacePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, CapsuleVisualEffectPacket.class,
                CapsuleVisualEffectPacket::encode,
                CapsuleVisualEffectPacket::decode,
                CapsuleVisualEffectPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
