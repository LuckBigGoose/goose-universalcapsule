package com.luckgoose.universalcapsule.command;

import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 服务端指令注册桥接。
 *
 * 业务实现放在 {@link UniversalCapsuleCommands}，本类只负责接入 Forge 注册事件。
 */
@Mod.EventBusSubscriber(modid = UniversalCapsuleMod.MOD_ID)
public final class UniversalCapsuleCommandRegistry {

    private UniversalCapsuleCommandRegistry() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        UniversalCapsuleCommands.register(event.getDispatcher());
    }
}
