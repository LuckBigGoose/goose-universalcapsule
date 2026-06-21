package com.luckgoose.universalcapsule.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 玩家瞄准目标点工具：用于扫描中心 / 放置 origin 计算。
 */
public final class CapsuleAim {

    /** 兜底基础射程（相当于原版 reach 的远扩展）。 */
    public static final double DEFAULT_RANGE = 16.0D;
    /** 大结构瞄准时额外加成；与参考 capsule-1.20 mod 的「18+size」相同。 */
    private static final double RANGE_BASE = 18.0D;
    /** 放置时的兜底下探距离：瞄空时向下找地面，避免结构悬空。 */
    private static final double GROUND_PROBE = 64.0D;

    private CapsuleAim() {
    }

    /** 根据结构最大边长动态扩大射程：让大结构的扫描/放置距离也足够远。 */
    public static double rangeFor(int sizeMax) {
        return Math.max(DEFAULT_RANGE, RANGE_BASE + Math.max(0, sizeMax));
    }

    public static double rangeFor(BlockPos size) {
        return rangeFor(Math.max(Math.max(size.getX(), size.getY()), size.getZ()));
    }

    public static BlockPos getCenter(Player player, int sizeForFallback) {
        BlockHitResult hit = rayTrace(player, rangeFor(sizeForFallback));
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().relative(hit.getDirection());
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double dist = Math.max(2.0D, sizeForFallback * 0.75D);
        Vec3 end = eye.add(look.scale(dist));
        return BlockPos.containing(end);
    }

    /** 地表锚点：玩家瞄准的方块（不是它前方的空气）。用于扫描底面对齐。 */
    public static BlockPos getSurfaceAnchor(Player player) {
        return getSurfaceAnchor(player, 0);
    }

    public static BlockPos getSurfaceAnchor(Player player, int sizeMax) {
        BlockHitResult hit = rayTrace(player, rangeFor(sizeMax));
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            if (hit.getDirection() == net.minecraft.core.Direction.UP) {
                return hitPos.above();
            }
            return hitPos;
        }
        // 兜底：从瞄准末端向下找最近地面，避免悬空。
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 aimEnd = eye.add(look.scale(rangeFor(sizeMax)));
        BlockPos ground = probeGroundDown(player, aimEnd);
        if (ground != null) return ground.above();
        return BlockPos.containing(aimEnd);
    }

    public static BlockPos getPlacementOrigin(Player player, BlockPos size) {
        double range = rangeFor(size);
        BlockHitResult hit = rayTrace(player, range);
        BlockPos center;
        if (hit.getType() == HitResult.Type.BLOCK) {
            // 命中：贴在命中面外侧，瞄地面 → direction=UP → center=ground.above() → 结构底贴地
            center = hit.getBlockPos().relative(hit.getDirection());
        } else {
            // 兜底：从瞄空末端向下投射找地面，避免结构悬空。
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            Vec3 aimEnd = eye.add(look.scale(range));
            BlockPos ground = probeGroundDown(player, aimEnd);
            center = ground != null ? ground.above() : BlockPos.containing(aimEnd);
        }
        int dx = size.getX() / 2;
        int dz = size.getZ() / 2;
        return center.offset(-dx, 0, -dz);
    }

    public static BlockHitResult rayTrace(Entity entity, double range) {
        Level level = entity.level();
        Vec3 eye = entity.getEyePosition();
        Vec3 look = entity.getLookAngle();
        Vec3 end = eye.add(look.scale(range));
        ClipContext ctx = new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity);
        return level.clip(ctx);
    }

    /**
     * 从某点向下做射线检测，找最近的实体方块。返回 null 表示往下 GROUND_PROBE 格内全是空气。
     * 用于"瞄向天空时把结构吸附到下方地面"的兜底逻辑。
     */
    @javax.annotation.Nullable
    private static BlockPos probeGroundDown(Entity entity, Vec3 from) {
        Vec3 to = from.subtract(0, GROUND_PROBE, 0);
        ClipContext ctx = new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity);
        BlockHitResult hit = entity.level().clip(ctx);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return null;
    }
}
