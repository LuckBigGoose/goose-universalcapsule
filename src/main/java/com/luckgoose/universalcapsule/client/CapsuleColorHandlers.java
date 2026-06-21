package com.luckgoose.universalcapsule.client;

import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import com.luckgoose.universalcapsule.CapsuleRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端物品颜色注册。
 *
 * 当前胶囊样式由独立贴图模型驱动，颜色处理器只保持白色，避免对贴图进行二次染色。
 */
@Mod.EventBusSubscriber(modid = UniversalCapsuleMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CapsuleColorHandlers {

    private CapsuleColorHandlers() {
    }

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        // 保留注册点，便于后续如果改回 tint 图层时只改此处。
        event.register((stack, tintIndex) -> 0xFFFFFF, CapsuleRegistry.UNIVERSAL_CAPSULE.get());
    }
}
