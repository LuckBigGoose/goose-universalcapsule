package com.luckgoose.universalcapsule.data;

import com.luckgoose.universalcapsule.CapsuleConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * 胶囊物品 NBT 读写工具，所有键都集中在 UniversalCapsule 复合标签下。
 */
public final class CapsuleItemNbt {

    private CapsuleItemNbt() {
    }

    public static CompoundTag getOrCreateRoot(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.contains(CapsuleConstants.NBT_ROOT, Tag.TAG_COMPOUND)) {
            tag.put(CapsuleConstants.NBT_ROOT, new CompoundTag());
        }
        return tag.getCompound(CapsuleConstants.NBT_ROOT);
    }

    @Nullable
    public static CompoundTag getRoot(ItemStack stack) {
        if (!stack.hasTag()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(CapsuleConstants.NBT_ROOT, Tag.TAG_COMPOUND)) {
            return null;
        }
        return tag.getCompound(CapsuleConstants.NBT_ROOT);
    }

    public static CapsuleMode getMode(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null) {
            return CapsuleMode.EMPTY;
        }
        return CapsuleMode.fromString(root.getString(CapsuleConstants.NBT_MODE));
    }

    public static void setMode(ItemStack stack, CapsuleMode mode) {
        CompoundTag root = getOrCreateRoot(stack);
        root.putString(CapsuleConstants.NBT_MODE, mode.name());
    }

    public static int getScanSize(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null || !root.contains(CapsuleConstants.NBT_SCAN_SIZE)) {
            return CapsuleConstants.DEFAULT_SCAN_SIZE;
        }
        int v = root.getInt(CapsuleConstants.NBT_SCAN_SIZE);
        return clampScanSize(v);
    }

    public static void setScanSize(ItemStack stack, int size) {
        CompoundTag root = getOrCreateRoot(stack);
        root.putInt(CapsuleConstants.NBT_SCAN_SIZE, clampScanSize(size));
    }

    public static int clampScanSize(int v) {
        if (v < CapsuleConstants.MIN_SCAN_SIZE) return CapsuleConstants.MIN_SCAN_SIZE;
        if (v > CapsuleConstants.MAX_SCAN_SIZE) return CapsuleConstants.MAX_SCAN_SIZE;
        return v;
    }

    /* ===== 三轴尺寸（新版本）：未设置时回退到旧的 scanSize 立方体值 ===== */

    public static int getSizeX(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        int fallback = getScanSize(stack);
        if (root == null || !root.contains(CapsuleConstants.NBT_SIZE_AXIS_X)) {
            return fallback;
        }
        return clampScanSize(root.getInt(CapsuleConstants.NBT_SIZE_AXIS_X));
    }

    public static int getSizeY(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        int fallback = getScanSize(stack);
        if (root == null || !root.contains(CapsuleConstants.NBT_SIZE_AXIS_Y)) {
            return fallback;
        }
        return clampScanSize(root.getInt(CapsuleConstants.NBT_SIZE_AXIS_Y));
    }

    public static int getSizeZ(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        int fallback = getScanSize(stack);
        if (root == null || !root.contains(CapsuleConstants.NBT_SIZE_AXIS_Z)) {
            return fallback;
        }
        return clampScanSize(root.getInt(CapsuleConstants.NBT_SIZE_AXIS_Z));
    }

    public static void setSizeXYZ(ItemStack stack, int x, int y, int z) {
        CompoundTag root = getOrCreateRoot(stack);
        root.putInt(CapsuleConstants.NBT_SIZE_AXIS_X, clampScanSize(x));
        root.putInt(CapsuleConstants.NBT_SIZE_AXIS_Y, clampScanSize(y));
        root.putInt(CapsuleConstants.NBT_SIZE_AXIS_Z, clampScanSize(z));
    }

    public static String getTemplateId(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null) {
            return "";
        }
        return root.getString(CapsuleConstants.NBT_TEMPLATE_ID);
    }

    public static void setTemplateId(ItemStack stack, String id) {
        CompoundTag root = getOrCreateRoot(stack);
        if (id == null || id.isEmpty()) {
            root.remove(CapsuleConstants.NBT_TEMPLATE_ID);
        } else {
            root.putString(CapsuleConstants.NBT_TEMPLATE_ID, id);
        }
    }

    public static String getDisplayName(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null) {
            return "";
        }
        return root.getString(CapsuleConstants.NBT_DISPLAY_NAME);
    }

    public static void setDisplayName(ItemStack stack, String name) {
        CompoundTag root = getOrCreateRoot(stack);
        if (name == null) {
            name = "";
        }
        root.putString(CapsuleConstants.NBT_DISPLAY_NAME, name);
    }

    public static String getStyle(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null || root.getString(CapsuleConstants.NBT_STYLE).isEmpty()) {
            return CapsuleConstants.STYLE_DEFAULT;
        }
        return CapsuleSealColors.normalize(root.getString(CapsuleConstants.NBT_STYLE));
    }

    public static void setStyle(ItemStack stack, String style) {
        CompoundTag root = getOrCreateRoot(stack);
        root.putString(CapsuleConstants.NBT_STYLE, CapsuleSealColors.normalize(style));
    }

    public static String getNumber(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null || root.getString(CapsuleConstants.NBT_NUMBER).isEmpty()) {
            return "1";
        }
        return root.getString(CapsuleConstants.NBT_NUMBER);
    }

    public static void setNumber(ItemStack stack, String number) {
        CompoundTag root = getOrCreateRoot(stack);
        String value = number == null ? "" : number.trim();
        if (value.isEmpty()) {
            value = "1";
        }
        int style = 1;
        try {
            style = Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }
        style = Math.max(1, Math.min(30, style));
        root.putString(CapsuleConstants.NBT_NUMBER, Integer.toString(style));
    }

    @Nullable
    public static CompoundTag getInlineTemplate(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null || !root.contains(CapsuleConstants.NBT_INLINE_TEMPLATE, Tag.TAG_COMPOUND)) {
            return null;
        }
        return root.getCompound(CapsuleConstants.NBT_INLINE_TEMPLATE);
    }

    public static void setInlineTemplate(ItemStack stack, @Nullable CompoundTag templateTag) {
        CompoundTag root = getOrCreateRoot(stack);
        if (templateTag == null) {
            root.remove(CapsuleConstants.NBT_INLINE_TEMPLATE);
        } else {
            root.put(CapsuleConstants.NBT_INLINE_TEMPLATE, templateTag);
        }
    }

    public static void setSize(ItemStack stack, BlockPos size) {
        CompoundTag root = getOrCreateRoot(stack);
        root.putInt(CapsuleConstants.NBT_SIZE_X, size.getX());
        root.putInt(CapsuleConstants.NBT_SIZE_Y, size.getY());
        root.putInt(CapsuleConstants.NBT_SIZE_Z, size.getZ());
    }

    public static BlockPos getSize(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null) return BlockPos.ZERO;
        return new BlockPos(
                root.getInt(CapsuleConstants.NBT_SIZE_X),
                root.getInt(CapsuleConstants.NBT_SIZE_Y),
                root.getInt(CapsuleConstants.NBT_SIZE_Z));
    }

    public static void setBlockCount(ItemStack stack, int count) {
        CompoundTag root = getOrCreateRoot(stack);
        root.putInt(CapsuleConstants.NBT_BLOCK_COUNT, count);
    }

    public static int getBlockCount(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null) return 0;
        return root.getInt(CapsuleConstants.NBT_BLOCK_COUNT);
    }

    public static void setAuthor(ItemStack stack, String author) {
        CompoundTag root = getOrCreateRoot(stack);
        root.putString(CapsuleConstants.NBT_AUTHOR, author == null ? "" : author);
    }

    public static String getAuthor(ItemStack stack) {
        CompoundTag root = getRoot(stack);
        if (root == null) return "";
        return root.getString(CapsuleConstants.NBT_AUTHOR);
    }

    public static void clearContent(ItemStack stack) {
        CompoundTag root = getOrCreateRoot(stack);
        root.remove(CapsuleConstants.NBT_TEMPLATE_ID);
        root.remove(CapsuleConstants.NBT_INLINE_TEMPLATE);
        root.remove(CapsuleConstants.NBT_DISPLAY_NAME);
        root.remove(CapsuleConstants.NBT_BLOCK_COUNT);
        root.remove(CapsuleConstants.NBT_SIZE_X);
        root.remove(CapsuleConstants.NBT_SIZE_Y);
        root.remove(CapsuleConstants.NBT_SIZE_Z);
        root.remove(CapsuleConstants.NBT_STYLE);
        root.remove(CapsuleConstants.NBT_NUMBER);
        root.remove(CapsuleConstants.NBT_AUTHOR);
        setMode(stack, CapsuleMode.EMPTY);
    }
}
