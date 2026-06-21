package com.luckgoose.universalcapsule.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自描述的胶囊结构模板。
 * 使用 BlockState 调色板 + 局部坐标 + 可选 BlockEntity NBT 的紧凑存储。
 * 完全独立于 Capsule 原模板格式，避免引入旧版的语义污染（如 occupiedPositions）。
 */
public class CapsuleTemplate {

    public static final int CURRENT_VERSION = 1;

    private static final String TAG_VERSION = "Version";
    private static final String TAG_PALETTE = "Palette";
    private static final String TAG_BLOCKS = "Blocks";
    private static final String TAG_ENTITIES = "Entities";
    private static final String TAG_SIZE_X = "SizeX";
    private static final String TAG_SIZE_Y = "SizeY";
    private static final String TAG_SIZE_Z = "SizeZ";
    private static final String TAG_DISPLAY_NAME = "DisplayName";
    private static final String TAG_STYLE = "Style";
    private static final String TAG_NUMBER = "Number";
    private static final String TAG_AUTHOR = "Author";

    private static final String TAG_BLOCK_POS = "P";
    private static final String TAG_BLOCK_STATE = "S";
    private static final String TAG_BLOCK_NBT = "N";
    private static final String TAG_ENTITY_POS = "P";
    private static final String TAG_ENTITY_NBT = "N";

    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> paletteIndex = new HashMap<>();
    private final List<BlockInfo> blocks = new ArrayList<>();
    private final List<EntityInfo> entities = new ArrayList<>();

    private BlockPos size = BlockPos.ZERO;
    private String displayName = "";
    private String style = CapsuleSealColors.PRESETS[3].hex();
    private String number = "1";
    private String author = "";

    public CapsuleTemplate() {
    }

    public BlockPos getSize() {
        return size;
    }

    public void setSize(BlockPos size) {
        this.size = size;
        // 预分配 blocks 容量：避免 5000+ 块结构在 addBlock 期间多次 ArrayList 扩容拷贝。
        // 使用最坏情况估计（sx*sy*sz），上限 32768 防止异常 size 导致一次性分配过大数组。
        // palette / entities 仍走默认容量（典型场景容量很小）。
        if (size != null) {
            long volume = (long) Math.max(0, size.getX())
                    * Math.max(0, size.getY())
                    * Math.max(0, size.getZ());
            int estimatedBlocks = (int) Math.min(32768L, volume);
            if (estimatedBlocks > 16) {
                ((ArrayList<BlockInfo>) blocks).ensureCapacity(estimatedBlocks);
            }
        }
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null ? "" : displayName;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = CapsuleSealColors.normalize(style);
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        String value = number == null ? "" : number.trim();
        if (value.isEmpty()) {
            value = "1";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length() && builder.length() < 3; i++) {
            char c = Character.toUpperCase(value.charAt(i));
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')) {
                builder.append(c);
            }
        }
        this.number = builder.isEmpty() ? "1" : builder.toString();
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author == null ? "" : author;
    }

    public List<BlockInfo> getBlocks() {
        return blocks;
    }

    public int getBlockCount() {
        return blocks.size();
    }

    public int getEntityCount() {
        return entities.size();
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && entities.isEmpty();
    }

    public List<EntityInfo> getEntities() {
        return entities;
    }

    public BlockState getPaletteState(int idx) {
        if (idx < 0 || idx >= palette.size()) {
            return null;
        }
        return palette.get(idx);
    }

    public void addBlock(BlockPos relativePos, BlockState state, @Nullable CompoundTag nbt) {
        int idx = paletteIndex.computeIfAbsent(state, s -> {
            palette.add(s);
            return palette.size() - 1;
        });
        blocks.add(new BlockInfo(relativePos.immutable(), idx, nbt == null ? null : nbt.copy()));
    }

    public void addEntity(Vec3 relativePos, CompoundTag nbt) {
        if (nbt == null || nbt.isEmpty()) return;
        entities.add(new EntityInfo(relativePos, nbt.copy()));
    }

    /**
     * 根据实际添加的方块重新计算边界 box size，而不是用扫描的原始大小。
     * 只在所有方块添加完毕后调用一次。
     */
    public void autoFitBounds() {
        if (blocks.isEmpty() && entities.isEmpty()) {
            size = BlockPos.ZERO;
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BlockInfo info : blocks) {
            BlockPos p = info.pos;
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }

        for (EntityInfo info : entities) {
            int x = (int)Math.floor(info.pos.x);
            int y = (int)Math.floor(info.pos.y);
            int z = (int)Math.floor(info.pos.z);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        // 平移所有位置，让最小坐标为 (0,0,0)
        List<BlockInfo> newBlocks = new ArrayList<>(blocks.size());
        for (BlockInfo info : blocks) {
            BlockPos newPos = new BlockPos(
                    info.pos.getX() - minX,
                    info.pos.getY() - minY,
                    info.pos.getZ() - minZ
            );
            newBlocks.add(new BlockInfo(newPos, info.paletteIndex, info.nbt));
        }
        blocks.clear();
        blocks.addAll(newBlocks);

        List<EntityInfo> newEntities = new ArrayList<>(entities.size());
        for (EntityInfo info : entities) {
            newEntities.add(new EntityInfo(info.pos.subtract(minX, minY, minZ), info.nbt));
        }
        entities.clear();
        entities.addAll(newEntities);

        // 重新设置 size
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        size = new BlockPos(sizeX, sizeY, sizeZ);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_VERSION, CURRENT_VERSION);
        tag.putInt(TAG_SIZE_X, size.getX());
        tag.putInt(TAG_SIZE_Y, size.getY());
        tag.putInt(TAG_SIZE_Z, size.getZ());
        tag.putString(TAG_DISPLAY_NAME, displayName);
        tag.putString(TAG_STYLE, style);
        tag.putString(TAG_NUMBER, number);
        tag.putString(TAG_AUTHOR, author);

        ListTag paletteList = new ListTag();
        for (BlockState state : palette) {
            paletteList.add(NbtUtils.writeBlockState(state));
        }
        tag.put(TAG_PALETTE, paletteList);

        ListTag blockList = new ListTag();
        for (BlockInfo info : blocks) {
            CompoundTag blockTag = new CompoundTag();
            blockTag.putIntArray(TAG_BLOCK_POS,
                    new int[]{info.pos.getX(), info.pos.getY(), info.pos.getZ()});
            blockTag.putInt(TAG_BLOCK_STATE, info.paletteIndex);
            if (info.nbt != null) {
                blockTag.put(TAG_BLOCK_NBT, info.nbt);
            }
            blockList.add(blockTag);
        }
        tag.put(TAG_BLOCKS, blockList);

        ListTag entityList = new ListTag();
        for (EntityInfo info : entities) {
            CompoundTag entityTag = new CompoundTag();
            entityTag.putIntArray(TAG_ENTITY_POS, new int[]{
                    (int)Math.floor(info.pos.x * 1024.0D),
                    (int)Math.floor(info.pos.y * 1024.0D),
                    (int)Math.floor(info.pos.z * 1024.0D)});
            entityTag.put(TAG_ENTITY_NBT, info.nbt);
            entityList.add(entityTag);
        }
        tag.put(TAG_ENTITIES, entityList);

        return tag;
    }

    public static CapsuleTemplate load(CompoundTag tag) {
        // 版本兼容：当前只识别 v1，更高版本将来通过 migration path 处理
        int version = tag.contains(TAG_VERSION) ? tag.getInt(TAG_VERSION) : 1;
        CapsuleTemplate template = new CapsuleTemplate();
        template.setSize(new BlockPos(
                tag.getInt(TAG_SIZE_X),
                tag.getInt(TAG_SIZE_Y),
                tag.getInt(TAG_SIZE_Z)));
        template.setDisplayName(tag.getString(TAG_DISPLAY_NAME));
        template.setStyle(tag.getString(TAG_STYLE));
        template.setNumber(tag.getString(TAG_NUMBER));
        template.setAuthor(tag.getString(TAG_AUTHOR));

        ListTag paletteList = tag.getList(TAG_PALETTE, Tag.TAG_COMPOUND);
        for (int i = 0; i < paletteList.size(); i++) {
            BlockState state = NbtUtils.readBlockState(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                    paletteList.getCompound(i));
            template.palette.add(state);
            template.paletteIndex.put(state, template.palette.size() - 1);
        }

        ListTag blockList = tag.getList(TAG_BLOCKS, Tag.TAG_COMPOUND);
        // 预分配 blocks 容量：5000+ 模板加载时避免 ArrayList 扩容拷贝
        ((ArrayList<BlockInfo>) template.blocks).ensureCapacity(blockList.size());
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockTag = blockList.getCompound(i);
            int[] arr = blockTag.getIntArray(TAG_BLOCK_POS);
            BlockPos pos;
            if (arr.length >= 3) {
                pos = new BlockPos(arr[0], arr[1], arr[2]);
            } else {
                pos = BlockPos.ZERO;
            }
            int paletteIdx = blockTag.getInt(TAG_BLOCK_STATE);
            CompoundTag nbt = blockTag.contains(TAG_BLOCK_NBT, Tag.TAG_COMPOUND)
                    ? blockTag.getCompound(TAG_BLOCK_NBT) : null;
            template.blocks.add(new BlockInfo(pos, paletteIdx, nbt));
        }

        ListTag entityList = tag.getList(TAG_ENTITIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < entityList.size(); i++) {
            CompoundTag entityTag = entityList.getCompound(i);
            int[] arr = entityTag.getIntArray(TAG_ENTITY_POS);
            Vec3 pos = Vec3.ZERO;
            if (arr.length >= 3) {
                pos = new Vec3(arr[0] / 1024.0D, arr[1] / 1024.0D, arr[2] / 1024.0D);
            }
            CompoundTag nbt = entityTag.contains(TAG_ENTITY_NBT, Tag.TAG_COMPOUND)
                    ? entityTag.getCompound(TAG_ENTITY_NBT) : new CompoundTag();
            template.entities.add(new EntityInfo(pos, nbt));
        }

        return template;
    }

    public static final class BlockInfo {
        public final BlockPos pos;
        public final int paletteIndex;
        @Nullable
        public final CompoundTag nbt;

        public BlockInfo(BlockPos pos, int paletteIndex, @Nullable CompoundTag nbt) {
            this.pos = pos;
            this.paletteIndex = paletteIndex;
            this.nbt = nbt;
        }
    }

    public static final class EntityInfo {
        public final Vec3 pos;
        public final CompoundTag nbt;

        public EntityInfo(Vec3 pos, CompoundTag nbt) {
            this.pos = pos;
            this.nbt = nbt;
        }
    }
}
