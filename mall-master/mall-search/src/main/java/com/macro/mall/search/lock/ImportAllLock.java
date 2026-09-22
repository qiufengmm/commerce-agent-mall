package com.macro.mall.search.lock;

/**
 * importAll 锁句柄，句柄失效后禁止继续清理陈旧文档。
 */
public interface ImportAllLock extends AutoCloseable {
    boolean isHeld();

    @Override
    void close();

    static ImportAllLock noop() {
        return new ImportAllLock() {
            @Override
            public boolean isHeld() {
                return true;
            }

            @Override
            public void close() {
                // 单实例显式关闭分布式锁时无需释放远端资源
            }
        };
    }
}
