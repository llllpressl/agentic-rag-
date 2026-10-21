package com.llll.rag.search.routing;

import com.llll.rag.search.properties.RagSearchProperties;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "rag.routing")
public class RagRoutingProperties {

    private final RagSearchProperties searchProperties;

    private boolean enabled;

    private double highThreshold = 0.7;

    private double midThreshold = 0.5;

    private int fallbackMinChunks = 3;

    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (!Double.isFinite(midThreshold) || !Double.isFinite(highThreshold)
                || midThreshold < 0 || highThreshold > 1 || midThreshold >= highThreshold) {
            throw new IllegalArgumentException("路由阈值必须满足 0 <= mid-threshold < high-threshold <= 1");
        }
        if (fallbackMinChunks < 1 || fallbackMinChunks > searchProperties.getFusionTopK()) {
            throw new IllegalArgumentException("fallback-min-chunks 必须在 1 到 fusion-top-k 之间");
        }
    }
}
