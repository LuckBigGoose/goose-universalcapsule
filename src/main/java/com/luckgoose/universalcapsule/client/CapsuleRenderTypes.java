package com.luckgoose.universalcapsule.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 自定义 {@link RenderType}，专用于胶囊放置预览的「半透明方块模型」。
 *
 * <p>关键点：
 * <ul>
 *   <li>必须用 {@link RenderStateShard#RENDERTYPE_SOLID_SHADER}：原版的
 *       {@link RenderType#translucent()} 会把输出重定向到 {@code TRANSLUCENT_TARGET}
 *       这个独立 framebuffer。该 framebuffer 在 {@code AFTER_TRANSLUCENT_BLOCKS}
 *       之前就已合成到主缓冲，我们再往里写就永远看不到 → 出现「完全透明」。</li>
 *   <li>用 {@link RenderStateShard#TRANSLUCENT_TRANSPARENCY} 实现真正的半透明 alpha 混合。</li>
 *   <li>{@link RenderStateShard#VIEW_OFFSET_Z_LAYERING} 沿视线方向把方块往相机方向轻推，
 *       避免与真实世界方块发生 z-fighting。</li>
 *   <li>{@link RenderStateShard#NO_CULL}：让背面也参与渲染，模板内部空腔时仍可看见。</li>
 *   <li>不显式声明 {@code OutputState} → 默认走主 framebuffer。</li>
 * </ul>
 *
 * <p>顶点格式严格使用 {@link DefaultVertexFormat#BLOCK}，与
 * {@link net.minecraft.client.renderer.block.ModelBlockRenderer} 输出对齐。
 *
 * <p>通过继承 {@link RenderType} 才能访问 {@code create} 与各种 protected 状态分片。
 */
public class CapsuleRenderTypes extends RenderType {

    private CapsuleRenderTypes(String name, com.mojang.blaze3d.vertex.VertexFormat fmt,
                               VertexFormat.Mode mode, int bufSize,
                               boolean affectsCrumbling, boolean sortOnUpload,
                               Runnable setup, Runnable clear) {
        super(name, fmt, mode, bufSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    /**
     * 半透明模型模式专用 RenderType：BLOCK 顶点格式 + 半透明 alpha 混合 + 主 framebuffer。
     */
    public static final RenderType GHOST_BLOCK = create(
            "goose_universalcapsule:ghost_block",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            256 * 1024,
            /* 是否参与破坏裂纹 */ false,
            /* 上传时是否排序 */ false,
            RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_SOLID_SHADER)
                    .setLightmapState(LIGHTMAP)
                    .setTextureState(BLOCK_SHEET_MIPPED)
                    .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .createCompositeState(false)
    );
}
