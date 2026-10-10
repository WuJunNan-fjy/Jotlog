package com.jotlog.storage;

import com.jotlog.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static String keyOf(String sha256) {
        return sha256.substring(0, 2) + "/" + sha256;
    }
}
