package com.github.zaneway.agent.ca.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 从 YAML 绑定模型配置，并按模型标识创建 OpenAI 兼容的聊天模型。
 *
 * <p>API Key 只允许通过配置占位符注入；本类不会记录或回传凭证。模型在首次使用时才校验，
 * 使未配置可选模型凭证的开发环境仍可正常启动。</p>
 *
 * @param models 以模型标识为键的模型配置，例如 {@code minimax}
 */
@ConfigurationProperties(prefix = "ca-agent")
public record ModelConfig(Map<String, ModelProperties> models) {

	public ModelConfig {
		models = models == null ? Map.of() : Map.copyOf(models);
	}

	/**
	 * 使用 YAML 中指定标识的完整配置创建聊天模型。
	 *
	 * @param modelId {@code ca-agent.models} 下的模型标识
	 * @return 已配置的聊天模型
	 * @throws IllegalArgumentException 模型标识为空、模型不存在或配置项不合法时抛出
	 */
	public ChatModel createChatModel(String modelId) {
		ModelProperties properties = requireValidProperties(modelId);
		OpenAiChatOptions options = OpenAiChatOptions.builder()
			.baseUrl(properties.baseUrl())
			.apiKey(properties.apiKey())
			.model(properties.model())
			.temperature(properties.temperature())
			.maxCompletionTokens(properties.maxCompletionTokens())
			.timeout(properties.timeout())
			.maxRetries(properties.maxRetries())
			.build();

		return OpenAiChatModel.builder().options(options).build();
	}

	/**
	 * 仅输出模型标识，避免 record 默认实现把 API Key 写入日志。
	 *
	 * @return 不含敏感配置的描述
	 */
	@Override
	public String toString() {
		return "ModelConfig[models=" + models.keySet() + "]";
	}

	private ModelProperties requireValidProperties(String modelId) {
		requireText(modelId, "模型标识不能为空");
		ModelProperties properties = models.get(modelId);
		if (properties == null) {
			throw new IllegalArgumentException("未找到模型配置: " + modelId);
		}

		requireText(properties.baseUrl(), "模型 " + modelId + " 的 base-url 不能为空");
		requireText(properties.apiKey(), "模型 " + modelId + " 的 api-key 不能为空");
		requireText(properties.model(), "模型 " + modelId + " 的 model 不能为空");
		if (properties.temperature() == null || !Double.isFinite(properties.temperature())
				|| properties.temperature() < 0) {
			throw new IllegalArgumentException("模型 " + modelId + " 的 temperature 必须是非负有限数值");
		}
		if (properties.maxCompletionTokens() == null || properties.maxCompletionTokens() <= 0) {
			throw new IllegalArgumentException("模型 " + modelId + " 的 max-completion-tokens 必须大于 0");
		}
		if (properties.timeout() == null || properties.timeout().isZero() || properties.timeout().isNegative()) {
			throw new IllegalArgumentException("模型 " + modelId + " 的 timeout 必须大于 0");
		}
		if (properties.maxRetries() == null || properties.maxRetries() < 0) {
			throw new IllegalArgumentException("模型 " + modelId + " 的 max-retries 不能小于 0");
		}
		return properties;
	}

	private static void requireText(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
	}

	/**
	 * 单个 OpenAI Chat Completions 兼容模型的完整运行参数。
	 *
	 * @param baseUrl 兼容接口根地址
	 * @param apiKey API Key，生产环境应使用环境变量或密钥管理系统注入
	 * @param model 服务端模型名称
	 * @param temperature 采样温度，必须为非负有限数值
	 * @param maxCompletionTokens 单次响应的最大 token 数
	 * @param timeout 单次请求超时
	 * @param maxRetries 请求失败后的最大重试次数
	 */
	public record ModelProperties(String baseUrl, String apiKey, String model, Double temperature,
			Integer maxCompletionTokens, Duration timeout, Integer maxRetries) {

		/**
		 * 输出配置摘要时隐藏 API Key，避免排障日志泄露凭证。
		 *
		 * @return 脱敏后的配置描述
		 */
		@Override
		public String toString() {
			return "ModelProperties[baseUrl=" + baseUrl + ", apiKey=***, model=" + model + ", temperature="
				+ temperature + ", maxCompletionTokens=" + maxCompletionTokens + ", timeout=" + timeout
				+ ", maxRetries=" + maxRetries + "]";
		}
	}
}
