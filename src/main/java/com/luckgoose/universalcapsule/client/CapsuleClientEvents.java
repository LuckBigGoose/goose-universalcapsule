package com.luckgoose.universalcapsule.client;

import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.luckgoose.universalcapsule.logic.CapsuleAim;
import com.luckgoose.universalcapsule.logic.CapsulePlacer;
import com.luckgoose.universalcapsule.network.CapsulePlacePacket;
import com.luckgoose.universalcapsule.network.CapsuleThrowPacket;
import com.luckgoose.universalcapsule.network.UniversalCapsuleNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端交互入口：
 *  - 第一次右键：进入扫描/放置预览模式
 *  - 第二次右键：把胶囊投掷出去
 *  - 滚轮：
 *      - 持有 X 轴快捷键：调整 sizeX
 *      - 持有 Y 轴快捷键：调整 sizeY
 *      - 持有 Z 轴快捷键：调整 sizeZ
 *      - 都不持时：直接拦截滚轮事件，避免在交互模式下误切快捷栏
 *  - R / Q：放置模式下旋转
 *  - Esc：退出当前模式
 *  - H：切换世界预览渲染样式
 *
 * 仅当玩家手中物品为 {@link UniversalCapsuleItem} 且处于扫描/放置模式时才会消费输入事件。
 */
@Mod.EventBusSubscriber(modid = UniversalCapsuleMod.MOD_ID, value = Dist.CLIENT)
public final class CapsuleClientEvents {

    private static final long CLICK_INTERVAL_MS = 200L;

    private CapsuleClientEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!event.getLevel().isClientSide) return;
        if (event.getEntity() != Minecraft.getInstance().player) return;
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) return;
        if (CapsuleClientState.throttleClick(CLICK_INTERVAL_MS)) {
            event.setCanceled(true);
            return;
        }
        InteractionHand hand = event.getHand();
        CapsuleMode mode = CapsuleItemNbt.getMode(stack);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        if (mode == CapsuleMode.EMPTY) {
            if (CapsuleClientState.isScanning() && CapsuleClientState.getActiveHand() == hand) {
                int sx = CapsuleClientState.getSizeX(stack);
                int sy = CapsuleClientState.getSizeY(stack);
                int sz = CapsuleClientState.getSizeZ(stack);
                BlockPos origin = CapsuleClientState.getScanOrigin(player, stack);
                if (origin == null || CapsuleClientState.countClientCapturable(player.level(), origin, new BlockPos(sx, sy, sz)) <= 0) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.goose_universalcapsule.capsule.capture_empty"), true);
                    event.setCanceled(true);
                    return;
                }
                UniversalCapsuleNetwork.CHANNEL.sendToServer(new CapsuleThrowPacket(
                        hand, origin, sx, sy, sz, CapsuleClientState.getYOffset(),
                        CapsuleClientState.getPlaceRotation()));
                CapsuleClientState.exitScanning();
            } else {
                CapsuleClientState.enterScanning(hand, stack);
            }
            event.setCanceled(true);
        } else if (mode.hasContent()) {
            if (CapsuleClientState.isPlacing() && CapsuleClientState.getActiveHand() == hand) {
                sendPlaceAtPreview(player, stack, hand);
            } else {
                CapsuleClientState.enterPlacing(hand);
            }
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide) return;
        if (event.getEntity() != Minecraft.getInstance().player) return;
        if (!CapsuleClientState.isInActiveMode()) return;
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) return;
        handleCapsuleRightClick(event, stack, event.getHand());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        if (!event.getLevel().isClientSide) return;
        if (event.getEntity() != Minecraft.getInstance().player) return;
        if (!CapsuleClientState.isInActiveMode()) return;
        ItemStack stack = event.getEntity().getItemInHand(event.getHand());
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) return;
        handleCapsuleRightClick(event, stack, event.getHand());
    }

    private static void handleCapsuleRightClick(PlayerInteractEvent event, ItemStack stack, InteractionHand hand) {
        if (CapsuleClientState.throttleClick(CLICK_INTERVAL_MS)) {
            event.setCanceled(true);
            return;
        }
        CapsuleMode mode = CapsuleItemNbt.getMode(stack);
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        if (mode == CapsuleMode.EMPTY && CapsuleClientState.isScanning() && CapsuleClientState.getActiveHand() == hand) {
            int sx = CapsuleClientState.getSizeX(stack);
            int sy = CapsuleClientState.getSizeY(stack);
            int sz = CapsuleClientState.getSizeZ(stack);
            BlockPos origin = CapsuleClientState.getScanOrigin(player, stack);
            if (origin == null || CapsuleClientState.countClientCapturable(player.level(), origin, new BlockPos(sx, sy, sz)) <= 0) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.goose_universalcapsule.capsule.capture_empty"), true);
                event.setCanceled(true);
                return;
            }
            UniversalCapsuleNetwork.CHANNEL.sendToServer(new CapsuleThrowPacket(
                    hand, origin, sx, sy, sz, CapsuleClientState.getYOffset(),
                    CapsuleClientState.getPlaceRotation()));
            CapsuleClientState.exitScanning();
            event.setCanceled(true);
        } else if (mode.hasContent() && CapsuleClientState.isPlacing() && CapsuleClientState.getActiveHand() == hand) {
            sendPlaceAtPreview(player, stack, hand);
            event.setCanceled(true);
        }
    }

    private static void sendPlaceAtPreview(LocalPlayer player, ItemStack stack, InteractionHand hand) {
        CapsuleTemplate template = UniversalCapsuleItem.readTemplate(stack);
        if (template == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.goose_universalcapsule.capsule.place_no_template"), true);
            CapsuleClientState.exitPlacing();
            return;
        }
        var rotation = CapsuleClientState.getPlaceRotation();
        BlockPos rotSize = CapsulePlacer.rotatedSize(template.getSize(), rotation);
        BlockPos origin = CapsuleAim.getPlacementOrigin(player, rotSize)
                .offset(0, CapsuleClientState.getYOffset(), 0);
        UniversalCapsuleNetwork.CHANNEL.sendToServer(new CapsulePlacePacket(hand, origin, rotation));
        CapsuleClientState.exitPlacing();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyInput(InputEvent.Key event) {
        if (!CapsuleClientState.isInActiveMode()) return;
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_REPEAT) return;
        if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
            CapsuleClientState.clearModes();
            return;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!CapsuleClientState.isInActiveMode()) return;
        event.setCanceled(true);
    }

    /**
     * 滚轮：
     *  - 在交互模式下，按住 X/Y/Z 轴快捷键时：调整对应轴尺寸
     *  - 否则：取消事件，防止切快捷栏
     *  - 注意：HIGH 优先级，确保在原版快捷栏切换之前消费。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!CapsuleClientState.isInActiveMode()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack stack = player.getItemInHand(CapsuleClientState.getActiveHand());
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) return;

        double rawDelta = event.getScrollDelta();
        if (rawDelta == 0.0D) {
            event.setCanceled(true);
            return;
        }
        int sign = rawDelta > 0 ? 1 : -1;

        boolean axisXHeld = CapsuleKeyBindings.AXIS_X.isDown();
        boolean axisYHeld = CapsuleKeyBindings.AXIS_Y.isDown();
        boolean axisZHeld = CapsuleKeyBindings.AXIS_Z.isDown();
        boolean yOffsetHeld = CapsuleKeyBindings.Y_OFFSET.isDown();

        if (CapsuleClientState.isScanning()) {
            if (axisXHeld) {
                CapsuleClientState.adjustAxisX(sign);
                event.setCanceled(true);
                return;
            }
            if (axisYHeld) {
                CapsuleClientState.adjustAxisY(sign);
                event.setCanceled(true);
                return;
            }
            if (axisZHeld) {
                CapsuleClientState.adjustAxisZ(sign);
                event.setCanceled(true);
                return;
            }
            if (yOffsetHeld) {
                CapsuleClientState.adjustYOffset(sign);
                event.setCanceled(true);
                return;
            }
        } else if (CapsuleClientState.isPlacing()) {
            if (yOffsetHeld) {
                CapsuleClientState.adjustYOffset(sign);
                event.setCanceled(true);
                return;
            }
        }
        // 扫描/放置模式但没按轴向修饰键：拦截滚轮，避免切快捷栏。
        event.setCanceled(true);
    }

    /**
     * 在 START 阶段提前吃掉原版丢弃键（默认 Q），避免放置模式下旋转的同时把胶囊丢掉；
     * 同时吃掉 inventory key（默认 E）以防止打开物品栏中断。
     */
    @SubscribeEvent
    public static void onClientTickStart(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!CapsuleClientState.isPlacing() && !CapsuleClientState.isScanning()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        ItemStack stack = player.getItemInHand(CapsuleClientState.getActiveHand());
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) return;
        // 吞掉原版丢弃键待处理点击（默认 Q）。在放置模式下 Q 已被 ROTATE_CCW 占用。
        while (mc.options.keyDrop.consumeClick()) {
            // 已吞掉。
        }
        // 吞掉 inventory key（默认 E）防止打开物品栏
        while (mc.options.keyInventory.consumeClick()) {
            // 已吞掉。
        }
        while (mc.options.keySwapOffhand.consumeClick()) {
            // 已吞掉。
        }
        while (mc.options.keyChat.consumeClick()) {
            // 已吞掉。
        }
        while (mc.options.keyCommand.consumeClick()) {
            // 已吞掉。
        }
        drainNonEssentialClicks();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (Minecraft.getInstance().screen != null) return;

        // H 键：在「线+面」与「半透明模型」两种预览样式之间切换。
        while (CapsuleKeyBindings.TOGGLE_PREVIEW.consumeClick()) {
            CapsuleClientState.toggleGhostMode();
        }

        if (!CapsuleClientState.isInActiveMode()) {
            CapsuleKeyBindings.ROTATE_CW.consumeClick();
            CapsuleKeyBindings.ROTATE_CCW.consumeClick();
            return;
        }

        if (CapsuleClientState.isScanning()) {
            ItemStack stack = player.getItemInHand(CapsuleClientState.getActiveHand());
            if (!(stack.getItem() instanceof UniversalCapsuleItem)
                    || CapsuleItemNbt.getMode(stack) != CapsuleMode.EMPTY) {
                CapsuleClientState.exitScanning();
                return;
            }
        } else if (CapsuleClientState.isPlacing()) {
            ItemStack stack = player.getItemInHand(CapsuleClientState.getActiveHand());
            if (!(stack.getItem() instanceof UniversalCapsuleItem)
                    || !CapsuleItemNbt.getMode(stack).hasContent()) {
                CapsuleClientState.exitPlacing();
                return;
            }
            while (CapsuleKeyBindings.ROTATE_CW.consumeClick()) {
                CapsuleClientState.rotatePlacement(true);
            }
            while (CapsuleKeyBindings.ROTATE_CCW.consumeClick()) {
                CapsuleClientState.rotatePlacement(false);
            }
        }
    }

    private static void drainNonEssentialClicks() {
        KeyMapping[] mappings = Minecraft.getInstance().options.keyMappings;
        for (KeyMapping mapping : mappings) {
            if (mapping == null || isCapsuleMapping(mapping)) continue;
            drainClicks(mapping);
        }
    }

    private static boolean isCapsuleMapping(KeyMapping mapping) {
        return mapping == CapsuleKeyBindings.AXIS_X
                || mapping == CapsuleKeyBindings.AXIS_Y
                || mapping == CapsuleKeyBindings.AXIS_Z
                || mapping == CapsuleKeyBindings.Y_OFFSET
                || mapping == CapsuleKeyBindings.ROTATE_CW
                || mapping == CapsuleKeyBindings.ROTATE_CCW
                || mapping == CapsuleKeyBindings.TOGGLE_PREVIEW
                || isVanillaMovementMapping(mapping);
    }

    private static boolean isVanillaMovementMapping(KeyMapping mapping) {
        Minecraft mc = Minecraft.getInstance();
        return mapping == mc.options.keyUp
                || mapping == mc.options.keyDown
                || mapping == mc.options.keyLeft
                || mapping == mc.options.keyRight
                || mapping == mc.options.keyJump
                || mapping == mc.options.keyShift;
    }

    private static void drainClicks(KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // 已吞掉。
        }
    }
}
