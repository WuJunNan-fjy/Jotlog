package com.jotlog.enrich;

import com.jotlog.config.AiProperties;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 只有 jotlog.ai.enabled=true 时才创建模型 Bean；key 为空直接 fail fast。
 * enabled=false 时 enrich 包里依赖 ChatModel 的 Bean 全部不创建，应用照常启动。
 */
@Configuration
@ConditionalOnProperty(name = "jotlog.ai.enabled", havingValue = "true")
public class DeepSeekConfig {

    @Bean
    public OpenAiChatModel openAiChatModel(AiProperties props) {
        if (!props.isReady()) {
            throw new IllegalStateException(
                    "jotlog.ai.enabled=true 但未配置 jotlog.ai.api-key（或环境变量 JOTLOG_AI_KEY）");
        }
        return OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(props.getModel())
                .timeout(Duration.ofSeconds(30))
                .maxRetries(1)
                .build();
    }
}
