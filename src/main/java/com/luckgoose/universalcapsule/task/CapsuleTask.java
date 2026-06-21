package com.luckgoose.universalcapsule.task;

import net.minecraft.server.level.ServerPlayer;

/**
 * 胶囊后台任务基类。
 *
 * 每 tick 会调用 {@link #tick(int)}，返回 true 表示任务完成。当前收集/摆放任务会忽略预算，
 * 在同一结算 tick 内完成世界写入，以保证多方块结构不会被拆成半收取或半放置状态。
 */
public abstract class CapsuleTask {

    public final ServerPlayer player;

    protected CapsuleTask(ServerPlayer player) {
        this.player = player;
    }

    public abstract boolean tick(int budget);

    /** 如果玩家下线/世界卸载，应当能优雅取消。 */
    public boolean isValid() {
        return player != null && player.isAlive() && !player.hasDisconnected();
    }

    public String describe() {
        return getClass().getSimpleName();
    }
}
