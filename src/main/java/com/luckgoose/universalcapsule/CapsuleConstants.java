package com.luckgoose.universalcapsule;

/**
 * 胶囊系统常量。
 *
 * 这里仅放跨包共享且不会被配置文件覆盖的稳定常量；可调行为参数放在 {@link CapsuleConfig}。
 */
public final class CapsuleConstants {

    /** 扫描尺寸上下限与默认值，三轴尺寸共用同一套限制。 */
    public static final int MIN_SCAN_SIZE = 1;
    public static final int MAX_SCAN_SIZE = 64;
    public static final int DEFAULT_SCAN_SIZE = 5;

    /** 新版三轴扫描尺寸键；缺失时回退到旧版 ScanSize。 */
    public static final String NBT_SIZE_AXIS_X = "SizeAxisX";
    public static final String NBT_SIZE_AXIS_Y = "SizeAxisY";
    public static final String NBT_SIZE_AXIS_Z = "SizeAxisZ";

    /** 单个模板允许表达的理论最大体积，与扫描上限保持一致。 */
    public static final int MAX_TEMPLATE_VOLUME = 64 * 64 * 64;

    /** 服务端校验：玩家中心到操作 origin 中心的最大允许距离（格）。 */
    public static final double MAX_INTERACT_RANGE = 96.0D;
    public static final double MAX_INTERACT_RANGE_SQ = MAX_INTERACT_RANGE * MAX_INTERACT_RANGE;

    /** 客户端预览：当扫描尺寸大于该值时不再绘制单个方块轮廓，避免误导。 */
    public static final int PREVIEW_OUTLINE_MAX_SIZE = 16;

    /** 模板文件目录名，实际父目录由 CapsuleTemplateStorage 负责拼接。 */
    public static final String CAPSULE_DIR_NAME = "capsules";

    /** 胶囊物品 NBT 根标签与内容字段名。 */
    public static final String NBT_ROOT = "UniversalCapsule";
    public static final String NBT_MODE = "Mode";
    public static final String NBT_SCAN_SIZE = "ScanSize";
    public static final String NBT_TEMPLATE_ID = "TemplateId";
    public static final String NBT_DISPLAY_NAME = "DisplayName";
    public static final String NBT_STYLE = "Style";
    public static final String NBT_NUMBER = "Number";
    public static final String NBT_INLINE_TEMPLATE = "Template";
    public static final String NBT_BLOCK_COUNT = "BlockCount";
    public static final String NBT_SIZE_X = "SizeX";
    public static final String NBT_SIZE_Y = "SizeY";
    public static final String NBT_SIZE_Z = "SizeZ";
    public static final String NBT_AUTHOR = "Author";

    /** 默认封条颜色。模型样式编号仍由 NBT_NUMBER 单独控制。 */
    public static final String STYLE_DEFAULT = "#E53935";

    private CapsuleConstants() {
    }
}
