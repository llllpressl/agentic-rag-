package com.llll.agent.concurrency;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.llll.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 仅负责并发准入，不处理 Agent、SSE、聊天记录或运行状态。 */
@Component
@RequiredArgsConstructor
public class AgentRunGate {

    private static final String CONVERSATION_KEY_PREFIX = "agent:run-lock:";
    private static final String PERMIT_KEY_PREFIX = "agent:run-permit:";

    private final RedissonClient redissonClient;
    private final AgentProperties agentProperties;

    public AgentRunLease acquire(String userId, String conversationId, String taskId) {
        long owner = Long.parseLong(taskId);
        RLock conversationLock = lockConversation(userId, conversationId, owner);
        RLock attemptedPermit = null;
        try {
            int limit = agentProperties.getMaxConcurrentRunsPerUser();
            for (int slot = 0; slot < limit; slot++) {
                attemptedPermit = redissonClient.getLock(PERMIT_KEY_PREFIX + userId + ":" + slot);
                if (tryAcquire(attemptedPermit, owner)) {
                    return AgentRunLease.builder()
                            .conversationLock(conversationLock)
                            .permitLock(attemptedPermit)
                            .owner(owner)
                            .build();
                }
                attemptedPermit = null;
            }
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "并发任务已达上限，请稍后重试");
        } catch (RuntimeException | Error exception) {
            // 命令超时不等于 Redis 未加锁；连同本次尝试的槽位一起按 owner 补偿。
            AgentRunLease.unlock(attemptedPermit, owner);
            AgentRunLease.unlock(conversationLock, owner);
            throw exception;
        }
    }

    /** 供未来需要会话互斥、但不消耗 Agent 名额的业务使用；本次不接入删除流程。 */
    public AgentRunLease acquireConversation(String userId, String conversationId) {
        long owner = IdWorker.getId();
        RLock conversationLock = lockConversation(userId, conversationId, owner);
        return AgentRunLease.builder()
                .conversationLock(conversationLock)
                .owner(owner)
                .build();
    }

    private RLock lockConversation(String userId, String conversationId, long owner) {
        RLock conversationLock = redissonClient.getLock(CONVERSATION_KEY_PREFIX + userId + ":" + conversationId);
        boolean acquired;
        try {
            acquired = tryAcquire(conversationLock, owner);
        } catch (RuntimeException | Error exception) {
            AgentRunLease.unlock(conversationLock, owner);
            throw exception;
        }
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话正在生成，请稍后重试");
        }
        return conversationLock;
    }

    private boolean tryAcquire(RLock lock, long owner) {
        try {
            // 单次尝试，不等待空闲名额；无固定 leaseTime，由 Watchdog 续期。
            return lock.tryLockAsync(owner).toCompletableFuture().join();
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "并发控制暂不可用，请稍后重试", exception);
        }
    }
}
