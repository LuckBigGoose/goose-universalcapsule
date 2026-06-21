package com.luckgoose.universalcapsule.client;

import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.luckgoose.universalcapsule.logic.CapsuleAim;
import com.luckgoose.universalcapsule.logic.CapsulePlacer;
import com.luckgoose.universalcapsule.logic.CapsuleScanner;
import com.luckgoose.universalcapsule.multiblock.IMultiblockExpander;
import com.luckgoose.universalcapsule.multiblock.MultiblockRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 胶囊扫描 / 放置阶段的预览渲染：
 *
 *  - 扫描状态：地表锚点 + 三轴尺寸的外框 + 半透明蓝色面板；再画出被选中的方块的线框
     *  - 放置状态：使用原版 {@link BlockRenderDispatcher} 渲染每个方块的真实半透明模型；
     *             阻挡处再叠加红色线框标识。
 */
@Mod.EventBusSubscriber(modid = UniversalCapsuleMod.MOD_ID, value = Dist.CLIENT)
public final class CapsulePreviewRenderer {

    private static final BufferBuilder LINE_BUFFER = new BufferBuilder(2048);
    private static final BufferBuilder QUAD_BUFFER = new BufferBuilder(2048);
    /**
     * 连通组件配色。注意：扫描区域外框已固定为绿色，所以这里**避免使用绿色系**，
     * 否则连通组件会和外框融成一片无法分辨。
     */
    private static final float[][] COMPONENT_COLORS = new float[][]{
            {0.22F, 0.74F, 0.97F},   // 蓝
            {0.00F, 0.85F, 0.85F},   // 青（替换原绿色）
            {0.65F, 0.55F, 0.98F},   // 紫
            {0.98F, 0.45F, 0.09F},   // 橙
            {0.96F, 0.45F, 0.71F},   // 粉
            {0.98F, 0.75F, 0.14F}    // 黄
    };
    /**
     * 实体阻挡查询节流间隔（client tick）。每 N tick 才重新调用 level.getEntities，
     * 避免在密集刷怪场 / AFK 鱼场附近持续查询拖累 FPS。
     * 4Hz (5 tick / 250ms) 对人眼足够"实时"，玩家视角下不易察觉延迟。
     */
    private static final int ENTITY_BLOCK_QUERY_INTERVAL_TICKS = 5;

    /**
     * 当 BB 体积超过该 cell 数时，跳过实体阻挡的精细查询，仅做"是否有阻挡实体"的整体判定，
     * 避免在大模板（如 30×30×30 = 27000 cell）+ 大量实体场景下出现毫秒级单次查询。
     */
    private static final int ENTITY_BLOCK_BB_VOLUME_LIMIT = 8000;

    /* ============ 帧间缓存：避免每帧重建组件 / 重新扫描世界 / 重算放置预览 ============
     * 现场观察：放置/扫描预览每帧都遍历整片体积调用 isCapturable / isReplaceable，
     * 在 5000+ 方块的模板上每秒做 60 次 → CPU 直接顶满。
     * 这里用最简单的"基于不变量比较"的缓存：
     *  - 扫描：origin + sizeXYZ + level identity 一致 → 复用上次组件
     *  - 放置：origin + rotation + templateRef + level identity 一致 → 复用上次 PlacementPreview
     * 玩家移动到下一格、旋转、改 yOffset 等才会让 origin/rotation 变 → 自动重建。
     * 缺点：当其他玩家在缓存窗口内修改了你预览区内的方块，需要等 origin 移动才会刷新；
     *      对单人/小队场景几乎无感，性能收益巨大。
     */
    private static BlockPos scanCacheOrigin;
    private static BlockPos scanCacheSize;
    private static int scanCacheLevelId;
    private static List<PreviewComponent> scanCacheComponents;

    private static BlockPos placeCacheOrigin;
    private static Rotation placeCacheRotation;
    private static int placeCacheTemplateRef;
    private static int placeCacheLevelId;
    private static PlacementPreview placeCachedPreview;

    /* ============ 半透明模型 VBO 缓存 ============
     * 把模板的所有方块 tessellate 成一个 VertexBuffer 上传到 GPU；顶点位置在
     * **template-local 空间** (0..size)，绘制时再用 PoseStack 平移到 world origin。
     *
     * 这样 origin 变化（玩家移动光标、调整 yOffset）→ 只需更新 pose 平移，**不重建 VBO**；
     * 仅当 rotation / template / level 改变时才重做 tessellation。
     *
     * 5000 方块模板的半透明模型模式从"每帧几十毫秒"降到"近乎免费"。
     */
    private static VertexBuffer ghostVbo;
    private static Rotation ghostVboRotation;
    private static int ghostVboTemplateRef;
    private static int ghostVboLevelId;

    /* ============ 实体阻挡缓存（独立于 PlacementPreview）============
     * findObstruction 在所有 cell 通过后还会检查 BB 内是否有阻挡实体（mob / 玩家），
     * 如果不显示就会出现"绿框却放不下"的迷惑情况。
     *
     * 但实体每 tick 都可能位置变化，又不能像 PlacementPreview 那样按 (origin, rotation) 缓存。
     * 折中方案：每 {@link #ENTITY_BLOCK_QUERY_INTERVAL_TICKS} client tick 才重新调用
     * level.getEntities，结果缓存到下一次查询。4Hz 采样对眼睛足够"实时"，又把鱼场旁的
     * 持续查询开销从 60Hz 降到 4Hz。
     */
    private static List<AABB> entityBlockedAabbs = java.util.Collections.emptyList();
    private static long entityBlockedLastQueryTick = Long.MIN_VALUE;
    private static BlockPos entityBlockedOrigin;
    private static Rotation entityBlockedRotation;
    private static int entityBlockedLevelId;

    private CapsulePreviewRenderer() {
    }

    /**
     * 强制下一帧重建缓存（旋转/Y 偏移/退出模式时调用，确保即时反馈）。
     * VertexBuffer 的实际 close 延迟到下一次渲染线程触发缓存未命中时进行，
     * 这样不需要担心调用方是否在渲染线程。
     */
    public static void invalidateCache() {
        scanCacheOrigin = null;
        scanCacheSize = null;
        scanCacheComponents = null;
        placeCacheOrigin = null;
        placeCacheRotation = null;
        placeCachedPreview = null;
        ghostVboRotation = null;
        // 实体阻挡也立刻强制下一帧重查（玩家退出 placing → 再进入 placing 时不会复用陈旧数据）
        entityBlockedAabbs = java.util.Collections.emptyList();
        entityBlockedOrigin = null;
        entityBlockedLastQueryTick = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        // 注：H 键不再隐藏预览，而是在「线+面」与「半透明模型」之间切换样式。
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (CapsuleClientState.isScanning()) {
            renderScan(event, player);
        } else if (CapsuleClientState.isPlacing()) {
            renderPlacement(event, mc, player);
        }
    }

    /**
     * 玩家退出世界 / 切维度时立即释放 GPU VBO，避免内存泄漏。
     *
     * <p>本事件在 {@link LevelEvent.Unload} 时触发，仅对客户端 Level 生效（服务端 Level
     * 也会触发同事件，但 ghostVbo 是客户端字段，服务端卸载走这里也只是空操作）。
     *
     * <p>VertexBuffer.close() 必须在 render 线程，但 LevelEvent.Unload 由 client tick 派发，
     * 与 render 线程同属客户端主线程；调用安全。
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == null || !event.getLevel().isClientSide()) return;
        if (ghostVbo != null) {
            try {
                ghostVbo.close();
            } catch (Throwable ignored) {
                // VBO 双重 close 是空操作，但极端情况下原版资源管理器可能已清掉 GL 句柄；
                // 吞异常避免影响其他 mod 的卸载处理。
            }
            ghostVbo = null;
        }
        // 缓存键也清空，下次进入 placing 模式重新构建
        ghostVboRotation = null;
        ghostVboTemplateRef = 0;
        ghostVboLevelId = 0;
        // 实体阻挡缓存也清掉：避免 entityBlockedOrigin 指向已 unload 维度的旧 BlockPos
        entityBlockedAabbs = java.util.Collections.emptyList();
        entityBlockedOrigin = null;
        entityBlockedLastQueryTick = Long.MIN_VALUE;
    }

    /* ============== 扫描预览：地表外框 + 半透明面 + 被选方块线框 ============== */

    private static void renderScan(RenderLevelStageEvent event, LocalPlayer player) {
        InteractionHand hand = CapsuleClientState.getActiveHand();
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) {
            CapsuleClientState.exitScanning();
            return;
        }
        if (CapsuleItemNbt.getMode(stack) != CapsuleMode.EMPTY) {
            CapsuleClientState.exitScanning();
            return;
        }
        int sx = CapsuleClientState.getSizeX(stack);
        int sy = CapsuleClientState.getSizeY(stack);
        int sz = CapsuleClientState.getSizeZ(stack);
        BlockPos sizeXYZ = new BlockPos(sx, sy, sz);
        // 大结构使用动态射程：18+max(size)，让远处大型结构也能被瞄准
        BlockPos anchor = CapsuleAim.getSurfaceAnchor(player, Math.max(Math.max(sx, sy), sz));
        BlockPos origin = CapsuleScanner.calcOriginAnchored(anchor, sizeXYZ)
                .offset(0, CapsuleClientState.getYOffset(), 0);

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        AABB outer = new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + sx, origin.getY() + sy, origin.getZ() + sz);

        // 缓存命中或新建：扫描区域内每个连通组件分别上色
        List<PreviewComponent> components = getScanComponents(player.level(), origin, sizeXYZ);

        // 半透明面：先画连通组件（让每个组件着色），再画外框（淡蓝色，定位用）
        beginQuadState();
        QUAD_BUFFER.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (PreviewComponent component : components) {
            for (AABB cBox : component.boxes) {
                appendBoxFaces(pose, cBox.inflate(0.003D), component.r, component.g, component.b, 0.18F);
            }
        }
        // 外框用绿色雾气；组件配色已避开绿色系，确保色相不冲突
        appendBoxFaces(pose, outer, 0.25F, 0.9F, 0.4F, 0.06F);
        BufferUploader.drawWithShader(QUAD_BUFFER.end());
        endQuadState();

        // 线框：连通组件用饱和色线框，再加扫描区域外框
        beginLineState();
        LINE_BUFFER.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (PreviewComponent component : components) {
            for (AABB cBox : component.boxes) {
                appendBoxLines(pose, cBox.inflate(0.006D), component.r, component.g, component.b, 0.95F);
            }
        }
        // 外框用饱和绿色线框
        appendBoxLines(pose, outer, 0.25F, 0.9F, 0.4F, 0.95F);
        BufferUploader.drawWithShader(LINE_BUFFER.end());
        endLineState();

        pose.popPose();
    }

    /** 扫描组件缓存查询：origin/size/level 未变则复用，否则重建。 */
    private static List<PreviewComponent> getScanComponents(Level level, BlockPos origin, BlockPos sizeXYZ) {
        int levelId = System.identityHashCode(level);
        if (scanCacheComponents != null
                && origin.equals(scanCacheOrigin)
                && sizeXYZ.equals(scanCacheSize)
                && levelId == scanCacheLevelId) {
            return scanCacheComponents;
        }
        Set<BlockPos> positions = collectCapturablePositions(level, origin, sizeXYZ);
        scanCacheComponents = buildPlaceableComponents(positions);
        scanCacheOrigin = origin;
        scanCacheSize = sizeXYZ;
        scanCacheLevelId = levelId;
        return scanCacheComponents;
    }

    private static Set<BlockPos> collectCapturablePositions(Level level, BlockPos origin, BlockPos sizeXYZ) {
        // 预估容量：估算 1/4 cell 会被命中（保守估计），避免 HashSet 扩容拷贝
        int volume = sizeXYZ.getX() * sizeXYZ.getY() * sizeXYZ.getZ();
        Set<BlockPos> positions = new HashSet<>(Math.max(16, volume / 2));
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sizeXYZ.getX(); x++) {
            for (int y = 0; y < sizeXYZ.getY(); y++) {
                for (int z = 0; z < sizeXYZ.getZ(); z++) {
                    mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(mutable);
                    if (CapsuleScanner.isCapturableCheap(level, mutable, state)) {
                        positions.add(mutable.immutable());
                    }
                }
            }
        }
        return positions;
    }

    /* ============== 放置预览：轻量连通组件体积 ============== */

    private static void renderPlacement(RenderLevelStageEvent event, Minecraft mc, LocalPlayer player) {
        InteractionHand hand = CapsuleClientState.getActiveHand();
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) {
            CapsuleClientState.exitPlacing();
            return;
        }
        if (!CapsuleItemNbt.getMode(stack).hasContent()) {
            CapsuleClientState.exitPlacing();
            return;
        }
        CapsuleTemplate template = UniversalCapsuleItem.readTemplate(stack);
        if (template == null) {
            CapsuleClientState.exitPlacing();
            return;
        }
        Rotation rotation = CapsuleClientState.getPlaceRotation();
        BlockPos size = template.getSize();
        BlockPos rotSize = CapsulePlacer.rotatedSize(size, rotation);
        BlockPos origin = CapsuleAim.getPlacementOrigin(player, rotSize)
                .offset(0, CapsuleClientState.getYOffset(), 0);

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);

        Level level = player.level();
        // 放置预览缓存：在 origin/rotation/template/level 都没变时复用上一次结果，
        // 避免每帧 O(N) 遍历 template.getBlocks() + 逐方块 level.isReplaceable 查询。
        PlacementPreview preview = getPlacementPreview(level, player, template, origin, rotation);

        // ===== 主体预览：根据 ghostMode 选择「线+面」或「半透明模型」=====
        boolean ghost = CapsuleClientState.isGhostMode();
        if (ghost) {
            // 半透明模型：使用 VBO 缓存。只在 rotation/template/level 变化时重新网格化；
            // origin 变化只通过 pose 平移生效，零重建开销。
            renderGhostModels(pose, mc, template, origin, rotation, level);
        } else {
            // 线框+面板模式：连通组件着色，CPU 开销很小。
            beginQuadState();
            QUAD_BUFFER.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (PreviewComponent component : preview.components) {
                for (AABB box : component.boxes) {
                    appendBoxFaces(pose, box.inflate(0.003D), component.r, component.g, component.b, 0.13F);
                }
            }
            BufferUploader.drawWithShader(QUAD_BUFFER.end());
            endQuadState();
        }

        // ===== 实体阻挡查询：节流到 4Hz，BB 过大时退化为空 =====
        // 与方块阻挡用相同的纯红色绘制，保证视觉语义一致："红色 = 阻挡"。
        List<AABB> entityBlocked = getEntityBlockedAabbs(level, player, origin, rotSize, rotation);
        boolean anyBlocked = preview.anyBlocked || !entityBlocked.isEmpty();

        // ===== 阻挡红框（两种模式都画，保留即时反馈）=====
        beginQuadState();
        QUAD_BUFFER.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (AABB blocked : preview.blockedAabbs) {
            appendBoxFaces(pose, blocked.inflate(0.006D), 1.0F, 0.1F, 0.1F, 0.18F);
        }
        for (AABB blocked : entityBlocked) {
            appendBoxFaces(pose, blocked.inflate(0.006D), 1.0F, 0.1F, 0.1F, 0.18F);
        }
        BufferUploader.drawWithShader(QUAD_BUFFER.end());
        endQuadState();

        beginLineState();
        LINE_BUFFER.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        if (!ghost) {
            // 非 ghost：连通组件线框
            for (PreviewComponent component : preview.components) {
                for (AABB box : component.boxes) {
                    appendBoxLines(pose, box.inflate(0.006D), component.r, component.g, component.b, 0.92F);
                }
            }
        }
        for (AABB blocked : preview.blockedAabbs) {
            appendBoxLines(pose, blocked.inflate(0.01D), 1.0F, 0.15F, 0.15F, 1.0F);
        }
        for (AABB blocked : entityBlocked) {
            appendBoxLines(pose, blocked.inflate(0.01D), 1.0F, 0.15F, 0.15F, 1.0F);
        }

        // 整体外框（绿/红表示是否可放）—— anyBlocked 现在含实体阻挡，与 findObstruction 语义一致
        AABB outer = new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + rotSize.getX(),
                origin.getY() + rotSize.getY(),
                origin.getZ() + rotSize.getZ());
        if (anyBlocked) {
            appendBoxLines(pose, outer, 1.0F, 0.4F, 0.4F, 0.85F);
        } else {
            appendBoxLines(pose, outer, 0.3F, 0.95F, 0.5F, 0.85F);
        }
        BufferUploader.drawWithShader(LINE_BUFFER.end());
        endLineState();

        pose.popPose();
    }

    /**
     * 半透明方块模型预览（带 VBO 缓存）。
     *
     * 工作方式：
      *  1. 当 rotation / template / level 任一变化 → 走 {@link #rebuildGhostVbo} 把所有
      *     方块的顶点一次性网格化到模板局部 VertexBuffer 并上传到 GPU。
     *  2. 之后所有帧只需 PoseStack 平移到当前 origin，bind + drawWithShader，开销 ≈
     *     一次 GPU draw call。**origin 变化（光标移动 / yOffset 调整）零重建。**
     *
     * 渲染状态使用自定义 {@link CapsuleRenderTypes#GHOST_BLOCK}：
      *  - 主 framebuffer 输出（不会像原版 {@code RenderType.translucent()} 那样
     *    被重定向到 TRANSLUCENT_TARGET 而消失）
     *  - 半透明 alpha 混合 + 视线方向 z-offset，避免 z-fighting
     *
     * 限制：仅 tessellate {@link RenderShape#MODEL}；ENTITYBLOCK_ANIMATED（chest/bell 等）
     * 走 BER，输出 ITEM 顶点格式与 BLOCK 不兼容 → 跳过。视觉上少数方块缺失，
     * 但保住整体不会出现乱码模型。
     *
     * 调用前提：caller 已经做过 pose.translate(-cam.x, -cam.y, -cam.z)。
     */
    private static void renderGhostModels(PoseStack pose, Minecraft mc,
                                          CapsuleTemplate template, BlockPos origin, Rotation rotation,
                                          Level level) {
        int templateRef = System.identityHashCode(template);
        int levelId = System.identityHashCode(level);
        boolean cacheHit = ghostVbo != null
                && rotation == ghostVboRotation
                && templateRef == ghostVboTemplateRef
                && levelId == ghostVboLevelId;

        if (!cacheHit) {
            rebuildGhostVbo(mc, template, rotation);
            ghostVboRotation = rotation;
            ghostVboTemplateRef = templateRef;
            ghostVboLevelId = levelId;
        }

        if (ghostVbo == null) return;

        // 半透明 alpha：通过 ColorModulator 让 GHOST_BLOCK 的 TRANSLUCENT_TRANSPARENCY
        // 状态产生约 55% 不透明的 ghost 视觉
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.55F);

        RenderType ghostType = CapsuleRenderTypes.GHOST_BLOCK;
        ghostType.setupRenderState();

        // 关键：把 pose 从 template-local (0,0,0) 平移到世界 origin，
        // 让 VBO 内的 [0..size] 顶点出现在玩家瞄准的目标位置。
        pose.pushPose();
        pose.translate(origin.getX(), origin.getY(), origin.getZ());

        ghostVbo.bind();
        ghostVbo.drawWithShader(pose.last().pose(),
                RenderSystem.getProjectionMatrix(),
                RenderSystem.getShader());
        VertexBuffer.unbind();

        pose.popPose();

        ghostType.clearRenderState();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * 重建半透明模型 VBO：将模板所有方块网格化到一个 BufferBuilder，再上传到 GPU。
     * 顶点位置在 template-local 空间 (0..size)，origin 不参与，让缓存与 origin 解耦。
     * 必须在 render 线程调用（在 onRenderLevel 路径上，已经满足）。
     */
    private static void rebuildGhostVbo(Minecraft mc, CapsuleTemplate template,
                                        Rotation rotation) {
        // 关闭旧 VBO，释放 GPU 内存
        if (ghostVbo != null) {
            ghostVbo.close();
            ghostVbo = null;
        }

        // 估算容量：N 个方块平均 6 面，每面 4 顶点，BLOCK 顶点格式 32 字节
        // 给一个保守的初始容量，BufferBuilder 内部会自动扩容
        int estimatedBytes = Math.max(64 * 1024,
                Math.min(8 * 1024 * 1024, template.getBlockCount() * 768));
        BufferBuilder bb = new BufferBuilder(estimatedBytes);
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        // 让 BlockRenderDispatcher 内部所有 RenderType 都汇总到这一个缓冲区。
        MultiBufferSource source = (RenderType rt) -> bb;

        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        BlockPos templateSize = template.getSize();
        // 用独立 PoseStack 做 tessellation 的局部坐标变换；不影响外部主 pose
        PoseStack tessPose = new PoseStack();

        for (CapsuleTemplate.BlockInfo info : template.getBlocks()) {
            BlockState state = template.getPaletteState(info.paletteIndex);
            if (state == null || state.isAir()) continue;
            // 关键：只网格化 RenderShape.MODEL 的方块。
            //
            // BlockRenderDispatcher.renderSingleBlock 对 ENTITYBLOCK_ANIMATED（chest/bell 等
            // 带 BlockEntity 的方块）会内部转去走 ItemRenderer.render，输出可能是 ITEM 格式
            // 而非我们这里 begin 的 BLOCK 格式 → 顶点结构错位 → 在 ghost 中显示成乱码模型。
            // 对 INVISIBLE 也直接跳过；对其它 RenderShape 也保守跳过。代价：chest/bell 等
            // 在 ghost 模式不显示，但保住整体不乱码。
            if (state.getRenderShape() != RenderShape.MODEL) continue;
            // 旋转后的方块朝向（让楼梯 / 栅栏 / 门等定向方块在预览中也是正确朝向）。
            BlockState rotatedState = state.rotate(rotation);
            BlockPos rel = CapsulePlacer.transformRelative(info.pos, templateSize, rotation);

            // 关键：tessellate 到 template 局部坐标（不加 origin），渲染时由 pose 平移到世界 origin
            tessPose.pushPose();
            tessPose.translate(rel.getX(), rel.getY(), rel.getZ());
            try {
                dispatcher.renderSingleBlock(rotatedState, tessPose, source,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY,
                        ModelData.EMPTY,
                        RenderType.solid());
            } catch (Throwable ignored) {
                // 个别 mod 方块渲染异常时跳过该方块，避免整个预览失效。
            }
            tessPose.popPose();
        }

        BufferBuilder.RenderedBuffer renderedBuffer;
        try {
            renderedBuffer = bb.endOrDiscardIfEmpty();
        } catch (Throwable ignored) {
            // BufferBuilder 结束时若因异常提前终止，则放弃本帧 VBO，下一次缓存失效后重建。
            renderedBuffer = null;
        }
        if (renderedBuffer == null) {
            return;
        }

        ghostVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        ghostVbo.bind();
        ghostVbo.upload(renderedBuffer);   // 内部会释放 renderedBuffer。
        VertexBuffer.unbind();
    }

    /** 放置预览缓存查询：不变量比较命中则复用，否则重建。 */
    private static PlacementPreview getPlacementPreview(Level level, LocalPlayer player, CapsuleTemplate template,
                                                        BlockPos origin, Rotation rotation) {
        int levelId = System.identityHashCode(level);
        int templateRef = System.identityHashCode(template);
        if (placeCachedPreview != null
                && origin.equals(placeCacheOrigin)
                && rotation == placeCacheRotation
                && templateRef == placeCacheTemplateRef
                && levelId == placeCacheLevelId) {
            return placeCachedPreview;
        }
        placeCachedPreview = buildPlacementPreview(level, player, template, origin, rotation);
        placeCacheOrigin = origin;
        placeCacheRotation = rotation;
        placeCacheTemplateRef = templateRef;
        placeCacheLevelId = levelId;
        return placeCachedPreview;
    }

    /**
     * 实体阻挡查询（节流到 {@link #ENTITY_BLOCK_QUERY_INTERVAL_TICKS} client tick 一次）。
     *
     * <p>逻辑：
     * <ol>
     *   <li>BB 体积 > {@link #ENTITY_BLOCK_BB_VOLUME_LIMIT} → 跳过精细查询，直接返回空（外框
     *       退化到\"只显示方块阻挡\"，避免大模板 + 鱼场附近触发毫秒级查询）。</li>
      *   <li>origin / rotation / level 与上次查询不同 → 强制立刻重查（玩家移动光标 / 旋转
     *       后下一帧就要看到新的实体阻挡）。</li>
     *   <li>否则按 tick 间隔节流：距离上次查询超过 N tick 才重查。</li>
     * </ol>
     *
     * <p>查询结果按"每个被阻挡实体一个 AABB"展开，由 {@link Entity#getBoundingBox()} 提供，
     * 渲染时用纯红色绘制（与方块阻挡同色）。
     */
    private static List<AABB> getEntityBlockedAabbs(Level level, LocalPlayer player,
                                                    BlockPos origin, BlockPos rotSize, Rotation rotation) {
        long bbVolume = (long) rotSize.getX() * rotSize.getY() * rotSize.getZ();
        if (bbVolume > ENTITY_BLOCK_BB_VOLUME_LIMIT) {
            // BB 太大，跳过实体精细查询；外框颜色仍由方块 anyBlocked 决定，是回退策略而非完美方案。
            return java.util.Collections.emptyList();
        }
        int levelId = System.identityHashCode(level);
        long now = level.getGameTime();
        boolean keysChanged = entityBlockedOrigin == null
                || !origin.equals(entityBlockedOrigin)
                || rotation != entityBlockedRotation
                || levelId != entityBlockedLevelId;
        boolean throttledExpired = (now - entityBlockedLastQueryTick) >= ENTITY_BLOCK_QUERY_INTERVAL_TICKS;
        if (!keysChanged && !throttledExpired) {
            return entityBlockedAabbs;
        }
        AABB area = new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + rotSize.getX(),
                origin.getY() + rotSize.getY(),
                origin.getZ() + rotSize.getZ());
        List<AABB> result;
        try {
            // 与 CapsulePlacer.hasBlockingEntity 谓词保持一致：忽略掉落物 / 经验球 / 旁观者 / 玩家自身
            List<? extends Entity> hits = level.getEntities(player, area, e ->
                    !e.isSpectator()
                            && e != player
                            && !(e instanceof ItemEntity)
                            && !(e instanceof ExperienceOrb));
            result = new ArrayList<>(hits.size());
            for (Entity e : hits) {
                result.add(e.getBoundingBox());
            }
        } catch (Throwable ignored) {
            // ClientLevel 实体表偶尔会在 chunk unload 边缘抛 NPE/CME，吞异常并退化为空
            result = java.util.Collections.emptyList();
        }
        entityBlockedAabbs = result;
        entityBlockedLastQueryTick = now;
        entityBlockedOrigin = origin;
        entityBlockedRotation = rotation;
        entityBlockedLevelId = levelId;
        return entityBlockedAabbs;
    }

    private static PlacementPreview buildPlacementPreview(Level level, LocalPlayer player, CapsuleTemplate template,
                                                         BlockPos origin, Rotation rotation) {
        BlockPos size = template.getSize();
        // 预分配：每个 BlockInfo 都会进 positions 集合，HashSet 容量取 blocks.size() / loadFactor
        int blockCount = template.getBlocks().size();
        Set<BlockPos> positions = new HashSet<>(Math.max(16, blockCount * 2));
        // 阻挡 cell 集合：等所有 cell 收集完后做一次 BFS 合并成连通 AABB，避免逐 cell 大量小红框
        Set<BlockPos> blockedPositions = new HashSet<>();
        boolean anyBlockedCell = false;
        for (CapsuleTemplate.BlockInfo info : template.getBlocks()) {
            BlockState state = template.getPaletteState(info.paletteIndex);
            if (state == null) continue;
            BlockPos rel = CapsulePlacer.transformRelative(info.pos, size, rotation);
            BlockPos world = origin.offset(rel).immutable();
            positions.add(world);
            if (!CapsulePlacer.isReplaceable(level, world, player) || !level.isInWorldBounds(world)) {
                anyBlockedCell = true;
                blockedPositions.add(world);
            }
        }
        // 多方块自展开补充：postPlaceCenter 型 expander 在 entries 里只有中心，展开后的边缘
        // 不在 template.getBlocks() 中，所以单独走 expander.getOccupiedWorldPositions 把这些
        // 边缘点也纳入阻挡检查，避免"中心绿、边缘红墙未显示"。
        // 当前唯一注册的 DoubleHalfExpander 把两半都加进了 entries，所以本块循环对它实际是空操作；
        // 此处保留是为了未来扩展 mod 多方块（3×3 机器中心等）时自动覆盖。
        for (CapsuleTemplate.BlockInfo info : template.getBlocks()) {
            BlockState state = template.getPaletteState(info.paletteIndex);
            if (state == null) continue;
            IMultiblockExpander exp = MultiblockRegistry.findFor(state);
            if (exp == null || !exp.isCenter(state) || !exp.isSelfExpandingOnPlace()) continue;
            BlockPos rel = CapsulePlacer.transformRelative(info.pos, size, rotation);
            BlockPos centerWorld = origin.offset(rel);
            for (BlockPos occ : exp.getOccupiedWorldPositions(level, centerWorld, state)) {
                BlockPos occImm = occ.immutable();
                if (positions.add(occImm)) {
                    if (!CapsulePlacer.isReplaceable(level, occImm, player) || !level.isInWorldBounds(occImm)) {
                        anyBlockedCell = true;
                        blockedPositions.add(occImm);
                    }
                }
            }
        }
        List<PreviewComponent> components = buildPlaceableComponents(positions);
        // 阻挡 cell 也走 BFS 合并：与可放置预览相同算法，但全部用红色（不参与彩虹色轮换），
        // 视觉上"阻挡=红色 AABB"语义清晰，且把"5000 cell 全阻挡"压成几个 AABB 显著降低 GPU 上传
        List<AABB> blockedAabbs = mergeIntoComponentAabbs(blockedPositions);
        return new PlacementPreview(components, blockedAabbs, anyBlockedCell);
    }

    /**
     * 把可放置 cell 集合 BFS 合并成"组件 + 颜色"，颜色按 {@link #COMPONENT_COLORS} 轮换。
     */
    private static List<PreviewComponent> buildPlaceableComponents(Set<BlockPos> positions) {
        List<PreviewComponent> components = new ArrayList<>();
        int colorIndex = 0;
        for (List<BlockPos> componentPositions : runBfsComponents(positions)) {
            float[] color = COMPONENT_COLORS[colorIndex++ % COMPONENT_COLORS.length];
            components.add(new PreviewComponent(mergeComponentToBoxes(componentPositions), color[0], color[1], color[2]));
        }
        return components;
    }

    /**
     * 把任意 cell 集合 BFS 合并成 AABB 列表（不带颜色信息）。
     * 用于"阻挡红框" —— 颜色由调用方在渲染阶段统一决定。
     */
    private static List<AABB> mergeIntoComponentAabbs(Set<BlockPos> positions) {
        if (positions.isEmpty()) return new ArrayList<>();
        List<AABB> aabbs = new ArrayList<>();
        for (List<BlockPos> componentPositions : runBfsComponents(positions)) {
            aabbs.addAll(mergeComponentToBoxes(componentPositions));
        }
        return aabbs;
    }

    /**
     * 通用 BFS 连通组件提取：6 邻接，输入 cell 集合，输出 List of components（每个 component 是
     * 一个 BlockPos 列表）。本方法**不修改入参 positions**（克隆一份做工作集）。
     */
    private static List<List<BlockPos>> runBfsComponents(Set<BlockPos> positions) {
        List<List<BlockPos>> result = new ArrayList<>();
        if (positions.isEmpty()) return result;
        // remaining 必须是新副本（while 循环会调用 remove），预分配容量避免 HashSet 扩容
        Set<BlockPos> remaining = new HashSet<>(Math.max(16, positions.size() * 2));
        remaining.addAll(positions);
        // BFS 工作队列：用 MutableBlockPos 避免每次邻居查询都 new BlockPos
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            List<BlockPos> componentPositions = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>(Math.min(remaining.size(), 256));
            queue.add(start);
            remaining.remove(start);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                componentPositions.add(pos);
                for (Direction direction : Direction.values()) {
                    neighbor.set(pos.getX() + direction.getStepX(),
                            pos.getY() + direction.getStepY(),
                            pos.getZ() + direction.getStepZ());
                    // HashSet.remove(MutableBlockPos) 通过 equals + hashCode 检索；
                    // BlockPos.hashCode 取决于 (x,y,z)，与 mutable/immutable 无关。
                    if (remaining.remove(neighbor)) {
                        queue.add(neighbor.immutable());
                    }
                }
            }
            result.add(componentPositions);
        }
        return result;
    }

    private static List<AABB> mergeComponentToBoxes(List<BlockPos> positions) {
        List<AABB> boxes = new ArrayList<>();
        if (positions.isEmpty()) return boxes;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : positions) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        boxes.add(new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1));
        return boxes;
    }

    /**
     * 放置预览的方块层数据：可放置组件 + 阻挡 AABB（已 BFS 合并）+ 是否有方块阻挡。
     * <p>注意：实体阻挡**不在**这里 —— 它独立维护在 {@link #entityBlockedAabbs} 字段，
     * 走每 5 client tick 节流的查询路径，避免污染本结构的稳定缓存键。
     */
    private record PlacementPreview(List<PreviewComponent> components, List<AABB> blockedAabbs, boolean anyBlocked) {
    }

    private record PreviewComponent(List<AABB> boxes, float r, float g, float b) {
    }

    /* ============== 渲染状态工具 ============== */

    private static void beginLineState() {
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(2.0F);
    }

    private static void endLineState() {
        RenderSystem.lineWidth(1.0F);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }

    private static void beginQuadState() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static void endQuadState() {
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void appendBoxLines(PoseStack pose, AABB box, float r, float g, float b, float a) {
        org.joml.Matrix4f mat = pose.last().pose();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        line(mat, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(mat, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(mat, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(mat, x1, y1, z2, x1, y1, z1, r, g, b, a);
        line(mat, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(mat, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(mat, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(mat, x1, y2, z2, x1, y2, z1, r, g, b, a);
        line(mat, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(mat, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(mat, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(mat, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private static void line(org.joml.Matrix4f mat,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        LINE_BUFFER.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
        LINE_BUFFER.vertex(mat, x2, y2, z2).color(r, g, b, a).endVertex();
    }

    /** 给整个 AABB 画 6 个面（QUAD），用于扫描模式半透明体积感。 */
    private static void appendBoxFaces(PoseStack pose, AABB box, float r, float g, float b, float a) {
        org.joml.Matrix4f m = pose.last().pose();
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        // 下
        quad(m, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        // 上
        quad(m, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, r, g, b, a);
        // 北
        quad(m, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        // 南
        quad(m, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, r, g, b, a);
        // 西
        quad(m, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, r, g, b, a);
        // 东
        quad(m, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
    }

    private static void quad(org.joml.Matrix4f mat,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz,
                             float r, float g, float b, float a) {
        QUAD_BUFFER.vertex(mat, ax, ay, az).color(r, g, b, a).endVertex();
        QUAD_BUFFER.vertex(mat, bx, by, bz).color(r, g, b, a).endVertex();
        QUAD_BUFFER.vertex(mat, cx, cy, cz).color(r, g, b, a).endVertex();
        QUAD_BUFFER.vertex(mat, dx, dy, dz).color(r, g, b, a).endVertex();
    }
}
