package com.luckgoose.universalcapsule.logic;

import com.luckgoose.universalcapsule.data.CapsuleTemplate;
import com.luckgoose.universalcapsule.multiblock.IMultiblockExpander;
import com.luckgoose.universalcapsule.multiblock.MultiblockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 胶囊放置逻辑：
 * - 计算转换后的相对位置
 * - 检测障碍
 * - 真正写入世界
 *
 * 多方块兼容（A2 / A3）：
 *  - 通过 {@link MultiblockRegistry} 在写入前识别中心块；
 *  - 对 onPlace 自展开型（如天华锻座）：跳过对应边缘块的写入，交给 onPlace 自动展开；
 *  - 对 setPlacedBy 型（如余烬复刻版机器）：写入中心块后调用 expander.postPlaceCenter 让其展开。
 */
public final class CapsulePlacer {

    private CapsulePlacer() {
    }

    /** 把模板内的相对坐标按 rotation 转换为新的相对坐标。 */
    public static BlockPos transformRelative(BlockPos rel, BlockPos size, Rotation rotation) {
        int x = rel.getX();
        int y = rel.getY();
        int z = rel.getZ();
        switch (rotation) {
            case CLOCKWISE_90:
                return new BlockPos(size.getZ() - 1 - z, y, x);
            case CLOCKWISE_180:
                return new BlockPos(size.getX() - 1 - x, y, size.getZ() - 1 - z);
            case COUNTERCLOCKWISE_90:
                return new BlockPos(z, y, size.getX() - 1 - x);
            case NONE:
            default:
                return new BlockPos(x, y, z);
        }
    }

    /**
     * 把模板内的「连续坐标」(实体位置) 按 rotation 转换为新的相对坐标。
     *
     * <p>注意与 {@link #transformRelative(BlockPos, BlockPos, Rotation)} 的区别：
     * 方块用离散索引（{@code size - 1 - i}），而 Vec3 是连续值（{@code size - v}）。
     *
     * <p>这是修复「旋转结构时展示框 / 画等实体不跟随旋转」的关键：
     * 之前 {@link #place} 直接用 {@code info.pos.x/y/z} 加上 origin，完全忽略 rotation，
     * 导致旋转后的方块结构与未旋转的实体位置错位。
     */
    public static Vec3 transformRelativeVec3(Vec3 rel, BlockPos size, Rotation rotation) {
        double x = rel.x;
        double y = rel.y;
        double z = rel.z;
        switch (rotation) {
            case CLOCKWISE_90:
                return new Vec3(size.getZ() - z, y, x);
            case CLOCKWISE_180:
                return new Vec3(size.getX() - x, y, size.getZ() - z);
            case COUNTERCLOCKWISE_90:
                return new Vec3(z, y, size.getX() - x);
            case NONE:
            default:
                return new Vec3(x, y, z);
        }
    }

    /** 旋转后的世界尺寸（X/Z 可能交换）。 */
    public static BlockPos rotatedSize(BlockPos size, Rotation rotation) {
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            return new BlockPos(size.getZ(), size.getY(), size.getX());
        }
        return size;
    }

    public static boolean isReplaceable(Level level, BlockPos pos, Player player) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && !fluid.isSource()) return true;
        return state.canBeReplaced();
    }

    /** 是否被实体阻挡。会忽略掉落物 / 经验球 / 旁观者 / 玩家自身。 */
    public static boolean hasBlockingEntity(Level level, AABB area, Player player) {
        return !level.getEntities(player, area, e ->
                !e.isSpectator()
                        && e != player
                        && !(e instanceof ItemEntity)
                        && !(e instanceof ExperienceOrb)).isEmpty();
    }

    public static BlockPos findObstruction(Level level, CapsuleTemplate template,
                                           BlockPos origin, Rotation rotation, Player player) {
        BlockPos size = template.getSize();
        Set<BlockPos> checkedExpanded = new HashSet<>();
        for (CapsuleTemplate.BlockInfo info : template.getBlocks()) {
            BlockState state = template.getPaletteState(info.paletteIndex);
            if (state == null) continue;
            BlockState rotated = state.rotate(rotation);
            BlockPos rel = transformRelative(info.pos, size, rotation);
            BlockPos world = origin.offset(rel);
            if (!level.isInWorldBounds(world)) return world;
            if (!isReplaceable(level, world, player)) return world;
            IMultiblockExpander exp = MultiblockRegistry.findFor(rotated);
            if (exp == null || !exp.isCenter(rotated) || !exp.isSelfExpandingOnPlace()) continue;
            for (BlockPos occupied : exp.getOccupiedWorldPositions(level, world, rotated)) {
                if (occupied.equals(world)) continue;
                BlockPos occupiedPos = occupied.immutable();
                if (!checkedExpanded.add(occupiedPos)) continue;
                if (!level.isInWorldBounds(occupiedPos)) return occupiedPos;
                if (!isReplaceable(level, occupiedPos, player)) return occupiedPos;
            }
        }
        BlockPos rotSize = rotatedSize(size, rotation);
        AABB aabb = new AABB(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + rotSize.getX(),
                origin.getY() + rotSize.getY(),
                origin.getZ() + rotSize.getZ());
        if (hasBlockingEntity(level, aabb, player)) {
            return BlockPos.containing(aabb.getCenter());
        }
        return null;
    }

    /**
     * 真正写入世界。调用前应当确认 findObstruction 返回 null。
     *
     * 算法：
     *  第 A 步：识别 onPlace 自展开型多方块的中心位置，并把同结构的边缘位置加入跳过集合；
     *  第 B 步：先写无 NBT 方块，再写带 NBT 方块，减少 BlockEntity 同时加载压力；
     *  第 C 步：对需要 setPlacedBy 的多方块中心调用 expander.postPlaceCenter。
     */
    public static int place(ServerLevel level, CapsuleTemplate template, BlockPos origin,
                            Rotation rotation, Player author) {
        BlockPos size = template.getSize();
        Mirror mirror = Mirror.NONE;
        List<CapsuleTemplate.BlockInfo> blocks = template.getBlocks();

        // 解析每个模板方块在世界中的目标状态与位置。
        List<PlacementEntry> entries = new ArrayList<>(blocks.size());
        for (CapsuleTemplate.BlockInfo info : blocks) {
            BlockState state = template.getPaletteState(info.paletteIndex);
            if (state == null) continue;
            BlockState rotated = state.rotate(rotation).mirror(mirror);
            BlockPos rel = transformRelative(info.pos, size, rotation);
            BlockPos world = origin.offset(rel);
            entries.add(new PlacementEntry(world, rotated, info.nbt));
        }

        // 第 A 步：收集 onPlace 自展开型多方块的展开范围。
        Set<BlockPos> skipExpanded = new HashSet<>();
        List<PlacementEntry> postPlaceCenters = new ArrayList<>();
        for (PlacementEntry e : entries) {
            IMultiblockExpander exp = MultiblockRegistry.findFor(e.state);
            if (exp != null && exp.isCenter(e.state)) {
                if (exp.isSelfExpandingOnPlace()) {
                    for (BlockPos occ : exp.getOccupiedWorldPositions(level, e.pos, e.state)) {
                        if (!occ.equals(e.pos)) {
                            skipExpanded.add(occ);
                        }
                    }
                } else {
                    postPlaceCenters.add(e);
                }
            }
        }

        // 让保护 mod 通过事件拦截
        for (PlacementEntry e : entries) {
            if (skipExpanded.contains(e.pos)) continue;
            BlockEvent.EntityPlaceEvent ev = new BlockEvent.EntityPlaceEvent(
                    net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(), level, e.pos),
                    level.getBlockState(e.pos), author);
            MinecraftForge.EVENT_BUS.post(ev);
            if (ev.isCanceled()) {
                // 单点被拦截就视为整体失败
                return 0;
            }
        }

        // 第 B 步：分组写入，避免大量 BlockEntity 同时 load。
        List<PlacementEntry> simple = new ArrayList<>();
        List<PlacementEntry> withNbt = new ArrayList<>();
        for (PlacementEntry e : entries) {
            if (skipExpanded.contains(e.pos)) continue;
            if (e.nbt != null) withNbt.add(e);
            else simple.add(e);
        }
        int placed = 0;
        for (PlacementEntry e : simple) {
            level.setBlock(e.pos, e.state, Block.UPDATE_ALL);
            placed++;
        }
        for (PlacementEntry e : withNbt) {
            level.setBlock(e.pos, e.state, Block.UPDATE_ALL);
            BlockEntity be = level.getBlockEntity(e.pos);
            if (be != null) {
                CompoundTag tag = e.nbt.copy();
                tag.putInt("x", e.pos.getX());
                tag.putInt("y", e.pos.getY());
                tag.putInt("z", e.pos.getZ());
                try {
                    be.load(tag);
                    be.setChanged();
                    level.sendBlockUpdated(e.pos, e.state, e.state, Block.UPDATE_ALL);
                } catch (Exception ignored) {
                    // 单个 BlockEntity NBT 损坏时保留方块本体，避免中断整个结构放置。
                }
            }
            placed++;
        }

        // 关键：对所有实体施加与方块结构相同的 rotation。
        // 旧代码直接用 info.pos 加 origin、不调用 entity.rotate(...)，导致：
        //  - 实体相对模板的位置不被旋转 → 旋转后整个结构错位（如展示框、画偏离应在的墙面）
        //  - HangingEntity (ItemFrame / Painting) 的 direction 字段不被更新 → 朝原始墙面，
        //    但被它依附的方块已不在原墙位置 → 实体没有 supportingBlock 而立即 dropAsItem。
        for (CapsuleTemplate.EntityInfo info : template.getEntities()) {
            placeRotatedEntity(level, info, origin, size, rotation);
        }

        // 第 C 步：让 setPlacedBy 型多方块自展开。
        for (PlacementEntry e : postPlaceCenters) {
            IMultiblockExpander exp = MultiblockRegistry.findFor(e.state);
            if (exp != null) {
                try {
                    exp.postPlaceCenter(level, e.pos, e.state);
                } catch (Throwable ignored) {
                    // 兼容适配失败只影响该多方块展开，不能让整次放置回滚或崩服。
                }
            }
        }

        return placed;
    }

    private static final class PlacementEntry {
        final BlockPos pos;
        final BlockState state;
        final CompoundTag nbt;

        PlacementEntry(BlockPos pos, BlockState state, CompoundTag nbt) {
            this.pos = pos;
            this.state = state;
            this.nbt = nbt;
        }
    }

    public static void loadEntity(ServerLevel level, CompoundTag tag, double x, double y, double z) {
        Optional<Entity> entity = EntityType.create(tag, level);
        entity.ifPresent(e -> {
            e.moveTo(x, y, z, e.getYRot(), e.getXRot());
            level.addFreshEntity(e);
        });
    }

    /**
     * 真正执行单个实体放置：
     *  1) 把模板内的连续位置按 rotation 转换 → 加 origin 得到世界坐标；
     *  2) 复制 NBT，重写 {@code Pos} 列表（也清掉 {@code UUID} 避免冲突）；
     *  3) 创建实体后调用 {@link Entity#rotate(Rotation)} 让 yaw（以及 HangingEntity 的
     *     {@code direction} 字段）跟着旋转；
     *  4) 调用 {@link Entity#moveTo} 用新的 yaw 应用到位置上，让 HangingEntity 的
     *     {@code recalculateBoundingBox} 基于更新后的 direction 算出正确依附面。
     *
     * 这样做后，旋转 90° / 180° / 270° 都不会再出现展示框、画悬空或错墙的问题。
     */
    private static void placeRotatedEntity(ServerLevel level, CapsuleTemplate.EntityInfo info,
                                           BlockPos origin, BlockPos size, Rotation rotation) {
        Vec3 rotatedRel = transformRelativeVec3(info.pos, size, rotation);
        double x = origin.getX() + rotatedRel.x;
        double y = origin.getY() + rotatedRel.y;
        double z = origin.getZ() + rotatedRel.z;

        CompoundTag tag = info.nbt.copy();
        tag.remove("UUID");
        ListTag posList = new ListTag();
        posList.add(DoubleTag.valueOf(x));
        posList.add(DoubleTag.valueOf(y));
        posList.add(DoubleTag.valueOf(z));
        tag.put("Pos", posList);

        // HangingEntity 的旧 NBT 还会包含 TileX/TileY/TileZ（依附的墙方块位置）；
        // 这些字段在 entity.load(nbt) → readAdditionalSaveData 中被读取并写入 this.pos。
        // 因为我们之后会调用 moveTo(x, y, z, ...) → setPos(...) → recalculateBoundingBox()，
        // 此时 HangingEntity 会用「entity 中心位置 - direction 半厚度」反算 supportingBlock，
        // 所以旧 TileX/Y/Z 不需要预先重写——只要 yaw 与 direction 都被 rotate(...) 更新后，
        // recalculateBoundingBox 自然会把它放到新的正确位置。

        Optional<Entity> created = EntityType.create(tag, level);
        created.ifPresent(e -> {
            // 让 entity 自己处理 rotation：默认 Entity.rotate 返回新 yaw；
            // HangingEntity.rotate 会同步把内部 direction 字段旋转。
            float newYaw = e.rotate(rotation);
            e.moveTo(x, y, z, newYaw, e.getXRot());

            // HangingEntity 的位置由 BlockPos pos + direction 共同决定；moveTo 的 setPos
            // 已经触发了 recalculateBoundingBox，这里再保险地刷新一次，确保 direction
            // 在 setPos 之前/之后切换时都能正确反映。
            if (e instanceof HangingEntity hanging) {
                // 将「实体世界中心」回写到 pos 字段（取整到方块），让 recalculateBoundingBox
                // 用最新方向把贴附面推到对应墙体上
                hanging.setPos(x, y, z);
            }

            level.addFreshEntity(e);
        });
    }
}
