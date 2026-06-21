package com.luckgoose.universalcapsule.multiblock;

import com.luckgoose.universalcapsule.multiblock.expander.DoubleHalfExpander;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局 expander 注册表。注册顺序即匹配顺序，先匹配先用。
 *
 * 本类只在服务端逻辑里使用，但接口与默认实现完全独立于其他 mod；
 * 跨 mod 适配实现通过 {@link #register(IMultiblockExpander)} 在外部注入。
 */
public final class MultiblockRegistry {

    private static final List<IMultiblockExpander> EXPANDERS = new ArrayList<>();
    private static boolean defaultsRegistered = false;

    /**
     * BlockState 级查找缓存：BlockState 是 vanilla 的不可变单例，identity 稳定，
     * 用 IdentityHashMap 即可。命中率高（一次 capture 5000 块大多落在同一组 BlockState）。
     *
     * 用 {@link #NO_EXPANDER} sentinel 表示\"已查询过且无 expander 命中\"，
     * 避免反复线性扫 EXPANDERS 列表。
     *
     * 注意：register / registerDefaults 会清空缓存，保证新增 expander 后查找能重新命中。
     */
    private static final IMultiblockExpander NO_EXPANDER = new IMultiblockExpander() {
        @Override
        public boolean isPart(BlockState state) { return false; }
        @Override
        public boolean isCenter(BlockState state) { return false; }
        @Override
        public String name() { return "<none>"; }
    };
    private static final Map<BlockState, IMultiblockExpander> STATE_CACHE = new IdentityHashMap<>();

    private MultiblockRegistry() {
    }

    public static synchronized void registerDefaults() {
        if (defaultsRegistered) return;
        defaultsRegistered = true;
        register(new DoubleHalfExpander());
    }

    public static synchronized void register(IMultiblockExpander expander) {
        EXPANDERS.add(expander);
        // 注册新 expander 必须清空缓存，否则之前缓存的 NO_EXPANDER 会让新 expander 永远命中不了
        synchronized (STATE_CACHE) {
            STATE_CACHE.clear();
        }
    }

    public static List<IMultiblockExpander> all() {
        return Collections.unmodifiableList(EXPANDERS);
    }

    /**
     * 找到第一个声明自己拥有该 state 的 expander；找不到返回 null。
     *
     * 性能：通过 {@link #STATE_CACHE} 缓存，避免每次都线性扫所有 expander。
     */
    public static IMultiblockExpander findFor(BlockState state) {
        if (state == null) return null;
        IMultiblockExpander cached;
        synchronized (STATE_CACHE) {
            cached = STATE_CACHE.get(state);
        }
        if (cached != null) {
            return cached == NO_EXPANDER ? null : cached;
        }
        IMultiblockExpander resolved = null;
        for (IMultiblockExpander e : EXPANDERS) {
            if (e.isPart(state)) {
                resolved = e;
                break;
            }
        }
        IMultiblockExpander valueToCache = resolved == null ? NO_EXPANDER : resolved;
        synchronized (STATE_CACHE) {
            STATE_CACHE.put(state, valueToCache);
        }
        return resolved;
    }

    /**
     * 工具：返回某个方块（如果是多方块的一部分）所属结构占用的所有世界坐标，
     * 包括中心块本身；如果该方块不属于任何已注册 expander，返回单点列表。
     */
    public static List<BlockPos> resolveOccupied(LevelAccessor level, BlockPos pos, BlockState state) {
        IMultiblockExpander e = findFor(state);
        if (e == null) return Collections.singletonList(pos);
        // 简单策略：若是中心块就直接展开；若是边缘块由 expander 自行处理（默认仅返回自身）
        if (e.isCenter(state)) {
            return e.getOccupiedWorldPositions(level, pos, state);
        }
        return e.getOccupiedWorldPositions(level, pos, state);
    }
}
