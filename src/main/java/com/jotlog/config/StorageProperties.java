package com.jotlog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 附件存储配置。
 *
 * {@code type=local}（默认）写本机磁盘；{@code type=oss} 写阿里云 OSS。
 * 真值生产环境走 systemd 注入环境变量，本地走 config/application-local.yml。
 */
@ConfigurationProperties(prefix = "jotlog.storage")
public class StorageProperties {

    /** local=本机磁盘（默认）；oss=阿里云 OSS */
    private String type = "local";

    /** 本机磁盘模式的附件根目录。生产环境建议 /var/lib/jotlog/attachments */
    private String attachmentDir = "./data/attachments";

    /** 阿里云 OSS 配置（type=oss 时必须配齐，否则启动 fail fast） */
    private Oss oss = new Oss();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAttachmentDir() {
        return attachmentDir;
    }

    public void setAttachmentDir(String attachmentDir) {
        this.attachmentDir = attachmentDir;
    }

    public Oss getOss() {
        return oss;
    }

    public void setOss(Oss oss) {
        this.oss = oss;
    }

    /** 解析成 Path 用（仅 local 后端使用）。 */
    public Path getAttachmentPath() {
        return Paths.get(attachmentDir);
    }

    public static class Oss {
        private String endpoint;
        private String accessKeyId;
        private String accessKeySecret;
        private String bucketName;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getAccessKeySecret() {
            return accessKeySecret;
        }

        public void setAccessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
        }

        public String getBucketName() {
            return bucketName;
        }

        public void setBucketName(String bucketName) {
            this.bucketName = bucketName;
        }
    }
}
