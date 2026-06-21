package com.luckgoose.universalcapsule.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 胶囊交互专用按键。设计原则：
 *  - 全部走原版控制菜单的 KeyMapping，玩家可在"控制 → 鹅之工作坊·胶囊"分类里改键。
 *  - 默认键避开原版 1.20.1 的预设绑定（IN_GAME 上下文）。
 *  - X / Y / Z 是滚轮修饰键：按住时滚轮调整对应轴；不按时滚轮被胶囊事件全局拦截避免切快捷栏。
 */
public final class CapsuleKeyBindings {

    public static final String CATEGORY = "key.categories.goose_universalcapsule.capsule";

    public static KeyMapping AXIS_X;
    public static KeyMapping AXIS_Y;
    public static KeyMapping AXIS_Z;
    public static KeyMapping Y_OFFSET;
    public static KeyMapping ROTATE_CW;
    public static KeyMapping ROTATE_CCW;
    /** H：切换扫描/放置时的预览渲染（地表外框、半透明体积、阻挡红框等）。 */
    public static KeyMapping TOGGLE_PREVIEW;

    private CapsuleKeyBindings() {
    }

    public static void register(RegisterKeyMappingsEvent event) {
        // X 常用于“取消模式”；三个轴修饰键改用 V/B/N，以避开常见冲突。
        AXIS_X = makeKey("axis_x", GLFW.GLFW_KEY_V);
        AXIS_Y = makeKey("axis_y", GLFW.GLFW_KEY_B);
        AXIS_Z = makeKey("axis_z", GLFW.GLFW_KEY_N);
        Y_OFFSET = makeKey("y_offset", GLFW.GLFW_KEY_Y);
        ROTATE_CW = makeKey("rotate_cw", GLFW.GLFW_KEY_R);
        ROTATE_CCW = makeKey("rotate_ccw", GLFW.GLFW_KEY_Q);
        TOGGLE_PREVIEW = makeKey("toggle_preview", GLFW.GLFW_KEY_H);

        event.register(AXIS_X);
        event.register(AXIS_Y);
        event.register(AXIS_Z);
        event.register(Y_OFFSET);
        event.register(ROTATE_CW);
        event.register(ROTATE_CCW);
        event.register(TOGGLE_PREVIEW);
    }

    private static KeyMapping makeKey(String name, int defaultGlfwKey) {
        return new KeyMapping(
                "key.goose_universalcapsule.capsule." + name,
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                defaultGlfwKey,
                CATEGORY);
    }
}
