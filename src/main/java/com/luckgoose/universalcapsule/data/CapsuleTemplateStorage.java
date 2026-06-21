package com.luckgoose.universalcapsule.data;

import com.luckgoose.universalcapsule.CapsuleConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 胶囊模板文件存储。
 * 路径：<config>/goose/goose-universalcapsule/capsules/<id>.nbt
 * 与原 capsule 模组类似，把保存内容放在配置文件夹下。
 */
public final class CapsuleTemplateStorage {

    private static final String FILE_EXT = ".nbt";
    /** 内部临时模板（捕获后即时写盘）的 ID 前缀。listAll 会跳过这些条目。 */
    public static final String TEMP_ID_PREFIX = "tmp_";
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9_-]{1,64}");

    private static final ConcurrentHashMap<String, CapsuleTemplate> CACHE = new ConcurrentHashMap<>();

    /* ============ 目录元信息缓存 ============
     * 旧实现：每次 listAll() 都全量扫目录 + 解压每个 .nbt 文件。
     *        工作台 GUI 每次打开 / save / delete 都触发一次 listAll → 几十~几百 ms 阻塞 server tick。
     *
     * 新实现：在第一次 listAll() 时从磁盘扫一次填充本索引缓存，之后所有 listAll() 直接返回缓存视图；
     *        save / delete 增量更新缓存，不再全量重扫。
     *
     * 注意：
     *  - 仅缓存正式模板（非 tmp_xxx），与 listAll 过滤策略一致；
     *  - tmp_xxx 不进缓存（生命周期短暂、由 sweepStaleTempFiles 处理）；
     *  - 启动早期（indexLoaded=false）才触发一次磁盘扫描；
     *  - 任何外部直接修改目录（手工放文件）需要调用 invalidateIndex() 重新扫描。
     */
    private static final ConcurrentHashMap<String, TemplateEntry> INDEX_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean indexLoaded = false;

    private CapsuleTemplateStorage() {
    }

    /**
     * 强制下次 listAll() 重新扫盘。
     * 仅用于外部手工放文件后想要立即生效的场景；正常 save / delete 已自动维护索引。
     */
    public static void invalidateIndex() {
        indexLoaded = false;
        INDEX_CACHE.clear();
    }

    public static Path getDirectory() {
        Path dir = FMLPaths.CONFIGDIR.get()
                .resolve("goose")
                .resolve("goose-universalcapsule")
                .resolve(CapsuleConstants.CAPSULE_DIR_NAME);
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            // 创建失败时仍返回目标路径；后续 save/list 会失败或重试，避免在工具方法里抛出硬错误。
        }
        return dir;
    }

    public static String generateId() {
        return "capsule_" + Long.toUnsignedString(System.currentTimeMillis(), 36);
    }

    /**
     * 为捕获后的临时模板生成 ID。物品 NBT 只保存这个 ID（很小），
     * 完整模板数据落在磁盘 tmp_xxx.nbt 中，避免 ItemStack 携带巨大 NBT 导致渲染卡顿。
     */
    public static String generateTempId() {
        return TEMP_ID_PREFIX + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static boolean isTempId(String id) {
        return id != null && id.startsWith(TEMP_ID_PREFIX);
    }

    @Nullable
    public static Path getFile(String id) {
        if (!isValidId(id)) {
            return null;
        }
        Path dir = getDirectory().normalize();
        Path path = dir.resolve(id + FILE_EXT).normalize();
        return path.startsWith(dir) ? path : null;
    }

    public static boolean isValidId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    public static String normalizeId(String id) {
        String value = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length() && builder.length() < 64; i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    public static boolean exists(String id) {
        Path path = getFile(id);
        return path != null && Files.exists(path);
    }

    @Nullable
    public static CapsuleTemplate load(String id) {
        if (!isValidId(id)) {
            return null;
        }
        CapsuleTemplate cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        Path path = getFile(id);
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(path.toFile());
            CapsuleTemplate template = CapsuleTemplate.load(tag);
            CACHE.put(id, template);
            return template;
        } catch (IOException e) {
            // 模板文件不可读或损坏时按“不存在”处理，调用方负责给玩家反馈。
            return null;
        }
    }

    public static boolean save(String id, CapsuleTemplate template) {
        if (template == null) {
            return false;
        }
        Path path = getFile(id);
        if (path == null) {
            return false;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            NbtIo.writeCompressed(template.save(), path.toFile());
            CACHE.put(id, template);
            // 增量维护索引：仅正式模板（非 tmp_）才进 INDEX_CACHE，
            // 这样下次 listAll 直接读缓存而不再全量扫盘。
            if (!isTempId(id)) {
                INDEX_CACHE.put(id, toEntry(id, template));
            }
            return true;
        } catch (IOException e) {
            // 保存失败统一返回 false；命令/交互层会给出目录权限提示。
            return false;
        }
    }

    public static boolean delete(String id) {
        Path path = getFile(id);
        if (path == null) {
            return false;
        }
        try {
            Files.deleteIfExists(path);
            CACHE.remove(id);
            INDEX_CACHE.remove(id);
            return true;
        } catch (IOException e) {
            // 删除失败不修改缓存状态，避免内存索引与磁盘继续偏离。
            return false;
        }
    }

    /**
     * 把内存中的 {@link CapsuleTemplate} 转成 {@link TemplateEntry}。
     * 复用 {@link CapsuleTemplate#getBlockCount()} 等已有 getter，
     * 避免索引视图与运行时视图出现字段不一致。
     */
    private static TemplateEntry toEntry(String id, CapsuleTemplate template) {
        return new TemplateEntry(
                id,
                template.getDisplayName(),
                template.getStyle(),
                template.getNumber(),
                template.getAuthor(),
                template.getSize().getX(),
                template.getSize().getY(),
                template.getSize().getZ(),
                template.getBlockCount());
    }

    public static void invalidate(String id) {
        if (!isValidId(id)) {
            return;
        }
        CACHE.remove(id);
    }

    public static void invalidateAll() {
        CACHE.clear();
        invalidateIndex();
    }

    /**
     * 清扫过期的 tmp_xxx.nbt 孤儿文件。
     *
     * 玩家死亡掉胶囊、扔垃圾桶、被销毁的 UNNAMED 胶囊，磁盘上的 tmp 文件会留下来，
     * 时间久了占空间。这里在每次服务器启动时调用一次，把超过 maxAgeMillis 没有动过
     * 的 tmp 文件全部删除。注意：阈值要远大于"单次游戏会话长度"，否则会误删玩家
     * 还在用的胶囊；默认 7 天足够稳妥。
     *
     * @return 删除的文件数
     */
    public static int sweepStaleTempFiles(long maxAgeMillis) {
        Path dir = getDirectory();
        if (!Files.exists(dir)) return 0;
        long threshold = System.currentTimeMillis() - maxAgeMillis;
        int[] deleted = {0};
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(TEMP_ID_PREFIX) && n.endsWith(FILE_EXT);
            }).forEach(p -> {
                try {
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (mtime < threshold) {
                        Files.deleteIfExists(p);
                        String fname = p.getFileName().toString();
                        String id = fname.substring(0, fname.length() - FILE_EXT.length());
                        CACHE.remove(id);
                        deleted[0]++;
                    }
                } catch (IOException e) {
                    // 单个 tmp 文件不可读/不可删时跳过，避免启动清扫影响服务器启动。
                }
            });
        } catch (IOException e) {
            // 目录不可读时放弃本轮清扫，下次服务器启动会再次尝试。
        }
        return deleted[0];
    }

    public static List<TemplateEntry> listAll() {
        // 第一次调用时一次性扫盘填充索引；后续直接读 INDEX_CACHE 视图。
        ensureIndexLoaded();
        return new ArrayList<>(INDEX_CACHE.values());
    }

    /**
     * 第一次访问索引或显式 invalidateIndex 后，从磁盘扫盘填充。
     * 双重检查锁保证只扫一次（即使被多个线程同时触发）。
     */
    private static void ensureIndexLoaded() {
        if (indexLoaded) return;
        synchronized (INDEX_CACHE) {
            if (indexLoaded) return;
            INDEX_CACHE.clear();
            Path dir = getDirectory();
            boolean loaded = false;
            if (Files.exists(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(FILE_EXT))
                            .filter(p -> !p.getFileName().toString().startsWith(TEMP_ID_PREFIX))
                            .forEach(p -> {
                                String fileName = p.getFileName().toString();
                                String id = fileName.substring(0, fileName.length() - FILE_EXT.length());
                                try {
                                    CompoundTag tag = NbtIo.readCompressed(p.toFile());
                                    String name = tag.getString("DisplayName");
                                    String style = CapsuleSealColors.normalize(tag.getString("Style"));
                                    String number = tag.getString("Number");
                                    String author = tag.getString("Author");
                                    int sizeX = tag.getInt("SizeX");
                                    int sizeY = tag.getInt("SizeY");
                                    int sizeZ = tag.getInt("SizeZ");
                                    int blockCount = tag.getList("Blocks", 10).size();
                                    INDEX_CACHE.put(id, new TemplateEntry(id, name, style, number, author,
                                            sizeX, sizeY, sizeZ, blockCount));
                                } catch (IOException e) {
                                    // 单个模板损坏只跳过该文件，避免整个列表不可用。
                                }
                            });
                    loaded = true;
                } catch (IOException e) {
                    // 目录暂时不可读时不标记为已加载，下一次 listAll 仍会重试。
                }
            } else {
                // 目录不存在且无法创建时保持未加载，避免把临时 I/O 故障固化为空索引。
                loaded = false;
            }
            indexLoaded = loaded;
        }
    }

    public static final class TemplateEntry {
        public final String id;
        public final String displayName;
        public final String style;
        public final String number;
        public final String author;
        public final int sizeX;
        public final int sizeY;
        public final int sizeZ;
        public final int blockCount;

        public TemplateEntry(String id, String displayName, String style, String number, String author,
                             int sizeX, int sizeY, int sizeZ, int blockCount) {
            this.id = id;
            this.displayName = displayName == null ? "" : displayName;
            this.style = CapsuleSealColors.normalize(style);
            this.number = number == null || number.isEmpty() ? "1" : number;
            this.author = author == null ? "" : author;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.blockCount = blockCount;
        }
    }
}
