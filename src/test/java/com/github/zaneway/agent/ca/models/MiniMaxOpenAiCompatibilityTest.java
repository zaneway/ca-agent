package com.github.zaneway.agent.ca.models;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.github.zaneway.agent.ca.config.ModelConfig;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Spring AI 能否使用 application.yaml 中的配置调用 MiniMax OpenAI 兼容接口。
 *
 * <p>该测试会产生真实网络请求和少量模型费用；未配置 {@code MINIMAX_API_KEY} 时自动跳过，
 * 避免常规构建依赖外部服务。</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MiniMaxOpenAiCompatibilityTest.TestConfiguration.class,
		initializers = ConfigDataApplicationContextInitializer.class)
class MiniMaxOpenAiCompatibilityTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(MiniMaxOpenAiCompatibilityTest.class);

	private static final String MODEL_ID = "minimax";

	private static final String EXPECTED_MARKER = "MINIMAX_OK";

	private final ModelConfig modelConfig;

	@Autowired
	MiniMaxOpenAiCompatibilityTest(ModelConfig modelConfig) {
		this.modelConfig = modelConfig;
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "MINIMAX_API_KEY", matches = ".*\\S.*")
	void shouldCallMiniMaxThroughOpenAiCompatibleApi() {
		ModelConfig.ModelProperties properties = modelConfig.models().get(MODEL_ID);
		ChatModel chatModel = modelConfig.createChatModel(MODEL_ID);

		long startNanos = System.nanoTime();
		LOGGER.info("开始调用 MiniMax OpenAI 兼容接口，model={}, baseUrl={}", properties.model(),
			properties.baseUrl());
		try {
			ChatResponse response = chatModel.call(new Prompt("只回复文本 " + EXPECTED_MARKER + "，不要添加其他内容。"));
			assertNotNull(response.getResult(), "MiniMax 响应必须包含至少一个生成结果");

			String content = response.getResult().getOutput().getText();
			assertNotNull(content, "MiniMax 响应文本不能为空");
			assertTrue(content.contains(EXPECTED_MARKER), () -> "MiniMax 响应未包含预期标记，实际响应：" + content);

			LOGGER.info("MiniMax 调用成功，model={}, elapsedMs={}, responseLength={}", properties.model(),
				elapsedMillis(startNanos), content.length());
		}
		catch (RuntimeException | AssertionError exception) {
			LOGGER.error("MiniMax 调用失败，model={}, elapsedMs={}", properties.model(), elapsedMillis(startNanos),
				exception);
			throw exception;
		}
	}

	private static long elapsedMillis(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(ModelConfig.class)
	static class TestConfiguration {
	}
}
