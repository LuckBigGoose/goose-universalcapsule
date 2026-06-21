package com.luckgoose.universalcapsule;

import com.luckgoose.universalcapsule.network.UniversalCapsuleNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 万能胶囊模组入口。
 *
 * 只负责挂接 Forge 注册表与网络通道，具体逻辑保持在 registry/network/task 等子模块中。
 */
@Mod(UniversalCapsuleMod.MOD_ID)
public class UniversalCapsuleMod {

    /** Forge mod id，必须与 mods.toml / 资源路径保持一致。 */
    public static final String MOD_ID = "goose_universalcapsule";

    public UniversalCapsuleMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        CapsuleRegistry.register(modBus);
        UniversalCapsuleNetwork.register();
    }
}
