package com.macro.mall.search.lock;

/**
 * importAll 跨实例互斥服务。
 */
public interface ImportAllLockService {
    ImportAllLock tryAcquire();

    static ImportAllLockService disabled() {
        return () -> ImportAllLock.noop();
    }
}
