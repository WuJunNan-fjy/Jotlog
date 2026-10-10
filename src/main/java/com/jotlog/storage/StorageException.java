package com.jotlog.storage;

/** 存储读写失败。unchecked：附件失败不应该强迫业务链路声明，由调用点决定是否吞掉。 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
