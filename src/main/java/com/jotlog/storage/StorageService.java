package com.jotlog.storage;

import java.io.InputStream;

/**
 * 附件存储的唯一门面。
 *
 * 调用方只认这个接口，不感知附件是躺在本机磁盘还是阿里云 OSS ——
 * 切换后端只改配置 {@code jotlog.storage.type=local|oss}，业务代码零改动。
 *
 * 对象寻址沿用 sha256 内容寻址：key = {@code {sha前2位}/{sha}}，
 * 文件名只存数据库，不进 key（避开中文/特殊字符问题，且同内容天然去重）。
 */
public interface StorageService {

    /** 后端标识（local / oss），启动日志用。 */
    String backend();

    /**
     * 存储附件。同 sha 已存在时不重复写入。
     *
     * @param bytes    附件内容
     * @param filename 原始文件名（只用于设置元数据，不参与 key）
     * @param mime     MIME 类型，写入 Content-Type，浏览器才能正确预览
     * @param sha256   内容的 sha256（64 位十六进制）
     * @return 已存储对象（key 落库到 attachments.storage_path）
     */
    StoredObject store(byte[] bytes, String filename, String mime, String sha256);

    /**
     * 按 key 打开附件内容流。调用方负责关闭。
     *
     * @throws StorageNotFoundException 对象不存在（DB 有记录但内容已丢失）
     * @throws StorageException         读取失败
     */
    InputStream open(StoredObject object);

    /** 存储对象。key 是数据库里 storage_path 的语义；size 用于 Content-Length。 */
    record StoredObject(String key, long size) {
    }
}
