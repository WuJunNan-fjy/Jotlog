package com.jotlog.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

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
     * 以流式方式存储一个已落在本地临时文件的大附件，内容不读进堆。
     * 同 sha 已存在时不重复写入；无论写入与否，临时文件都由调用方负责删除。
     *
     * 供飞书 ≥100MB 附件的 Range 分片下载链路使用：OSS 后端走分片上传，
     * local 后端直接搬运文件。
     *
     * @param source 本地临时文件（绝对路径）
     * @param size   文件字节数，用于 Content-Length / OSS 分片元数据
     */
    StoredObject storeFile(Path source, long size, String filename, String mime, String sha256);

    /**
     * 按 key 打开附件内容流。调用方负责关闭。
     *
     * @throws StorageNotFoundException 对象不存在（DB 有记录但内容已丢失）
     * @throws StorageException         读取失败
     */
    InputStream open(StoredObject object);

    /**
     * 列举存储后端里的全部对象 key（形如 {@code 1a/1a2b...}）。
     * 只给附件垃圾回收用：与 attachments 表的 storage_path 对账，找出孤儿对象。
     */
    List<String> listKeys();

    /**
     * 删除一个对象。供垃圾回收调用，业务路径不允许直接删 —— 内容寻址对象
     * 可能被多条记录共享，只有确认全库没有任何 attachments 行引用时才能删。
     *
     * @throws StorageException key 非法或删除失败
     */
    void delete(String key);

    /** 存储对象。key 是数据库里 storage_path 的语义；size 用于 Content-Length。 */
    record StoredObject(String key, long size) {
    }
}
