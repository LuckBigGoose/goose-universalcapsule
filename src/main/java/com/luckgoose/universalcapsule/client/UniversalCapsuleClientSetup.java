package com.luckgoose.universalcapsule.client;

import com.luckgoose.universalcapsule.CapsuleRegistry;
import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端注册入口。
 *
 * 负责物品模型谓词、按键、HUD 覆盖层与投掷实体渲染器的注册。
 */
@Mod.EventBusSubscriber(modid = UniversalCapsuleMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class UniversalCapsuleClientSetup {

    private UniversalCapsuleClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 空胶囊固定样式 0；非空胶囊按 Number 生成 0.01~0.30，匹配 item model override。
        event.enqueueWork(() -> ItemProperties.register(CapsuleRegistry.UNIVERSAL_CAPSULE.get(),
                new ResourceLocation(UniversalCapsuleMod.MOD_ID, "capsule_style"),
                (stack, level, entity, seed) -> CapsuleItemNbt.getMode(stack) == CapsuleMode.EMPTY ? 0.0F : stylePredicate(CapsuleItemNbt.getNumber(stack))));
    }

    /** 将样式编号 1..30 映射为模型谓词值 0.01..0.30。 */
    private static float stylePredicate(String number) {
        int style = 1;
        try {
            style = Integer.parseInt(number);
        } catch (NumberFormatException ignored) {
        }
        style = Math.max(1, Math.min(30, style));
        return (float) (style / 100.0D);
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        // 按键必须在 MOD 总线注册，否则控制菜单不会出现本模组分类。
        CapsuleKeyBindings.register(event);
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        CapsuleHudOverlay.register(event);
    }

    @SubscribeEvent
    public static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CapsuleRegistry.THROWN_CAPSULE.get(), ThrownItemRenderer::new);
    }
}
