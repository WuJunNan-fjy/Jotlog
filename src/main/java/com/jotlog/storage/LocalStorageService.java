package com.jotlog.storage;

import com.jotlog.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本机磁盘存储。默认实现，没配 OSS 时系统行为与改造前完全一致。
 *
 * 两级分目录：{@code 存储根/{sha前2位}/{sha}}，同 sha 文件只写一次。
 */
@Service
@ConditionalOnProperty(prefix = "jotlog.storage", name = "type",
        havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path root;

    /** 合法对象 key：两位十六进制前缀 + / + 64 位 sha。列举和删除都靠它做形状校验。 */
    private static final Pattern KEY_SHAPE = Pattern.compile("[0-9a-f]{2}/[0-9a-f]{64}");

    public LocalStorageService(StorageProperties properties) {
        this.root = properties.getAttachmentPath().toAbsolutePath().normalize();
        log.info("附件存储后端：本机磁盘 {}", root);
    }

    @Override
    public String backend() {
        return "local";
    }

    @Override
    public StoredObject store(byte[] bytes, String filename, String mime, String sha256) {
        try {
            Path target = resolve(sha256);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Files.write(target, bytes);
            }
            return new StoredObject(keyOf(sha256), bytes.length);
        } catch (Exception e) {
            throw new StorageException("附件写盘失败：" + filename, e);
        }
    }

    @Override
    public StoredObject storeFile(Path source, long size, String filename, String mime, String sha256) {
        try {
            Path target = resolve(sha256);
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                try {
                    // 临时目录与附件目录通常同盘，move 是原子的且零拷贝
                    Files.move(source, target);
                } catch (IOException moveFailed) {
                    // 跨挂载点（如 PrivateTmp 与 /var/lib 不同设备）move 会失败，退化为复制；
                    // move 也可能已复制成功但删源失败，此时 target 已存在，别再覆盖
                    if (!Files.exists(target)) {
                        Files.copy(source, target);
                    }
                }
            }
            return new StoredObject(keyOf(sha256), size);
        } catch (Exception e) {
            throw new StorageException("大附件写盘失败：" + filename, e);
        }
    }

    @Override
    public InputStream open(StoredObject object) {
        Path file = root.resolve(object.key()).toAbsolutePath().normalize();
        // 路径穿越防御：DB 被污染后也不能借 key 读出存储根之外的文件
        if (!file.startsWith(root)) {
            throw new StorageException("非法的存储位置：" + object.key());
        }
        if (!Files.isRegularFile(file)) {
            throw new StorageNotFoundException("附件文件不存在：" + object.key());
        }
        try {
            return Files.newInputStream(file);
        } catch (Exception e) {
            throw new StorageException("附件读取失败：" + object.key(), e);
        }
    }

    /** sha → 落盘路径。 */
    private Path resolve(String sha256) {
        return root.resolve(keyOf(sha256));
    }

    @Override
    public List<String> listKeys() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root, 2)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(p -> root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                    .filter(k -> KEY_SHAPE.matcher(k).matches())
                    .toList();
        } catch (Exception e) {
            throw new StorageException("枚举附件目录失败：" + root, e);
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || !KEY_SHAPE.matcher(key).matches()) {
            throw new StorageException("非法的存储 key：" + key);
        }
        Path file = root.resolve(key).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new StorageException("非法的存储位置：" + key);
        }
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                // 顺手删掉空掉的两位前缀目录；目录非空会抛 DirectoryNotEmptyException，忽略即可
                try {
                    Files.deleteIfExists(file.getParent());
                } catch (DirectoryNotEmptyException ignored) {
                    // 还有别的附件，目录保留
                }
            }
        } catch (Exception e) {
            throw new StorageException("附件删除失败：" + key, e);
        }
    }

    private static String keyOf(String sha256) {
        return sha256.substring(0, 2) + "/" + sha256;
    }
}
