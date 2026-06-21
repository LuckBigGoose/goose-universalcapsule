package com.luckgoose.universalcapsule.data;

import java.util.Locale;

/**
 * 胶囊封条颜色工具。
 *
 * 颜色以十六进制字符串落入物品 NBT，样式贴图编号则由 {@code Number} 字段单独控制。
 */
public final class CapsuleSealColors {

    /** 预设色：id 用于指令/配置输入，中英文名称用于未来 UI 展示。 */
    public record Preset(String id, String zhName, String enName, int color) {
        /** 返回标准 #RRGGBB 形式。 */
        public String hex() {
            return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
        }
    }

    /** 内置封条色板。新增色板时注意客户端贴图样式编号不是从这里自动生成的。 */
    public static final Preset[] PRESETS = new Preset[]{
            new Preset("white", "万能白", "Capsule White", 0xF5F5F5),
            new Preset("silver", "银灰", "Silver", 0xC0C7D1),
            new Preset("black", "曜石黑", "Obsidian", 0x1A1D24),
            new Preset("red", "胶囊红", "Capsule Red", 0xE53935),
            new Preset("scarlet", "朱红", "Scarlet", 0xFF3D00),
            new Preset("orange", "橙色", "Orange", 0xFB8C00),
            new Preset("amber", "琥珀", "Amber", 0xFFB300),
            new Preset("gold", "金色", "Gold", 0xFDD835),
            new Preset("lemon", "柠檬黄", "Lemon", 0xD4E157),
            new Preset("lime", "青柠", "Lime", 0x7CB342),
            new Preset("green", "胶囊绿", "Capsule Green", 0x43A047),
            new Preset("emerald", "翡翠", "Emerald", 0x00A86B),
            new Preset("mint", "薄荷", "Mint", 0x66E6B3),
            new Preset("cyan", "青色", "Cyan", 0x00ACC1),
            new Preset("sky", "天空蓝", "Sky", 0x29B6F6),
            new Preset("blue", "胶囊蓝", "Capsule Blue", 0x1E88E5),
            new Preset("royal", "皇家蓝", "Royal Blue", 0x3949AB),
            new Preset("indigo", "靛蓝", "Indigo", 0x5E35B1),
            new Preset("violet", "紫罗兰", "Violet", 0x8E24AA),
            new Preset("purple", "紫色", "Purple", 0xAB47BC),
            new Preset("magenta", "品红", "Magenta", 0xD81B60),
            new Preset("pink", "粉色", "Pink", 0xF06292),
            new Preset("rose", "玫瑰", "Rose", 0xFF6F91),
            new Preset("coral", "珊瑚", "Coral", 0xFF7F50),
            new Preset("brown", "棕色", "Brown", 0x8D6E63),
            new Preset("copper", "铜色", "Copper", 0xB87333),
            new Preset("teal", "蓝绿", "Teal", 0x00897B),
            new Preset("aqua", "水蓝", "Aqua", 0x4DD0E1),
            new Preset("navy", "海军蓝", "Navy", 0x0D47A1),
            new Preset("plum", "梅紫", "Plum", 0x7B1FA2),
            new Preset("cream", "奶油", "Cream", 0xFFF3C4),
            new Preset("dragon", "龙珠橙", "Dragon Orange", 0xFF9800)
    };

    private CapsuleSealColors() {
    }

    /** 把预设 id、裸十六进制或 #RRGGBB 统一规范化为 #RRGGBB。 */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return PRESETS[3].hex();
        }
        String trimmed = value.trim();
        for (Preset preset : PRESETS) {
            if (preset.id.equalsIgnoreCase(trimmed)) {
                return preset.hex();
            }
        }
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        if (trimmed.matches("#[0-9a-fA-F]{6}")) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return PRESETS[3].hex();
    }

    /** 解析为 RGB 整数；非法输入会回退到默认红色。 */
    public static int parse(String value) {
        String hex = normalize(value);
        return Integer.parseInt(hex.substring(1), 16);
    }

    /** 查找与输入颜色最接近的预设下标，供 UI 或命令提示使用。 */
    public static int findNearestIndex(String value) {
        int color = parse(value);
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < PRESETS.length; i++) {
            int preset = PRESETS[i].color;
            int dr = ((color >> 16) & 255) - ((preset >> 16) & 255);
            int dg = ((color >> 8) & 255) - ((preset >> 8) & 255);
            int db = (color & 255) - (preset & 255);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }
}
