package com.github.zaneway.agent.ca.config;

import java.time.Duration;

import io.micrometer.observation.ObservationRegistry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 根据 YAML 配置创建 Qdrant 客户端和 Spring AI 向量存储。
 *
 * <p>Spring AI 内置 Qdrant 自动装配保持关闭，由本配置统一控制连接超时、兼容性检查和客户端关闭。
 * Qdrant 使用 gRPC 端口；HTTP 管理端口不能用于此处。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VectorConfig.QdrantProperties.class)
@ConditionalOnProperty(prefix = "ca-agent.vector-store.qdrant", name = "enabled", havingValue = "true")
public class VectorConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger(VectorConfig.class);

	/**
	 * 创建由 Spring 容器管理生命周期的 Qdrant 客户端。
	 *
	 * @param properties Qdrant 连接配置
	 * @return Qdrant 客户端，容器关闭时同步释放 gRPC channel
	 * @throws IllegalArgumentException 配置缺失或取值越界时抛出
	 */
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean
	public QdrantClient qdrantClient(QdrantProperties properties) {
		properties.validate();
		long startNanos = System.nanoTime();
		LOGGER.info("开始创建 Qdrant 客户端，host={}, port={}, tls={}, checkCompatibility={}", properties.host(),
			properties.port(), properties.useTls(), properties.checkCompatibility());
		try {
			QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(properties.host(), properties.port(),
				properties.useTls(), properties.checkCompatibility())
				.withTimeout(properties.requestTimeout());
			if (StringUtils.hasText(properties.apiKey())) {
				builder.withApiKey(properties.apiKey());
			}

			QdrantClient client = new QdrantClient(builder.build());
			LOGGER.info("Qdrant 客户端创建完成，host={}, port={}, elapsedMs={}", properties.host(), properties.port(),
				elapsedMillis(startNanos));
			return client;
		}
		catch (RuntimeException exception) {
			LOGGER.error("Qdrant 客户端创建失败，host={}, port={}, elapsedMs={}", properties.host(), properties.port(),
				elapsedMillis(startNanos), exception);
			throw exception;
		}
	}

	/**
	 * 使用配置文件中的集合参数及容器内唯一的 EmbeddingModel 创建 QdrantVectorStore。
	 *
	 * @param qdrantClient Qdrant 客户端
	 * @param embeddingModel 文本向量化模型；向量维度必须与已有集合一致
	 * @param properties Qdrant 集合配置
	 * @param observationRegistryProvider 可选的观测注册器
	 * @return 可注入业务服务的 Qdrant 向量存储
	 */
	@Bean
	@ConditionalOnMissingBean(QdrantVectorStore.class)
	public QdrantVectorStore qdrantVectorStore(QdrantClient qdrantClient, EmbeddingModel embeddingModel,
			QdrantProperties properties, ObjectProvider<ObservationRegistry> observationRegistryProvider) {
		properties.validate();
		LOGGER.info("开始创建 QdrantVectorStore，collection={}, initializeSchema={}", properties.collectionName(),
			properties.initializeSchema());

		QdrantVectorStore vectorStore = QdrantVectorStore.builder(qdrantClient, embeddingModel)
			.collectionName(properties.collectionName())
			.contentFieldName(properties.contentFieldName())
			.initializeSchema(properties.initializeSchema())
			.observationRegistry(observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP))
			.build();

		LOGGER.info("QdrantVectorStore 创建完成，collection={}", properties.collectionName());
		return vectorStore;
	}

	private static long elapsedMillis(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

	/**
	 * Qdrant 连接和集合配置。
	 *
	 * @param enabled 是否创建 Qdrant 相关 Bean
	 * @param host Qdrant gRPC 主机名
	 * @param port Qdrant gRPC 端口
	 * @param useTls 是否启用 TLS
	 * @param apiKey 可选 API Key，应通过环境变量或密钥管理系统注入
	 * @param collectionName 向量集合名称
	 * @param contentFieldName 文档正文在 payload 中的字段名
	 * @param initializeSchema 是否在启动时自动创建集合
	 * @param requestTimeout 单次 gRPC 请求超时
	 * @param checkCompatibility 是否在创建客户端时连接服务端检查版本兼容性
	 */
	@ConfigurationProperties(prefix = "ca-agent.vector-store.qdrant")
	public record QdrantProperties(boolean enabled, String host, int port, boolean useTls, String apiKey,
			String collectionName, String contentFieldName, boolean initializeSchema, Duration requestTimeout,
			boolean checkCompatibility) {

		private void validate() {
			if (!StringUtils.hasText(host)) {
				throw new IllegalArgumentException("Qdrant host 不能为空");
			}
			if (port < 1 || port > 65535) {
				throw new IllegalArgumentException("Qdrant port 必须在 1 到 65535 之间");
			}
			if (!StringUtils.hasText(collectionName)) {
				throw new IllegalArgumentException("Qdrant collection-name 不能为空");
			}
			if (!StringUtils.hasText(contentFieldName)) {
				throw new IllegalArgumentException("Qdrant content-field-name 不能为空");
			}
			if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
				throw new IllegalArgumentException("Qdrant request-timeout 必须大于 0");
			}
		}

		/**
		 * 输出配置摘要时隐藏 API Key，避免日志泄露凭证。
		 *
		 * @return 脱敏后的配置描述
		 */
		@Override
		public String toString() {
			return "QdrantProperties[enabled=" + enabled + ", host=" + host + ", port=" + port + ", useTls="
				+ useTls + ", apiKey=***, collectionName=" + collectionName + ", contentFieldName="
				+ contentFieldName + ", initializeSchema=" + initializeSchema + ", requestTimeout="
				+ requestTimeout + ", checkCompatibility=" + checkCompatibility + "]";
		}
	}
}
