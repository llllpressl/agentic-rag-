package com.llll.rag.search.routing;

import com.llll.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.llll.rag.search.exception.RetrievalUnavailableException;
import com.llll.rag.search.routing.model.KbScore;
import com.llll.rag.search.routing.model.KnowledgeBaseMeta;
import com.llll.rag.search.routing.model.RetrievalScope;
import com.llll.rag.search.routing.model.RouteDecision;
import com.llll.rag.search.routing.model.RouteMode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseRouter {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final JevClient jevClient;

    private final JevProperties jevProperties;

    private final RagRoutingProperties routingProperties;

    @PostConstruct
    public void checkCredentials() {
        if (routingProperties.isEnabled() && jevProperties.isEnabled()
                && !StringUtils.hasText(jevProperties.getApiKey())) {
            log.warn("知识库路由已开启但 JEV_API_KEY 未配置，请求将降级到可用知识库全局检索");
        }
    }

    public RouteDecision route(String query) {
        requireNotInterrupted();
        if (!routingProperties.isEnabled() || !jevProperties.isEnabled()) {
            RetrievalScope originalScope = RetrievalScope.builder()
                    .unrestricted(true)
                    .kbIds(List.of())
                    .build();
            return decision(RouteMode.GLOBAL, List.of(), originalScope, originalScope, 0, 0, false);
        }

        List<KnowledgeBaseMeta> knowledgeBases;
        try {
            knowledgeBases = knowledgeBaseMapper.selectRetrievableMetadata();
        } catch (DataAccessException exception) {
            requireNotInterrupted();
            // 无法确认可用范围时不扩大检索范围，也不能宣称没有相关资料。
            log.warn("无法读取知识库路由元数据，failureType={}", exception.getClass().getSimpleName());
            throw new RetrievalUnavailableException();
        }
        requireNotInterrupted();
        List<String> accessibleKbIds = new ArrayList<>();
        for (KnowledgeBaseMeta knowledgeBase : knowledgeBases) {
            accessibleKbIds.add(knowledgeBase.getKbId());
        }
        RetrievalScope globalScope = scopedTo(accessibleKbIds);
        if (knowledgeBases.isEmpty()) {
            return decision(RouteMode.NONE, List.of(), globalScope, globalScope, 0, 0, false);
        }

        long startedNanos = System.nanoTime();
        try {
            List<KbScore> scores = jevClient.score(query, knowledgeBases);
            requireNotInterrupted();
            return decide(scores, globalScope, elapsedMillis(startedNanos));
        } catch (JevCallException exception) {
            requireNotInterrupted();
            log.warn("Jev 路由失败，降级到可用知识库全局检索，reason={}", exception.getMessage());
            return decision(RouteMode.GLOBAL, List.of(), globalScope, globalScope,
                    knowledgeBases.size(), elapsedMillis(startedNanos), true);
        }
    }

    private RouteDecision decide(List<KbScore> scores, RetrievalScope globalScope, long durationMs) {
        List<KbScore> candidates = new ArrayList<>();
        for (KbScore score : scores) {
            if (score.getProbability() >= routingProperties.getMidThreshold()) {
                candidates.add(score);
            }
        }
        candidates.sort(Comparator.comparingDouble(KbScore::getProbability)
                .reversed()
                .thenComparing(KbScore::getKbId));
        if (candidates.isEmpty()) {
            return decision(RouteMode.NONE, List.of(), scopedTo(List.of()), globalScope,
                    scores.size(), durationMs, false);
        }
        if (candidates.size() == 1
                && candidates.getFirst().getProbability() < routingProperties.getHighThreshold()) {
            return decision(RouteMode.GLOBAL, List.of(), globalScope, globalScope,
                    scores.size(), durationMs, false);
        }
        List<String> selectedKbIds = new ArrayList<>();
        for (KbScore candidate : candidates) {
            selectedKbIds.add(candidate.getKbId());
        }
        RouteMode mode = RouteMode.MULTI;
        if (selectedKbIds.size() == 1) {
            mode = RouteMode.SINGLE;
        }
        return decision(mode, selectedKbIds, scopedTo(selectedKbIds), globalScope,
                scores.size(), durationMs, false);
    }

    private RetrievalScope scopedTo(List<String> kbIds) {
        return RetrievalScope.builder()
                .unrestricted(false)
                .kbIds(List.copyOf(kbIds))
                .build();
    }

    private RouteDecision decision(RouteMode mode, List<String> kbIds, RetrievalScope scope,
                                   RetrievalScope globalScope, int candidateKbCount,
                                   long durationMs, boolean routingFallback) {
        return RouteDecision.builder()
                .mode(mode)
                .kbIds(List.copyOf(kbIds))
                .scope(scope)
                .globalScope(globalScope)
                .candidateKbCount(candidateKbCount)
                .jevDurationMs(durationMs)
                .routingFallback(routingFallback)
                .build();
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("知识库路由已中断");
        }
    }
}
