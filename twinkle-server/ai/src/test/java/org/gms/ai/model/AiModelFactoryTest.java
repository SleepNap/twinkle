package org.gms.ai.model;

import org.gms.ai.model.tool.ToolRouter;
import org.gms.i18n.I18n;
import org.gms.i18n.ResourceBundleI18nService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实模型装配只做构造级验证，不访问外部网络或读取真实密钥。 */
class AiModelFactoryTest {

    @BeforeEach
    void setUp() {
        I18n.install(new ResourceBundleI18nService("zh-CN"));
    }

    @Test
    void localRuleRemainsAvailableForOfflineTests() {
        AiModelBundle bundle = AiModelFactory.create("local-rule", "", "", "",
                0.1, 1200, 30, new ToolRouter());

        assertThat(bundle.external()).isFalse();
        assertThat(bundle.descriptor()).isEqualTo("local-rule/deterministic");
    }

    @Test
    void deepSeekUsesSafeDefaultsWithoutCallingNetwork() {
        AiModelBundle bundle = AiModelFactory.create("deepseek", "", "test-placeholder-key", "",
                0.1, 1200, 30, new ToolRouter());

        assertThat(bundle.external()).isTrue();
        assertThat(bundle.descriptor()).isEqualTo("deepseek/deepseek-chat");
    }

    @Test
    void remoteCompatibleEndpointWithoutKeyFailsFast() {
        assertThatThrownBy(() -> AiModelFactory.create("openai-compatible",
                "https://models.example.com/v1", "", "cheap-tool-model",
                0.1, 1200, 30, new ToolRouter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TWINKLE_LLM_API_KEY");
    }

    @Test
    void loopbackCompatibleEndpointMayRunWithoutKey() {
        AiModelBundle bundle = AiModelFactory.create("openai-compatible",
                "http://127.0.0.1:11434/v1", "", "qwen3",
                0.1, 1200, 30, new ToolRouter());

        assertThat(bundle.descriptor()).isEqualTo("openai-compatible/qwen3");
    }
}
