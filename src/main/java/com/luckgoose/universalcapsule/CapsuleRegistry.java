package com.luckgoose.universalcapsule;

import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import com.luckgoose.universalcapsule.entity.ThrownCapsuleEntity;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 胶囊系统所有内容物的集中注册表，独立于现有 init/* 系列，避免与现有 mod 内容产生耦合。
 */
public final class CapsuleRegistry {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, UniversalCapsuleMod.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, UniversalCapsuleMod.MOD_ID);

    public static final RegistryObject<Item> UNIVERSAL_CAPSULE = ITEMS.register("universal_capsule",
            () -> new UniversalCapsuleItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<EntityType<ThrownCapsuleEntity>> THROWN_CAPSULE =
            ENTITIES.register("thrown_capsule", () ->
                    EntityType.Builder.<ThrownCapsuleEntity>of(ThrownCapsuleEntity::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(4)
                            .updateInterval(10)
                            .build("thrown_capsule"));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        modBus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                e.enqueueWork(com.luckgoose.universalcapsule.multiblock.MultiblockRegistry::registerDefaults));
    }

    private CapsuleRegistry() {
    }
}
