package com.luckgoose.universalcapsule.item;

import com.luckgoose.universalcapsule.CapsuleConstants;
import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import com.luckgoose.universalcapsule.data.CapsuleSealColors;
import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.data.CapsuleTemplateStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 鹅工作坊·胶囊。
 *
 * 状态机：
 *  - EMPTY    : 空胶囊。右键进入扫描状态（客户端本地），扫描框中至少有 1 个可收取目标时再次右键执行捕获。
 *  - UNNAMED  : 已捕获，未在工作方块内命名 / 保存。可以直接放置。
 *  - READY    : 工作方块内已命名 / 保存 / 或从已保存模板写入。可以直接放置。
 *
 * 实际的扫描 / 放置交互由客户端 {@code CapsuleClientState} 配合网络包驱动，
 * 物品本身只负责状态、Tooltip 与发包入口。
 */
public class UniversalCapsuleItem extends Item {

    public UniversalCapsuleItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // 客户端事件处理器会负责扫描 / 放置交互，这里只把控制权交回去。
            return InteractionResultHolder.pass(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    /**
     * 兼容性自动迁移：把旧版本（在 tmp-storage 改造前捕获的）UNNAMED 胶囊
     * 物品上的 inline NBT 模板搬到磁盘 tmp_xxx.nbt，物品 NBT 减重 1000+ 倍。
     *
     * 触发时机：每次玩家 inventory tick（每 tick 一次）。检测到 inline 存在且 templateId 为空时，
     * 一次性写盘并清除 inline。之后该物品就和新版本捕获的胶囊一样小。
     *
     * 仅服务端执行（客户端无文件写权限）。
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (level.isClientSide) return;
        if (CapsuleItemNbt.getMode(stack) != CapsuleMode.UNNAMED) return;
        if (!CapsuleItemNbt.getTemplateId(stack).isEmpty()) return;  // 已经是 tmp/正式 ID
        var inline = CapsuleItemNbt.getInlineTemplate(stack);
        if (inline == null) return;

        try {
            CapsuleTemplate template = CapsuleTemplate.load(inline);
            String tempId = CapsuleTemplateStorage.generateTempId();
            if (CapsuleTemplateStorage.save(tempId, template)) {
                CapsuleItemNbt.setTemplateId(stack, tempId);
                CapsuleItemNbt.setInlineTemplate(stack, null);
            }
        } catch (Throwable ignored) {
            // 迁移失败保留旧 inline，下个 tick 还有机会重试；不影响游戏继续。
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        CapsuleMode mode = CapsuleItemNbt.getMode(stack);
        String name = CapsuleItemNbt.getDisplayName(stack);
        // 只要不是 EMPTY 且填写了名字，就直接显示名字；这样 /goosecapsule rename 在
        // UNNAMED 状态下也能立即生效，不必等到 save 后才显示。
        if (mode != CapsuleMode.EMPTY && !name.isEmpty()) {
            return Component.translatable("item.goose_universalcapsule.universal_capsule.named", name);
        }
        if (mode == CapsuleMode.UNNAMED) {
            return Component.translatable("item.goose_universalcapsule.universal_capsule.unnamed");
        }
        return Component.translatable("item.goose_universalcapsule.universal_capsule.empty");
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        CapsuleMode mode = CapsuleItemNbt.getMode(stack);
        switch (mode) {
            case EMPTY: {
                tooltip.add(Component.translatable("tooltip.goose_universalcapsule.capsule.empty")
                        .withStyle(ChatFormatting.GRAY));
                break;
            }
            case UNNAMED: {
                BlockPos size = CapsuleItemNbt.getSize(stack);
                int count = CapsuleItemNbt.getBlockCount(stack);
                tooltip.add(Component.translatable("tooltip.goose_universalcapsule.capsule.size",
                        size.getX(), size.getY(), size.getZ()).withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable("tooltip.goose_universalcapsule.capsule.blocks", count)
                        .withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable("tooltip.goose_universalcapsule.capsule.unnamed_hint")
                        .withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
            case READY: {
                BlockPos size = CapsuleItemNbt.getSize(stack);
                int count = CapsuleItemNbt.getBlockCount(stack);
                tooltip.add(Component.translatable("tooltip.goose_universalcapsule.capsule.size",
                        size.getX(), size.getY(), size.getZ()).withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable("tooltip.goose_universalcapsule.capsule.blocks", count)
                        .withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable("tooltip.goose_universalcapsule.capsule.ready_hint")
                        .withStyle(ChatFormatting.DARK_GRAY));
                break;
            }
        }
    }

    /* ===== 服务端工具方法 ===== */

    /**
     * 将一次捕获的模板写入物品 NBT，物品进入 UNNAMED 状态。
     */
    public static void writeCapturedTemplate(ItemStack stack, CapsuleTemplate template, Player author) {
        if (author != null) {
            CapsuleItemNbt.setAuthor(stack, author.getGameProfile().getName());
            template.setAuthor(author.getGameProfile().getName());
        }
        CapsuleItemNbt.setInlineTemplate(stack, template.save());
        CapsuleItemNbt.setTemplateId(stack, "");
        CapsuleItemNbt.setDisplayName(stack, "");
        CapsuleItemNbt.setStyle(stack, CapsuleSealColors.PRESETS[3].hex());
        CapsuleItemNbt.setNumber(stack, "1");
        CapsuleItemNbt.setSize(stack, template.getSize());
        CapsuleItemNbt.setBlockCount(stack, template.getBlockCount());
        CapsuleItemNbt.setMode(stack, CapsuleMode.UNNAMED);
    }

    /**
     * 工作方块保存动作：把 UNNAMED 的内联模板写入磁盘，物品改成 READY。
     */
    public static boolean saveToStorage(ItemStack stack, String displayName, String style, String number, Player author) {
        return saveToStorage(stack, CapsuleTemplateStorage.generateId(), displayName, style, number, author, false);
    }

    public static boolean saveToStorage(ItemStack stack, String templateId, String displayName, String style, String number,
                                        Player author, boolean overwrite) {
        if (CapsuleItemNbt.getMode(stack) != CapsuleMode.UNNAMED) {
            return false;
        }
        String id = CapsuleTemplateStorage.normalizeId(templateId);
        if (!CapsuleTemplateStorage.isValidId(id) || (!overwrite && CapsuleTemplateStorage.exists(id))) return false;

        // 优先从磁盘上的 tmp 模板加载；若 tmp id 为空（例如旧物品或写盘失败回退），再退到 inline
        String tempId = CapsuleItemNbt.getTemplateId(stack);
        CapsuleTemplate template = null;
        if (!tempId.isEmpty()) {
            template = CapsuleTemplateStorage.load(tempId);
        }
        if (template == null) {
            var inline = CapsuleItemNbt.getInlineTemplate(stack);
            if (inline == null) return false;
            template = CapsuleTemplate.load(inline);
        }
        if (template == null) return false;

        template.setDisplayName(displayName);
        template.setStyle(style);
        template.setNumber(number);
        if (author != null) {
            template.setAuthor(author.getGameProfile().getName());
        }
        if (!CapsuleTemplateStorage.save(id, template)) {
            return false;
        }
        // 清理 tmp 文件，避免磁盘上堆积孤儿
        if (CapsuleTemplateStorage.isTempId(tempId) && !tempId.equals(id)) {
            CapsuleTemplateStorage.delete(tempId);
        }
        CapsuleItemNbt.setInlineTemplate(stack, null);
        CapsuleItemNbt.setTemplateId(stack, id);
        CapsuleItemNbt.setDisplayName(stack, displayName);
        CapsuleItemNbt.setStyle(stack, style);
        CapsuleItemNbt.setNumber(stack, number);
        CapsuleItemNbt.setSize(stack, template.getSize());
        CapsuleItemNbt.setBlockCount(stack, template.getBlockCount());
        CapsuleItemNbt.setMode(stack, CapsuleMode.READY);
        return true;
    }

    /**
     * 工作方块加载动作：把磁盘上的模板写入一个空胶囊，物品变成 READY。
     */
    public static boolean loadFromStorage(ItemStack stack, String templateId) {
        if (CapsuleItemNbt.getMode(stack) != CapsuleMode.EMPTY) {
            return false;
        }
        String id = CapsuleTemplateStorage.normalizeId(templateId);
        CapsuleTemplate template = CapsuleTemplateStorage.load(id);
        if (template == null) return false;
        CapsuleItemNbt.setInlineTemplate(stack, null);
        CapsuleItemNbt.setTemplateId(stack, id);
        CapsuleItemNbt.setDisplayName(stack, template.getDisplayName());
        CapsuleItemNbt.setStyle(stack, template.getStyle());
        CapsuleItemNbt.setNumber(stack, template.getNumber());
        CapsuleItemNbt.setSize(stack, template.getSize());
        CapsuleItemNbt.setBlockCount(stack, template.getBlockCount());
        CapsuleItemNbt.setAuthor(stack, template.getAuthor());
        CapsuleItemNbt.setMode(stack, CapsuleMode.READY);
        return true;
    }

    /**
     * 取出胶囊内的结构模板（无论是内联模板还是磁盘模板）。
     */
    @Nullable
    public static CapsuleTemplate readTemplate(ItemStack stack) {
        CapsuleMode mode = CapsuleItemNbt.getMode(stack);
        if (mode != CapsuleMode.UNNAMED && mode != CapsuleMode.READY) {
            return null;
        }
        // 新流程：UNNAMED 也通过 templateId 指向磁盘上的 tmp_xxx.nbt
        String id = CapsuleItemNbt.getTemplateId(stack);
        if (!id.isEmpty()) {
            CapsuleTemplate t = CapsuleTemplateStorage.load(id);
            if (t != null) return t;
        }
        // 兼容旧物品：UNNAMED 状态下还可能带内联模板
        if (mode == CapsuleMode.UNNAMED) {
            var inline = CapsuleItemNbt.getInlineTemplate(stack);
            if (inline != null) return CapsuleTemplate.load(inline);
        }
        return null;
    }
}
