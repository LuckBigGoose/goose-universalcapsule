package com.luckgoose.universalcapsule.client;

import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.ArrayList;
import java.util.List;

/**
 * 胶囊交互期间的右下角 HUD：
 *  - 当前模式
 *  - 三轴尺寸 / Y 偏移 / 旋转 / 模板信息
 *  - 热键提示（动态读取每个 KeyMapping 当前绑定的键名）
 */
public final class CapsuleHudOverlay {

    private static final int BG_COLOR = 0x80000000;
    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 4;
    private static final int OFFSET_X = 4;
    private static final int OFFSET_Y = 4;

    /* ============ HUD 帧间缓存 ============
     * 现状：HUD 每帧调 buildLines() → 新建 ~12 个 MutableComponent + 多次 append + font.width 测量。
     *      每帧大约几十次小对象分配，对于持续 1 分钟的扫描/放置交互累计可观。
     *
     * 优化：用 (CapsuleClientState.hudVersion, itemTagHash) 作为缓存键。
     *      - hudVersion 由 enter / exit / adjust* / rotate 等用户交互入口 ++；
     *      - itemTagHash 由当前手持 ItemStack 的 NBT hashCode 算出，覆盖 NBT 外部修改
     *        （例如其他玩家 /goosecapsule rename 通过同步包改了 displayName）。
     *      命中时复用 lines、boxW、boxH，跳过 buildLines + font.width。
     */
    private static int cachedHudVersion = Integer.MIN_VALUE;
    private static int cachedItemHash = 0;
    private static List<Component> cachedLines = null;
    private static int cachedBoxW = 0;
    private static int cachedBoxH = 0;

    public static final IGuiOverlay OVERLAY = (gui, gfx, partialTick, screenW, screenH) -> {
        // HUD 在扫描/放置模式下永远显示；预览体积的开关由 H 键独立控制。
        if (!CapsuleClientState.isInActiveMode()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        ItemStack stack = player.getItemInHand(CapsuleClientState.getActiveHand());
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) return;

        int currentVersion = CapsuleClientState.getHudVersion();
        // CompoundTag.hashCode 在原版实现中基于其内部 Map，稳定且廉价。
        // null tag 时取 0；这样 stack 没 NBT 也能正确缓存。
        int currentHash = stack.getTag() == null ? 0 : stack.getTag().hashCode();

        if (cachedLines == null
                || cachedHudVersion != currentVersion
                || cachedItemHash != currentHash) {
            List<Component> lines = buildLines(stack);
            if (lines.isEmpty()) {
                cachedLines = null;
                return;
            }
            Font font = mc.font;
            int maxWidth = 0;
            for (Component c : lines) {
                int w = font.width(c);
                if (w > maxWidth) maxWidth = w;
            }
            cachedLines = lines;
            cachedBoxW = maxWidth + PADDING * 2;
            cachedBoxH = lines.size() * LINE_HEIGHT + PADDING * 2;
            cachedHudVersion = currentVersion;
            cachedItemHash = currentHash;
        }

        List<Component> lines = cachedLines;
        if (lines == null || lines.isEmpty()) return;

        Font font = mc.font;
        int boxW = cachedBoxW;
        int boxH = cachedBoxH;
        int x = screenW - boxW - OFFSET_X;
        int y = screenH - boxH - OFFSET_Y;
        gfx.fill(x, y, x + boxW, y + boxH, BG_COLOR);
        int textY = y + PADDING;
        for (Component c : lines) {
            gfx.drawString(font, c, x + PADDING, textY, 0xFFFFFFFF, false);
            textY += LINE_HEIGHT;
        }
    };

    /*
     * 颜色规范（重新设计）：
     *  - 标题（L1）         AQUA/GREEN + BOLD
     *  - 字段标签（L2）      GOLD
     *  - 分隔符 ": "（L2）   DARK_GRAY
     *  - 字段值（L3）        WHITE
     *  - 旋转值（L3 特殊）   0°=GRAY  +90°=GREEN  180°=GOLD  -90°=AQUA
     *  - 操作描述（L4）      GRAY（替代旧 DARK_GRAY，提一档对比）
     *  - 按键标签（L4）      YELLOW
     *  - 主操作高亮（L5）    GOLD
     */

    private static List<Component> buildLines(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        int yOff = CapsuleClientState.getYOffset();
        if (CapsuleClientState.isScanning()) {
            int sx = CapsuleClientState.getSizeX(stack);
            int sy = CapsuleClientState.getSizeY(stack);
            int sz = CapsuleClientState.getSizeZ(stack);
            lines.add(Component.translatable("gui.goose_universalcapsule.capsule.hud.title.scan")
                    .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            lines.add(label("gui.goose_universalcapsule.capsule.hud.size_xyz",
                    sx + " × " + sy + " × " + sz));
            lines.add(scrollHint(CapsuleKeyBindings.AXIS_X, "gui.goose_universalcapsule.capsule.hud.axis_x_hint"));
            lines.add(scrollHint(CapsuleKeyBindings.AXIS_Y, "gui.goose_universalcapsule.capsule.hud.axis_y_hint"));
            lines.add(scrollHint(CapsuleKeyBindings.AXIS_Z, "gui.goose_universalcapsule.capsule.hud.axis_z_hint"));
            lines.add(Component.literal(""));
            lines.add(label("gui.goose_universalcapsule.capsule.hud.y_offset", formatSigned(yOff)));
            lines.add(scrollHint(CapsuleKeyBindings.Y_OFFSET, "gui.goose_universalcapsule.capsule.hud.axis_y_offset_hint"));
            lines.add(Component.literal(""));
            lines.add(togglePreviewHint());
            lines.add(cancelHint("gui.goose_universalcapsule.capsule.hud.key.cancel.scan"));
            lines.add(Component.translatable("gui.goose_universalcapsule.capsule.hud.right_click_capture")
                    .withStyle(ChatFormatting.GOLD));
        } else if (CapsuleClientState.isPlacing()) {
            lines.add(Component.translatable("gui.goose_universalcapsule.capsule.hud.title.place")
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            if (CapsuleItemNbt.getMode(stack) == CapsuleMode.READY) {
                String name = CapsuleItemNbt.getDisplayName(stack);
                if (!name.isEmpty()) {
                    lines.add(label("gui.goose_universalcapsule.capsule.hud.name", name));
                }
            }
            // 性能：HUD 每帧绘制，所以直接用 NBT 缓存的尺寸/方块数，
            // 避免每帧反序列化 CapsuleTemplate（即便有内存缓存也至少要一次 NBT lookup）。
            BlockPos sz = CapsuleItemNbt.getSize(stack);
            int blockCount = CapsuleItemNbt.getBlockCount(stack);
            if (sz.getX() > 0 || sz.getY() > 0 || sz.getZ() > 0) {
                lines.add(label("gui.goose_universalcapsule.capsule.hud.template_size",
                        sz.getX() + "×" + sz.getY() + "×" + sz.getZ()));
                lines.add(label("gui.goose_universalcapsule.capsule.hud.blocks", String.valueOf(blockCount)));
            }
            lines.add(Component.literal(""));
            lines.add(labelComponent("gui.goose_universalcapsule.capsule.hud.rotation", rotationComponent()));
            lines.add(keyHint("gui.goose_universalcapsule.capsule.hud.key.rotate",
                    CapsuleKeyBindings.ROTATE_CW, CapsuleKeyBindings.ROTATE_CCW));
            lines.add(Component.literal(""));
            lines.add(label("gui.goose_universalcapsule.capsule.hud.y_offset", formatSigned(yOff)));
            lines.add(scrollHint(CapsuleKeyBindings.Y_OFFSET, "gui.goose_universalcapsule.capsule.hud.axis_y_offset_hint"));
            lines.add(Component.literal(""));
            lines.add(togglePreviewHint());
            lines.add(cancelHint("gui.goose_universalcapsule.capsule.hud.key.cancel.place"));
            lines.add(Component.translatable("gui.goose_universalcapsule.capsule.hud.right_click_place")
                    .withStyle(ChatFormatting.GOLD));
        }
        return lines;
    }

    /** 字段标签 + 文本值。key=GOLD, ": "=DARK_GRAY, value=WHITE。 */
    private static Component label(String key, String value) {
        return Component.translatable(key).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    /** 字段标签 + Component 值（用于带自定义颜色的旋转值）。 */
    private static Component labelComponent(String key, Component value) {
        return Component.translatable(key).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
                .append(value);
    }

    /**
     * 旋转角度的彩色组件。
     * 顺时针为 +，逆时针为 -，180° 不带符号；颜色按方向区分以提高一眼识别度。
     */
    private static Component rotationComponent() {
        return switch (CapsuleClientState.getPlaceRotation()) {
            case NONE -> Component.translatable("gui.goose_universalcapsule.capsule.hud.rotation.none")
                    .withStyle(ChatFormatting.GRAY);
            case CLOCKWISE_90 -> Component.translatable("gui.goose_universalcapsule.capsule.hud.rotation.cw90")
                    .withStyle(ChatFormatting.GREEN);
            case CLOCKWISE_180 -> Component.translatable("gui.goose_universalcapsule.capsule.hud.rotation.cw180")
                    .withStyle(ChatFormatting.GOLD);
            case COUNTERCLOCKWISE_90 -> Component.translatable("gui.goose_universalcapsule.capsule.hud.rotation.ccw90")
                    .withStyle(ChatFormatting.AQUA);
        };
    }

    private static Component keyHint(String key, KeyMapping... mappings) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mappings.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(translatedKeyName(mappings[i]));
        }
        return Component.translatable(key).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[" + sb + "]").withStyle(ChatFormatting.YELLOW));
    }

    /** 取消提示：动态根据扫描/放置传入不同 i18n key（"退出扫描" / "退出放置"）。 */
    private static Component cancelHint(String i18nKey) {
        return Component.translatable(i18nKey).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[Esc]").withStyle(ChatFormatting.YELLOW));
    }

    /** 切换预览提示，扫描/放置模式都展示，方便用户随时关闭世界端体积渲染。 */
    private static Component togglePreviewHint() {
        return Component.translatable("gui.goose_universalcapsule.capsule.hud.key.toggle_preview")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[" + translatedKeyName(CapsuleKeyBindings.TOGGLE_PREVIEW) + "]")
                        .withStyle(ChatFormatting.YELLOW));
    }

    /** 显示 "[键] + 滚轮" 形式的提示。 */
    private static Component scrollHint(KeyMapping modifier, String descKey) {
        return Component.translatable(descKey).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" [").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(translatedKeyName(modifier))
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" + ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("gui.goose_universalcapsule.capsule.hud.scroll")
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("]").withStyle(ChatFormatting.GRAY));
    }

    private static String translatedKeyName(KeyMapping mapping) {
        if (mapping == null) return "?";
        InputConstants.Key key = mapping.getKey();
        if (key == InputConstants.UNKNOWN) return "?";
        return mapping.getTranslatedKeyMessage().getString();
    }

    private static String formatSigned(int v) {
        if (v > 0) return "+" + v;
        return String.valueOf(v);
    }

    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("capsule_hud", OVERLAY);
    }

    private CapsuleHudOverlay() {
    }
}
