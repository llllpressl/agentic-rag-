package com.llll.agent.concurrency;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;

import java.util.concurrent.CompletionException;

/** 一轮任务持有的锁及释放责任；不依赖获取锁和释放锁的 Java 线程。 */
@Slf4j
public class AgentRunLease {

    private final RLock conversationLock;
    private final RLock permitLock;
    private final long owner;

    private boolean transferred;
    private boolean released;
    private boolean releaseSucceeded;

    @Builder
    private AgentRunLease(RLock conversationLock, RLock permitLock, long owner) {
        this.conversationLock = conversationLock;
        this.permitLock = permitLock;
        this.owner = owner;
    }

    /** 在启动异步订阅前移交；此后 Service 不再关闭 Agent 或释放锁。 */
    public synchronized void transferToRunHandle() {
        if (transferred || released) {
            throw new IllegalStateException("Agent 锁资源已经移交或释放");
        }
        transferred = true;
    }

    public synchronized boolean isTransferred() {
        return transferred;
    }

    /** 同步等待两把锁处理完毕；重复释放等待首次释放完成，并返回相同结果。 */
    public synchronized boolean release() {
        if (released) {
            return releaseSucceeded;
        }
        released = true;
        boolean permitReleased = unlock(permitLock, owner);
        boolean conversationReleased = unlock(conversationLock, owner);
        releaseSucceeded = permitReleased && conversationReleased;
        return releaseSucceeded;
    }

    /** 也用于加锁响应异常时的补偿；仅按本轮 owner 解锁，绝不强制删除 key。 */
    static boolean unlock(RLock lock, long owner) {
        if (lock == null) {
            return true;
        }
        try {
            lock.unlockAsync(owner).toCompletableFuture().join();
            return true;
        } catch (RuntimeException | Error exception) {
            Throwable cause = exception;
            if (exception instanceof CompletionException && exception.getCause() != null) {
                cause = exception.getCause();
            }
            if (cause instanceof IllegalMonitorStateException) {
                log.warn("Agent 锁已过期或不再属于本轮任务，key={}, owner={}", lock.getName(), owner);
            } else {
                // 当前版本 Redisson 异步解锁失败时也撤销对应续期；残留锁等待 TTL 过期。
                log.error("Agent 锁释放失败，等待过期恢复，key={}, owner={}", lock.getName(), owner, exception);
            }
            return false;
        }
    }
}
