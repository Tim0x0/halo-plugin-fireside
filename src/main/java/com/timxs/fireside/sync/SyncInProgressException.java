package com.timxs.fireside.sync;

/**
 * 已有同步任务持锁时的互斥信号。独立类型是为了与其它 IllegalStateException
 * （如 reactor block 超时）区分：调度器对它跳过本轮且不推进计时，端点把它映射为 409。
 */
public class SyncInProgressException extends RuntimeException {

    public SyncInProgressException(String message) {
        super(message);
    }
}
