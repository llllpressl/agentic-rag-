package com.llll.rag.knowledge.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * 同一个 KB 的上传、Chunk 保存、ES 写入和删除共用 PG 会话锁，支持多实例。
 * 锁连接不参与业务事务，因此 PG 逻辑删除提交后，仍可持锁完成外部清理。
 * 未获得锁时直接失败，避免等待者占满连接池；调用方或 MQ 可稍后重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseOperationLock {

    private final DataSource dataSource;

    public <T> T execute(String kbId, Supplier<T> operation) {
        if (!StringUtils.hasText(kbId)) {
            throw new IllegalArgumentException("kbId 不能为空");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("知识库互斥必须在业务事务开始前获取");
        }

        String lockKey = "knowledge-base:" + kbId;
        try (Connection connection = dataSource.getConnection()) {
            boolean locked;
            try {
                locked = tryLock(connection, lockKey);
            } catch (SQLException exception) {
                // SQL 超时也可能意味着 PG 已加锁但响应丢失，不能把未知状态的会话归还连接池。
                abortConnection(connection, exception);
                throw exception;
            }
            if (!locked) {
                throw new IllegalStateException("知识库正在上传、同步或删除，请稍后重试");
            }
            try {
                return operation.get();
            } finally {
                unlock(connection, lockKey);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("知识库互斥连接异常，请稍后重试", exception);
        }
    }

    private boolean tryLock(Connection connection, String lockKey) throws SQLException {
        String sql = "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lockKey);
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection, String lockKey) throws SQLException {
        String sql = "SELECT pg_advisory_unlock(hashtextextended(?, 0))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, lockKey);
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new SQLException("知识库会话锁释放失败");
                }
            }
        } catch (SQLException exception) {
            // 会话锁不能随连接归还到池中；解锁失败时终止底层连接，PG 会释放该会话的锁。
            log.error("释放知识库会话锁失败，终止连接，lockKey={}", lockKey, exception);
            abortConnection(connection, exception);
            throw exception;
        }
    }

    private void abortConnection(Connection connection, SQLException exception) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException abortException) {
            exception.addSuppressed(abortException);
        }
    }
}
