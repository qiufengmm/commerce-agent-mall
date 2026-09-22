package com.macro.mall.search.exception;

/**
 * 全量导入并发冲突异常
 * 同一进程内已有一个importAll正在执行时抛出，用于向调用方返回明确的执行中状态，而不是悄悄跳过
 */
public class ImportAllConflictException extends RuntimeException {
    public ImportAllConflictException(String message) {
        super(message);
    }
}
