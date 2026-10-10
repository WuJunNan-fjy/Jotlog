package com.jotlog.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.jotlog.config.StorageProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 阿里云 OSS 存储。配置 {@code jotlog.storage.type=oss} 时启用。
 *
 * 与参考项目 Junan 的两个刻意差异：
 *   1. OSSClient 线程安全且内部带连接池，这里随服务构造一次、{@link PreDestroy} 关闭，
 *      不做"每次上传 new 一个再 shutdown"；
 *   2. 不按扩展名分目录 —— 本项目已用 sha256 内容寻址，key = {sha前2位}/{sha}。
 *
 * 凭证缺失时 fail fast 直接让应用启动失败，而不是静默回退（配置错误应当尽早暴露）。
 */
@Service
@ConditionalOnProperty(prefix = "jotlog.storage", name = "type", havingValue = "oss")
public class OssStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(OssStorageService.class);

    private final String bucket;
    private final OSS ossClient;

    public OssStorageService(StorageProperties properties) {
        StorageProperties.Oss cfg = properties.getOss();
        if (cfg == null
                || blank(cfg.getEndpoint())
                || blank(cfg.getAccessKeyId())
                || blank(cfg.getAccessKeySecret())
                || blank(cfg.getBucketName())) {
            throw new IllegalStateException(
                    "jotlog.storage.type=oss 但 OSS 配置不完整。请在 config/application-local.yml "
                            + "或环境变量中配齐 jotlog.storage.oss 的 endpoint/access-key-id/"
                            + "access-key-secret/bucket-name。");
        }
        this.bucket = cfg.getBucketName().trim();
        this.ossClient = new OSSClientBuilder().build(
                cfg.getEndpoint().trim(),
                cfg.getAccessKeyId().trim(),
                cfg.getAccessKeySecret().trim());
        log.info("附件存储后端：阿里云 OSS bucket={} endpoint={}", bucket, cfg.getEndpoint());
    }

    @Override
    public String backend() {
        return "oss";
    }

    @Override
    public StoredObject store(byte[] bytes, String filename, String mime, String sha256) {
        String key = keyOf(sha256);
        try {
            // 同 sha 已存在则不重复上传（对象就是同一份内容，覆盖无意义）
            if (!ossClient.doesObjectExist(bucket, key)) {
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(bytes.length);
                if (!blank(mime)) {
                    metadata.setContentType(mime);
                }
                ossClient.putObject(bucket, key, new ByteArrayInputStream(bytes), metadata);
            }
            return new StoredObject(key, bytes.length);
        } catch (com.aliyun.oss.OSSException oe) {
            log.warn("OSS 上传被拒绝 key={} code={} requestId={} hostId={}",
                    key, oe.getErrorCode(), oe.getRequestId(), oe.getHostId());
            throw new StorageException("附件上传 OSS 失败：" + filename, oe);
        } catch (ClientException ce) {
            throw new StorageException("附件上传 OSS 失败（客户端错误）：" + filename, ce);
        }
    }

    @Override
    public InputStream open(StoredObject object) {
        try {
            OSSObject obj = ossClient.getObject(bucket, object.key());
            // 关闭这个流即归还 HTTP 连接；Spring 写完响应后会关闭 InputStreamResource 的流
            return obj.getObjectContent();
        } catch (com.aliyun.oss.OSSException oe) {
            if ("NoSuchKey".equals(oe.getErrorCode())) {
                throw new StorageNotFoundException("OSS 对象不存在：" + object.key());
            }
            log.warn("OSS 下载被拒绝 key={} code={} requestId={}",
                    object.key(), oe.getErrorCode(), oe.getRequestId());
            throw new StorageException("附件从 OSS 读取失败：" + object.key(), oe);
        } catch (ClientException ce) {
            throw new StorageException("附件从 OSS 读取失败（客户端错误）：" + object.key(), ce);
        }
    }

    @PreDestroy
    public void shutdown() {
        ossClient.shutdown();
    }

    private static String keyOf(String sha256) {
        return sha256.substring(0, 2) + "/" + sha256;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
