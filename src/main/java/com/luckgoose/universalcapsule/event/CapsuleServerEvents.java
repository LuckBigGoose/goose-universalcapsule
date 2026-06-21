package com.luckgoose.universalcapsule.event;

import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import com.luckgoose.universalcapsule.data.CapsuleTemplateStorage;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 胶囊系统的服务端生命周期事件。
 *
 * 目前只做一件事：服务器启动后扫一遍 config 目录下的 tmp_xxx.nbt 文件，
 * 把上一次世界停止后留下的、超过 7 天没动过的孤儿临时模板删掉，避免长时间运行
 * 的服务器目录被废弃 tmp 文件填满。
 */
@Mod.EventBusSubscriber(modid = UniversalCapsuleMod.MOD_ID)
public final class CapsuleServerEvents {

    private CapsuleServerEvents() {
    }

    /** tmp 文件最长保留时间：7 天。超过此阈值视为孤儿。 */
    private static final long TMP_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        CapsuleTemplateStorage.sweepStaleTempFiles(TMP_MAX_AGE_MS);
    }
}