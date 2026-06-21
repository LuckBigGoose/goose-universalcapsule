package com.luckgoose.universalcapsule.client;

import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleTemplateStorage;
import com.luckgoose.universalcapsule.logic.CapsuleAim;
import com.luckgoose.universalcapsule.logic.CapsuleScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 纯客户端的胶囊交互临时状态：
 *  - 扫描模式开关 + 当前手位 + 三轴尺寸 + Y 偏移
 *  - 放置模式开关 + 当前旋转
 *  - 工作方块界面同步过来的模板列表缓存
 *  - HUD 是否展示
 */
public final class CapsuleClientState {

    private static boolean scanning = false;
    private static boolean placing = false;
    private static InteractionHand activeHand = InteractionHand.MAIN_HAND;
    private static int sizeX = -1;
    private static int sizeY = -1;
    private static int sizeZ = -1;
    private static int yOffset = 0;
    private static Rotation placeRotation = Rotation.NONE;
    private static long lastClickTimeMs = 0L;
    private static boolean ghostMode = false;
    private static List<CapsuleTemplateStorage.TemplateEntry> templateList = new ArrayList<>();

    /**
     * HUD 单调递增版本号：所有会改变 HUD 显示内容的状态变更都会让它 +1。
     * HUD 使用 (hudVersion, itemTagHash) 作为缓存键，避免每帧重建 Component 树。
     * 仅记录到 int，溢出无害（环绕后只是触发一次额外重建，与功能正确性无关）。
     */
    private static int hudVersion = 0;

    private CapsuleClientState() {
    }

    public static int getHudVersion() {
        return hudVersion;
    }

    private static void bumpHudVersion() {
        hudVersion++;
    }

    public static boolean isScanning() { return scanning; }
    public static boolean isPlacing() { return placing; }
    public static boolean isInActiveMode() { return scanning || placing; }
    public static InteractionHand getActiveHand() { return activeHand; }
    public static Rotation getPlaceRotation() { return placeRotation; }
    public static int getYOffset() { return yOffset; }
    public static boolean isGhostMode() { return ghostMode; }
    public static void toggleGhostMode() {
        ghostMode = !ghostMode;
        // 模式切换会让缓存的 PlacementPreview 在视觉表现上改变（线框+面板 / 半透明模型），
        // 但底层 PlacementPreview 数据本身不变，所以无需 invalidateCache。
        // HUD 当前不显示预览样式，但保险起见仍然 bump，避免未来添加显示时遗忘。
        bumpHudVersion();
    }

    public static void enterScanning(InteractionHand hand, ItemStack stack) {
        scanning = true;
        placing = false;
        activeHand = hand;
        sizeX = CapsuleItemNbt.getSizeX(stack);
        sizeY = CapsuleItemNbt.getSizeY(stack);
        sizeZ = CapsuleItemNbt.getSizeZ(stack);
        yOffset = 0;
        // 切换模式：让上次的预览缓存失效，避免显示陈旧组件
        CapsulePreviewRenderer.invalidateCache();
        bumpHudVersion();
    }

    public static void exitScanning() {
        scanning = false;
        sizeX = sizeY = sizeZ = -1;
        yOffset = 0;
        CapsulePreviewRenderer.invalidateCache();
        bumpHudVersion();
    }

    public static void enterPlacing(InteractionHand hand) {
        placing = true;
        scanning = false;
        sizeX = sizeY = sizeZ = -1;
        activeHand = hand;
        placeRotation = Rotation.NONE;
        yOffset = 0;
        CapsulePreviewRenderer.invalidateCache();
        bumpHudVersion();
    }

    public static void exitPlacing() {
        placing = false;
        yOffset = 0;
        CapsulePreviewRenderer.invalidateCache();
        bumpHudVersion();
    }

    public static void clearModes() {
        scanning = false;
        placing = false;
        sizeX = sizeY = sizeZ = -1;
        yOffset = 0;
        CapsulePreviewRenderer.invalidateCache();
        bumpHudVersion();
    }

    public static int getSizeX(ItemStack stack) {
        return sizeX > 0 ? sizeX : CapsuleItemNbt.getSizeX(stack);
    }

    public static int getSizeY(ItemStack stack) {
        return sizeY > 0 ? sizeY : CapsuleItemNbt.getSizeY(stack);
    }

    public static int getSizeZ(ItemStack stack) {
        return sizeZ > 0 ? sizeZ : CapsuleItemNbt.getSizeZ(stack);
    }

    public static BlockPos getScanOrigin(LocalPlayer player, ItemStack stack) {
        if (player == null || stack == null) return null;
        BlockPos sizeXYZ = new BlockPos(getSizeX(stack), getSizeY(stack), getSizeZ(stack));
        int sizeMax = Math.max(Math.max(sizeXYZ.getX(), sizeXYZ.getY()), sizeXYZ.getZ());
        BlockPos anchor = CapsuleAim.getSurfaceAnchor(player, sizeMax);
        return CapsuleScanner.calcOriginAnchored(anchor, sizeXYZ).offset(0, getYOffset(), 0);
    }

    public static int countClientCapturable(Level level, BlockPos origin, BlockPos sizeXYZ) {
        int count = 0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sizeXYZ.getX(); x++) {
            for (int y = 0; y < sizeXYZ.getY(); y++) {
                for (int z = 0; z < sizeXYZ.getZ(); z++) {
                    mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(mutable);
                    if (CapsuleScanner.isCapturableCheap(level, mutable, state)) count++;
                }
            }
        }
        return count;
    }

    public static void adjustAxisX(int delta) {
        sizeX = CapsuleItemNbt.clampScanSize(Math.max(1, getOrInit(sizeX) + delta));
        bumpHudVersion();
    }
    public static void adjustAxisY(int delta) {
        sizeY = CapsuleItemNbt.clampScanSize(Math.max(1, getOrInit(sizeY) + delta));
        bumpHudVersion();
    }
    public static void adjustAxisZ(int delta) {
        sizeZ = CapsuleItemNbt.clampScanSize(Math.max(1, getOrInit(sizeZ) + delta));
        bumpHudVersion();
    }

    private static int getOrInit(int v) {
        return v > 0 ? v : 5;
    }

    public static void adjustYOffset(int delta) {
        int prev = yOffset;
        yOffset += delta;
        if (yOffset > 256) yOffset = 256;
        if (yOffset < -256) yOffset = -256;
        // 仅在真正变化时才 bump，避免边界处一直触发 HUD 缓存失效
        if (yOffset != prev) bumpHudVersion();
    }

    public static void rotatePlacement(boolean clockwise) {
        Rotation[] values = Rotation.values();
        int idx = placeRotation.ordinal();
        if (clockwise) {
            placeRotation = values[Math.floorMod(idx + 1, values.length)];
        } else {
            placeRotation = values[Math.floorMod(idx - 1, values.length)];
        }
        bumpHudVersion();
    }

    public static boolean throttleClick(long minIntervalMs) {
        long now = System.currentTimeMillis();
        if (now - lastClickTimeMs < minIntervalMs) {
            return true;
        }
        lastClickTimeMs = now;
        return false;
    }

    public static List<CapsuleTemplateStorage.TemplateEntry> getTemplateList() {
        return Collections.unmodifiableList(templateList);
    }

    public static void acceptTemplateList(@Nullable List<CapsuleTemplateStorage.TemplateEntry> entries) {
        templateList = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }
}
