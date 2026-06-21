package com.luckgoose.universalcapsule.command;

import com.luckgoose.universalcapsule.CapsuleRegistry;
import com.luckgoose.universalcapsule.data.CapsuleItemNbt;
import com.luckgoose.universalcapsule.data.CapsuleMode;
import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.data.CapsuleTemplateStorage;
import com.luckgoose.universalcapsule.item.UniversalCapsuleItem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 胶囊指令（重设计版本）。共 6 条指令：
 *   1. /goosecapsule giveEmpty [targets]                       给予空胶囊（默认当前玩家）
 *   2. /goosecapsule list                                      列出已保存的所有模板
 *   3. /goosecapsule save <templateId> [overwrite]             把当前手持非空胶囊保存到文件
 *   4. /goosecapsule rename <name>                             修改当前手持非空胶囊名称
 *   5. /goosecapsule style <1-30>                              修改当前手持非空胶囊样式编号
 *   6. /goosecapsule giveTemplate <templateId> [targets]       给予指定模板胶囊（默认当前玩家）
 *
 * 所有失败均通过 {@link #fail} 给出原因 + 解决方案建议。
 */
public final class UniversalCapsuleCommands {

    private UniversalCapsuleCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goosecapsule")
                .requires(source -> source.hasPermission(2))

                // 1. 给予空胶囊
                .then(Commands.literal("giveEmpty")
                        .executes(ctx -> giveEmpty(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> giveEmpty(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))))

                // 2. 列出所有模板
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource())))

                // 3. 保存当前胶囊到文件
                .then(Commands.literal("save")
                        .then(Commands.argument("templateId", StringArgumentType.word())
                                .executes(ctx -> save(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "templateId"), false))
                                .then(Commands.literal("overwrite")
                                        .executes(ctx -> save(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "templateId"), true)))))

                // 4. 修改胶囊名称
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> rename(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))

                // 5. 修改胶囊样式 1-30
                .then(Commands.literal("style")
                        .then(Commands.argument("style", IntegerArgumentType.integer(1, 30))
                                .executes(ctx -> style(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "style")))))

                // 6. 给予指定模板胶囊
                .then(Commands.literal("giveTemplate")
                        .then(Commands.argument("templateId", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        CapsuleTemplateStorage.listAll().stream()
                                                .map(e -> e.id).collect(Collectors.toList()), b))
                                .executes(ctx -> giveTemplate(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "templateId"),
                                        List.of(ctx.getSource().getPlayerOrException())))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(ctx -> giveTemplate(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "templateId"),
                                                EntityArgument.getPlayers(ctx, "targets")))))));
    }

    /* ============================ 1. giveEmpty ============================ */
    private static int giveEmpty(CommandSourceStack source, Collection<ServerPlayer> targets) {
        if (targets == null || targets.isEmpty()) {
            fail(source, "未找到目标玩家",
                    "请指定至少一个在线玩家，或省略参数由玩家本人执行：/goosecapsule giveEmpty");
            return 0;
        }
        for (ServerPlayer target : targets) {
            target.getInventory().placeItemBackInInventory(
                    new ItemStack(CapsuleRegistry.UNIVERSAL_CAPSULE.get()));
        }
        final int count = targets.size();
        source.sendSuccess(() -> Component.literal("已给予空万能胶囊 × " + count), true);
        return count;
    }

    /* ============================ 2. list ============================ */
    private static int list(CommandSourceStack source) {
        List<CapsuleTemplateStorage.TemplateEntry> entries = CapsuleTemplateStorage.listAll();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                            "当前没有任何已保存的胶囊模板。可以先用 /goosecapsule save <id> 保存一个模板。")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已保存胶囊模板：" + entries.size())
                .withStyle(ChatFormatting.AQUA), false);
        for (CapsuleTemplateStorage.TemplateEntry entry : entries) {
            source.sendSuccess(() -> Component.literal(" - " + entry.id
                    + " | 名称=" + (entry.displayName.isEmpty() ? "<未命名>" : entry.displayName)
                    + " | 尺寸=" + entry.sizeX + "x" + entry.sizeY + "x" + entry.sizeZ
                    + " | 方块=" + entry.blockCount
                    + " | 样式=" + entry.number
                    + " | 作者=" + (entry.author.isEmpty() ? "<未知>" : entry.author)), false);
        }
        return entries.size();
    }

    /* ============================ 3. save ============================ */
    private static int save(CommandSourceStack source, String rawId, boolean overwrite)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) {
            fail(source, "主手未持有万能胶囊",
                    "请将一颗万能胶囊放入主手后再执行该指令。");
            return 0;
        }
        CapsuleMode mode = CapsuleItemNbt.getMode(stack);
        if (!mode.hasContent()) {
            fail(source, "当前胶囊为空，无法保存",
                    "请先在工作台扫描或装填结构（状态需为已封装/已就绪）后再保存。");
            return 0;
        }
        String id = CapsuleTemplateStorage.normalizeId(rawId);
        if (!CapsuleTemplateStorage.isValidId(id)) {
            fail(source, "模板 ID 无效：" + rawId,
                    "ID 只能包含小写字母、数字、下划线（_）和连字符（-），长度 1-64。");
            return 0;
        }
        if (!overwrite && CapsuleTemplateStorage.exists(id)) {
            fail(source, "模板 " + id + " 已存在",
                    "请换一个 ID，或在指令末尾追加 overwrite 进行覆盖：/goosecapsule save " + id + " overwrite");
            return 0;
        }

        String name = CapsuleItemNbt.getDisplayName(stack);
        String styleHex = CapsuleItemNbt.getStyle(stack);
        String number = CapsuleItemNbt.getNumber(stack);
        boolean ok;
        if (mode == CapsuleMode.UNNAMED) {
            ok = UniversalCapsuleItem.saveToStorage(stack, id, name, styleHex, number, player, true);
        } else {
            CapsuleTemplate template = UniversalCapsuleItem.readTemplate(stack);
            if (template == null) {
                fail(source, "无法读取胶囊内的模板数据",
                        "该胶囊的模板可能已损坏或被删除，请重新扫描装填后再保存。");
                return 0;
            }
            template.setDisplayName(name);
            template.setStyle(styleHex);
            template.setNumber(number);
            template.setAuthor(player.getGameProfile().getName());
            ok = CapsuleTemplateStorage.save(id, template);
            if (ok) {
                CapsuleItemNbt.setInlineTemplate(stack, null);
                CapsuleItemNbt.setTemplateId(stack, id);
                CapsuleItemNbt.setAuthor(stack, template.getAuthor());
                CapsuleItemNbt.setMode(stack, CapsuleMode.READY);
            }
        }
        if (!ok) {
            fail(source, "保存胶囊模板失败：" + id,
                    "请检查服务器日志；确认 config/goose/goose-universalcapsule/capsules 目录存在且可写。");
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已保存胶囊模板：" + id)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /* ============================ 4. rename ============================ */
    private static int rename(CommandSourceStack source, String name) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) {
            fail(source, "主手未持有万能胶囊",
                    "请将一颗万能胶囊放入主手后再执行该指令。");
            return 0;
        }
        if (!CapsuleItemNbt.getMode(stack).hasContent()) {
            fail(source, "当前胶囊为空，无法重命名",
                    "请先装填内容后再修改名称。空胶囊没有可命名的结构。");
            return 0;
        }
        if (name == null || name.trim().isEmpty()) {
            fail(source, "名称不能为空",
                    "请提供有效名称，例如：/goosecapsule rename \"我的基地\"。");
            return 0;
        }
        CapsuleItemNbt.setDisplayName(stack, name);
        updateInlineTemplateMetadata(stack, player);
        source.sendSuccess(() -> Component.literal("已将胶囊名称修改为：" + name)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /* ============================ 5. style ============================ */
    private static int style(CommandSourceStack source, int styleNumber) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof UniversalCapsuleItem)) {
            fail(source, "主手未持有万能胶囊",
                    "请将一颗万能胶囊放入主手后再执行该指令。");
            return 0;
        }
        if (!CapsuleItemNbt.getMode(stack).hasContent()) {
            fail(source, "当前胶囊为空，无法修改样式",
                    "请先装填内容后再修改样式。空胶囊不支持样式编号。");
            return 0;
        }
        if (styleNumber < 1 || styleNumber > 30) {
            fail(source, "样式编号超出范围：" + styleNumber,
                    "样式编号必须在 1-30 之间，例如：/goosecapsule style 7。");
            return 0;
        }
        CapsuleItemNbt.setNumber(stack, Integer.toString(styleNumber));
        updateInlineTemplateMetadata(stack, player);
        source.sendSuccess(() -> Component.literal("已将胶囊样式修改为：" + styleNumber)
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /* ============================ 6. giveTemplate ============================ */
    private static int giveTemplate(CommandSourceStack source, String rawId,
                                    Collection<ServerPlayer> targets) {
        if (targets == null || targets.isEmpty()) {
            fail(source, "未找到目标玩家",
                    "请指定至少一个在线玩家，或省略参数由玩家本人执行：/goosecapsule giveTemplate <id>");
            return 0;
        }
        String id = CapsuleTemplateStorage.normalizeId(rawId);
        if (!CapsuleTemplateStorage.isValidId(id)) {
            fail(source, "模板 ID 无效：" + rawId,
                    "ID 只能包含小写字母、数字、下划线（_）和连字符（-），可使用 /goosecapsule list 查看可用模板。");
            return 0;
        }
        CapsuleTemplate template = CapsuleTemplateStorage.load(id);
        if (template == null) {
            fail(source, "未找到胶囊模板：" + id,
                    "请使用 /goosecapsule list 查看可用模板，或先用 /goosecapsule save <id> 保存一个模板。");
            return 0;
        }
        for (ServerPlayer target : targets) {
            ItemStack stack = new ItemStack(CapsuleRegistry.UNIVERSAL_CAPSULE.get());
            CapsuleItemNbt.setInlineTemplate(stack, null);
            CapsuleItemNbt.setTemplateId(stack, id);
            CapsuleItemNbt.setDisplayName(stack, template.getDisplayName());
            CapsuleItemNbt.setStyle(stack, template.getStyle());
            CapsuleItemNbt.setNumber(stack, template.getNumber());
            CapsuleItemNbt.setSize(stack, template.getSize());
            CapsuleItemNbt.setBlockCount(stack, template.getBlockCount());
            CapsuleItemNbt.setAuthor(stack, template.getAuthor());
            CapsuleItemNbt.setMode(stack, CapsuleMode.READY);
            target.getInventory().placeItemBackInInventory(stack);
        }
        final int count = targets.size();
        source.sendSuccess(() -> Component.literal("已给予模板胶囊「" + id + "」× " + count)
                .withStyle(ChatFormatting.GREEN), true);
        return count;
    }

    /* ============================ 辅助方法 ============================ */
    private static void fail(CommandSourceStack source, String reason, String hint) {
        source.sendFailure(Component.literal("[胶囊] " + reason)
                .withStyle(ChatFormatting.RED));
        source.sendFailure(Component.literal("  → 建议：" + hint)
                .withStyle(ChatFormatting.GOLD));
    }

    private static void updateInlineTemplateMetadata(ItemStack stack, ServerPlayer player) {
        if (CapsuleItemNbt.getMode(stack) != CapsuleMode.UNNAMED) return;
        var inline = CapsuleItemNbt.getInlineTemplate(stack);
        if (inline == null) return;
        CapsuleTemplate template = CapsuleTemplate.load(inline);
        template.setDisplayName(CapsuleItemNbt.getDisplayName(stack));
        template.setStyle(CapsuleItemNbt.getStyle(stack));
        template.setNumber(CapsuleItemNbt.getNumber(stack));
        template.setAuthor(player.getGameProfile().getName());
        CapsuleItemNbt.setInlineTemplate(stack, template.save());
    }
}
