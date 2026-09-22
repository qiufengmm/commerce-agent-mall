package com.macro.mall.search.lock;

import com.macro.mall.search.exception.ImportAllConflictException;

/**
 * importAll 无法安全取得或继续持有分布式锁时抛出。
 */
public class ImportAllLockException extends ImportAllConflictException {
    public ImportAllLockException(String message) {
        super(message);
    }

    public ImportAllLockException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
