package com.jotlog.storage;

/** 对象不存在：数据库有记录，但实际内容已被清理或从未写成功。 */
public class StorageNotFoundException extends StorageException {

    public StorageNotFoundException(String message) {
        super(message);
    }

    public StorageNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
