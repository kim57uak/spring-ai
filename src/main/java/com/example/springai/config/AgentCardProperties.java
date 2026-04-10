package com.example.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Agent Card 노출 정책 설정.
 * <p>
 * enabledScopes를 지정하면 해당 스코프만 카드/엔드포인트 활성 대상으로 취급한다.
 * 비어 있으면 agent.scopes에 정의된 스코프 전체를 활성 대상으로 본다.
 */
@ConfigurationProperties(prefix = "agent.cards")
public class AgentCardProperties {

    private Set<String> enabledScopes = Collections.emptySet();

    public Set<String> getEnabledScopes() {
        return enabledScopes == null ? Collections.emptySet() : enabledScopes;
    }

    public void setEnabledScopes(Set<String> enabledScopes) {
        this.enabledScopes = enabledScopes == null ? Collections.emptySet() : new LinkedHashSet<>(enabledScopes);
    }
}

