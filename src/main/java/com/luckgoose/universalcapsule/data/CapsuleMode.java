package com.luckgoose.universalcapsule.data;

/**
 * 胶囊物品状态。
 *
 * EMPTY 只能进入扫描流程；UNNAMED / READY 都包含可放置模板，但 READY 已绑定正式模板 ID。
 */
public enum CapsuleMode {
    /** 空胶囊，无模板内容。 */
    EMPTY,
    /** 已捕获但未正式保存的临时模板。 */
    UNNAMED,
    /** 已保存或从模板库加载的正式模板。 */
    READY;

    /** 从 NBT 字符串恢复状态；未知值按 EMPTY 处理，避免损坏物品阻塞交互。 */
    public static CapsuleMode fromString(String name) {
        if (name == null || name.isEmpty()) {
            return EMPTY;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException ex) {
            return EMPTY;
        }
    }

    /** 是否携带可放置内容。 */
    public boolean hasContent() {
        return this == UNNAMED || this == READY;
    }
}
