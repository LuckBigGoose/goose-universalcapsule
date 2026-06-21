package com.luckgoose.universalcapsule.logic;

import com.luckgoose.universalcapsule.CapsuleConfig;
import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.multiblock.IMultiblockExpander;
import com.luckgoose.universalcapsule.multiblock.MultiblockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 扫描指定立方体区域，将可收取的方块写入 CapsuleTemplate。
 *
 * 采用两阶段策略避免多方块级联拆除：
 *  Phase 1（扫描）：纯读，把所有可捕获方块的 state + BlockEntity NBT 抓到 template
 *                  ；同时通过 IMultiblockExpander 自动扩展捕获边界，避免双高/3x3x2 漏抓。
 *  Phase 2（移除）：对全部已记录位置统一执行 setBlock(AIR)；
 *                  此时即使触发其它 onRemove 级联，也已经不会影响 template 完整性。
 */
public final class CapsuleScanner {

    private CapsuleScanner() {
    }

    /** 计算扫描盒子的最低角（旧版本：立方体 size×size×size，中心居中）。 */
    public static BlockPos calcOrigin(BlockPos center, int size) {
        int half = size / 2;
        return center.offset(-half, -half, -half);
    }

    /**
     * 三轴尺寸 + 地表锚点：扫描盒底面坐标 = center 的方块位置，X/Z 围绕 center 居中。
     * 这样从地表瞄准时盒子刚好"立在"目标方块上方，不会向下吃地皮。
     */
    public static BlockPos calcOriginAnchored(BlockPos center, BlockPos sizeXYZ) {
        return new BlockPos(
                center.getX() - sizeXYZ.getX() / 2,
                center.getY(),
                center.getZ() - sizeXYZ.getZ() / 2);
    }

    public static int countCapturable(Level level, BlockPos origin, BlockPos sizeXYZ) {
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sizeXYZ.getX(); x++) {
            for (int y = 0; y < sizeXYZ.getY(); y++) {
                for (int z = 0; z < sizeXYZ.getZ(); z++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(pos);
                    if (isCapturableCheap(level, pos, state)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    public static List<BlockPos> resolveCapturePositions(ServerLevel level, BlockPos origin,
                                                         BlockPos sizeXYZ, @Nullable Player author) {
        preloadChunks(level, origin, sizeXYZ.getX(), sizeXYZ.getZ());
        LinkedHashSet<BlockPos> all = new LinkedHashSet<>(expectedSetCapacity(sizeXYZ));
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sizeXYZ.getX(); x++) {
            for (int y = 0; y < sizeXYZ.getY(); y++) {
                for (int z = 0; z < sizeXYZ.getZ(); z++) {
                    cur.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(cur);
                    if (!isCapturable(level, cur, state, author)) continue;
                    BlockPos imm = cur.immutable();
                    all.add(imm);
                    IMultiblockExpander exp = MultiblockRegistry.findFor(state);
                    if (exp != null) {
                        for (BlockPos occ : exp.getOccupiedWorldPositions(level, imm, state)) {
                            BlockState occState = level.getBlockState(occ);
                            if (isCapturable(level, occ, occState, author)) {
                                all.add(occ.immutable());
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(all);
    }

    public static int countResolvedCapturable(ServerLevel level, BlockPos origin, BlockPos sizeXYZ,
                                              @Nullable Player author) {
        // 关键性能修复：count 路径不应触发 Forge BreakEvent，否则在同一次 capture 流程里
        // BreakEvent 会被 post 2~3 次（throw 包预检 + DelayedCaptureTask 预检 + 真正 capture）。
        // 这里改走 cheap 版扫描，仅用于估算数量；真正捕获时仍由 captureAndRemove → resolveCapturePositions
        // 走完整的 BreakEvent 检查保证保护 mod 行为不变。
        return countCapturablePositionsCheap(level, origin, sizeXYZ)
                + resolveCaptureEntities(level, origin, sizeXYZ, author).size();
    }

    /**
     * 廉价版计数：只用 {@link #isCapturableCheap}，不 post BreakEvent，也不把结果再转成 List。
     * 仅用于"估算数量"等不会真的删除方块的预检；保护 mod 仍然能在真正 capture 阶段拒绝。
     */
    private static int countCapturablePositionsCheap(ServerLevel level, BlockPos origin, BlockPos sizeXYZ) {
        preloadChunks(level, origin, sizeXYZ.getX(), sizeXYZ.getZ());
        Set<BlockPos> all = new HashSet<>(expectedSetCapacity(sizeXYZ));
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sizeXYZ.getX(); x++) {
            for (int y = 0; y < sizeXYZ.getY(); y++) {
                for (int z = 0; z < sizeXYZ.getZ(); z++) {
                    cur.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(cur);
                    if (!isCapturableCheap(level, cur, state)) continue;
                    BlockPos imm = cur.immutable();
                    all.add(imm);
                    IMultiblockExpander exp = MultiblockRegistry.findFor(state);
                    if (exp != null) {
                        for (BlockPos occ : exp.getOccupiedWorldPositions(level, imm, state)) {
                            BlockState occState = level.getBlockState(occ);
                            if (isCapturableCheap(level, occ, occState)) {
                                all.add(occ.immutable());
                            }
                        }
                    }
                }
            }
        }
        return all.size();
    }

    public static CapsuleTemplate captureAndRemove(ServerLevel level, BlockPos origin, BlockPos sizeXYZ,
                                                   @Nullable Player author) {
        CapsuleTemplate template = new CapsuleTemplate();
        template.setSize(sizeXYZ);
        List<BlockPos> positions = resolveCapturePositions(level, origin, sizeXYZ, author);
        readIntoTemplate(level, origin, positions, template);
        List<Entity> entities = resolveCaptureEntities(level, origin, sizeXYZ, author);
        readEntitiesIntoTemplate(level, origin, entities, template);
        removeAll(level, positions);
        removeEntities(entities);
        template.autoFitBounds();
        return template;
    }

    public static int countCapturable(Level level, BlockPos origin, int size) {
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(pos);
                    if (isCapturableCheap(level, pos, state)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    public static boolean isCapturable(BlockState state) {
        return isCapturableCheap(null, null, state);
    }

    /**
     * 完整的可捕获判定：考虑 air / bedrock / 流体源 / 黑名单 tag / Forge BreakEvent。
     *
     * <p>性能注意：保护 mod 监听 {@link BlockEvent.BreakEvent} 时这一调用会进入完整事件总线广播，
     * 因此**仅在真正要捕获方块时**调用本方法（即 {@link #captureAndRemove} 路径）。
     * 仅做"看一眼能不能捕获"用途的预检（计数、客户端预览）请改用 {@link #isCapturableCheap}。
     *
     * <p>level/pos/player 可为 null（仅做静态判断时）。
     */
    public static boolean isCapturable(@Nullable Level level, @Nullable BlockPos pos,
                                       BlockState state, @Nullable Player player) {
        if (!isCapturableCheap(level, pos, state)) return false;
        // 让外部保护 mod 通过 Forge 事件拦截
        if (level instanceof ServerLevel sl && pos != null && player instanceof ServerPlayer sp) {
            BlockEvent.BreakEvent ev = new BlockEvent.BreakEvent(sl, pos, state, sp);
            MinecraftForge.EVENT_BUS.post(ev);
            if (ev.isCanceled()) return false;
        }
        return true;
    }

    /**
     * 廉价版本的可捕获判定：只看 air / bedrock / 流体 / 黑名单 tag / shape；**不 post BreakEvent**。
     *
     * <p>专为高频路径设计：
     * <ul>
     *   <li>客户端预览渲染（每帧若 cache miss 会扫几千 cell）；</li>
     *   <li>{@link #countResolvedCapturable} 计数（network packet → 抛投/着陆/Delayed task 多处会调）；</li>
     *   <li>{@link #resolveCapturePositions} 内的预过滤（实际 capture 阶段仍会再调一次完整 isCapturable）。</li>
     * </ul>
     *
     * <p>结果可能与 {@link #isCapturable} 不一致（保护 mod 拒绝某些块）；这是设计内的：
     * 计数是"乐观估计"，最终保护 mod 决定权仍由完整版掌控，行为不变。
     */
    public static boolean isCapturableCheap(@Nullable Level level, @Nullable BlockPos pos, BlockState state) {
        // 显式三重 air 排除：理论上 state.isAir() 已涵盖 air/cave_air/void_air，
        // 但有些模组方块的 isAir() 实现会撒谎（例如某些"幻影方块"），这里再用 block 类型兜底
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) return false;
        // 形状完全为空（无碰撞、无遮挡）的方块也视为空气类，跳过收纳
        if (level != null && state.getShape(level, pos == null ? BlockPos.ZERO : pos).isEmpty()
                && state.getFluidState().isEmpty()) {
            return false;
        }
        if (block == Blocks.BEDROCK) return false;
        if (!state.getFluidState().isEmpty() && !state.getFluidState().isSource()) {
            return false;
        }
        // tag 黑名单
        if (state.is(CapsuleConfig.CAPSULE_BLACKLIST)) {
            return false;
        }
        return true;
    }

    /**
     * 计算原始立方体里所有可捕获位置（含多方块扩展），返回有序的位置集合。
     * 不修改世界。
     */
    public static List<BlockPos> resolveCapturePositions(ServerLevel level, BlockPos origin, int size,
                                                         @Nullable Player author) {
        // 预加载涉及的 chunk
        preloadChunks(level, origin, size);
        LinkedHashSet<BlockPos> all = new LinkedHashSet<>(expectedSetCapacity(new BlockPos(size, size, size)));
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    cur.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(cur);
                    if (!isCapturable(level, cur, state, author)) continue;
                    BlockPos imm = cur.immutable();
                    all.add(imm);
                    // 多方块扩展：把同结构的相邻方块也纳入
                    IMultiblockExpander exp = MultiblockRegistry.findFor(state);
                    if (exp != null) {
                        for (BlockPos occ : exp.getOccupiedWorldPositions(level, imm, state)) {
                            BlockState occState = level.getBlockState(occ);
                            if (isCapturable(level, occ, occState, author)) {
                                all.add(occ.immutable());
                            }
                        }
                    }
                }
            }
        }
        return new ArrayList<>(all);
    }

    /** 把指定 positions 内的方块/BlockEntity 序列化到 template（不修改世界）。 */
    public static void readIntoTemplate(ServerLevel level, BlockPos origin, List<BlockPos> positions,
                                        CapsuleTemplate template) {
        for (BlockPos p : positions) {
            BlockState state = level.getBlockState(p);
            if (state.isAir()) continue;
            BlockEntity be = level.getBlockEntity(p);
            CompoundTag beTag = null;
            if (be != null) {
                beTag = be.saveWithFullMetadata();
                beTag.remove("x");
                beTag.remove("y");
                beTag.remove("z");
            }
            BlockPos rel = new BlockPos(
                    p.getX() - origin.getX(),
                    p.getY() - origin.getY(),
                    p.getZ() - origin.getZ());
            template.addBlock(rel, state, beTag);
        }
    }

    public static List<Entity> resolveCaptureEntities(ServerLevel level, BlockPos origin, BlockPos sizeXYZ,
                                                      @Nullable Player author) {
        AABB box = new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + sizeXYZ.getX(), origin.getY() + sizeXYZ.getY(), origin.getZ() + sizeXYZ.getZ());
        return level.getEntities((Entity) null, box, e -> isCapturableEntity(e, author));
    }

    public static boolean isCapturableEntity(Entity entity, @Nullable Player author) {
        if (entity == null || !entity.isAlive()) return false;
        if (entity instanceof Player) return false;
        if (entity instanceof ItemEntity) return false;
        if (entity instanceof ItemFrame) return true;
        if (entity instanceof Painting) return true;
        // 矿车系列（普通矿车 / 漏斗矿车 / 箱子矿车 / 熔炉矿车 / TNT 矿车 / 命令方块矿车 / 刷怪笼矿车）
        // 都属于"摆放型"实体，按结构搬迁是合理的，统一通过 AbstractMinecart 父类放行。
        if (entity instanceof AbstractMinecart) return true;
        return false;
    }

    public static void readEntitiesIntoTemplate(ServerLevel level, BlockPos origin, BlockPos sizeXYZ,
                                                CapsuleTemplate template, @Nullable Player author) {
        readEntitiesIntoTemplate(level, origin, resolveCaptureEntities(level, origin, sizeXYZ, author), template);
    }

    private static void readEntitiesIntoTemplate(ServerLevel level, BlockPos origin, List<Entity> entities,
                                                 CapsuleTemplate template) {
        for (Entity entity : entities) {
            CompoundTag tag = new CompoundTag();
            if (!entity.save(tag)) continue;
            tag.remove("UUID");
            template.addEntity(entity.position().subtract(origin.getX(), origin.getY(), origin.getZ()), tag);
        }
    }

    public static void removeEntities(ServerLevel level, BlockPos origin, BlockPos sizeXYZ,
                                      @Nullable Player author) {
        removeEntities(resolveCaptureEntities(level, origin, sizeXYZ, author));
    }

    private static void removeEntities(List<Entity> entities) {
        for (Entity entity : entities) {
            entity.discard();
        }
    }

    /** 把指定 positions 内的方块统一移除（setBlock AIR），并抑制掉落。 */
    public static void removeAll(ServerLevel level, List<BlockPos> positions) {
        GameRules.BooleanValue tileDrops = level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS);
        boolean prevDrops = tileDrops.get();
        tileDrops.set(false, level.getServer());
        boolean prevRestoring = level.restoringBlockSnapshots;
        level.restoringBlockSnapshots = true;
        try {
            // 先把所有 BE 标记为已移除，避免 onRemove 触发 Containers.dropContents
            for (BlockPos p : positions) {
                BlockEntity be = level.getBlockEntity(p);
                if (be != null) {
                    be.setRemoved();
                }
            }
            for (BlockPos p : positions) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
            }
        } finally {
            tileDrops.set(prevDrops, level.getServer());
            level.restoringBlockSnapshots = prevRestoring;
        }
    }

    /**
     * 同步执行的完整捕获，两阶段：扫描 → 移除。
     * 该过程必须单 tick 完成，以免多方块结构被半收取。
     */
    public static CapsuleTemplate captureAndRemove(ServerLevel level, BlockPos origin, int size,
                                                   @Nullable Player author) {
        CapsuleTemplate template = new CapsuleTemplate();
        BlockPos sizeXYZ = new BlockPos(size, size, size);
        template.setSize(sizeXYZ);
        List<BlockPos> positions = resolveCapturePositions(level, origin, size, author);
        readIntoTemplate(level, origin, positions, template);
        List<Entity> entities = resolveCaptureEntities(level, origin, sizeXYZ, author);
        readEntitiesIntoTemplate(level, origin, entities, template);
        removeAll(level, positions);
        removeEntities(entities);
        template.autoFitBounds();
        return template;
    }

    /** 兼容旧调用方：不传 author。 */
    public static CapsuleTemplate captureAndRemove(ServerLevel level, BlockPos origin, int size) {
        return captureAndRemove(level, origin, size, null);
    }

    /** 预加载本次操作涉及的所有 chunk，避免漏抓。 */
    public static void preloadChunks(ServerLevel level, BlockPos origin, int size) {
        preloadChunks(level, origin, size, size);
    }

    public static void preloadChunks(ServerLevel level, BlockPos origin, int sizeX, int sizeZ) {
        int minCx = origin.getX() >> 4;
        int minCz = origin.getZ() >> 4;
        int maxCx = (origin.getX() + sizeX - 1) >> 4;
        int maxCz = (origin.getZ() + sizeZ - 1) >> 4;
        int touched = (maxCx - minCx + 1) * (maxCz - minCz + 1);
        if (touched > CapsuleConfig.MAX_CHUNKS_PER_OPERATION) return; // 安全阈值
        // 双层 for 已保证 (cx, cz) 唯一，无需额外 HashSet 去重
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                level.getChunkSource().getChunk(cx, cz, true);
            }
        }
    }

    private static int expectedSetCapacity(BlockPos sizeXYZ) {
        long volume = (long) Math.max(0, sizeXYZ.getX())
                * Math.max(0, sizeXYZ.getY())
                * Math.max(0, sizeXYZ.getZ());
        return (int) Math.min(1 << 20, Math.max(16L, volume * 2L));
    }
}
