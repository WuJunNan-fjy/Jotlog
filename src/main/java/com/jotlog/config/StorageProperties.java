package com.jotlog.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储路径配置。
 *
 * 生产环境走 systemd 注入，配置文件里只给默认值。
 */
@ConfigurationProperties(prefix = "jotlog.storage")
public class StorageProperties {

    /** 附件根目录。生产环境建议 /var/lib/jotlog/attachments */
    private String attachmentDir = "./data/attachments";

    public String getAttachmentDir() {
        return attachmentDir;
    }

    public void setAttachmentDir(String attachmentDir) {
        this.attachmentDir = attachmentDir;
    }

    /** 解析成Path 用。 */
    public Path getAttachmentPath() {
        return Paths.get(attachmentDir);
    }
}
