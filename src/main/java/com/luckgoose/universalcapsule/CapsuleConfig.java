package com.luckgoose.universalcapsule;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * 胶囊系统的常量配置。集中维护：
 *  - 黑名单 tag 路径
 *  - 任务调度器预留预算
 *  - 同步/异步阈值
 */
public final class CapsuleConfig {

    /** 用户可通过 datapack 扩展该 tag 来禁止某些方块被胶囊捕获。 */
    public static final TagKey<Block> CAPSULE_BLACKLIST =
            TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    new ResourceLocation(UniversalCapsuleMod.MOD_ID, "capsule_blacklist"));

    /** 预留任务预算；收集和摆放为保证多方块完整性，不按 tick 拆分。 */
    public static final int TASK_BUDGET_PER_TICK = 1024;

    /** 当总方块数（size³）小于此阈值时直接同步执行，避免小胶囊也走异步带来视觉延迟。 */
    public static final int SYNC_THRESHOLD_BLOCKS = 1024;

    /** 单次捕获涉及的 chunk 上限（防止恶意构造扫描跨太多 chunk）。 */
    public static final int MAX_CHUNKS_PER_OPERATION = 256;

    private CapsuleConfig() {
    }
}
