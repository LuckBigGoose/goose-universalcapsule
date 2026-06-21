package com.luckgoose.universalcapsule.task;

import com.luckgoose.universalcapsule.UniversalCapsuleMod;
import com.luckgoose.universalcapsule.CapsuleConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 胶囊任务调度器：统一承载飞行动画后的延迟结算与后台任务。
 *
 * 收集和摆放不在多个 tick 间拆分；真正修改世界时必须一次完成，避免多方块结构
 * 被跨 tick 只收取一半或只放置一半。预算参数仅保留给未来可安全切片的任务。
 */
@Mod.EventBusSubscriber(modid = UniversalCapsuleMod.MOD_ID)
public final class CapsuleTaskScheduler {

    private static final List<CapsuleTask> TASKS = new ArrayList<>();
    private static final Queue<CapsuleTask> PENDING_TASKS = new ConcurrentLinkedQueue<>();

    private CapsuleTaskScheduler() {
    }

    public static void submit(CapsuleTask task) {
        if (task == null) return;
        PENDING_TASKS.add(task);
    }

    public static int pending() {
        return TASKS.size() + PENDING_TASKS.size();
    }

    public static void clear() {
        TASKS.clear();
        PENDING_TASKS.clear();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        CapsuleTask pending;
        while ((pending = PENDING_TASKS.poll()) != null) {
            TASKS.add(pending);
        }
        if (TASKS.isEmpty()) return;
        Iterator<CapsuleTask> it = TASKS.iterator();
        while (it.hasNext()) {
            CapsuleTask t = it.next();
            try {
                if (t.tick(CapsuleConfig.TASK_BUDGET_PER_TICK)) {
                    it.remove();
                }
            } catch (Throwable ex) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clear();
    }
}
