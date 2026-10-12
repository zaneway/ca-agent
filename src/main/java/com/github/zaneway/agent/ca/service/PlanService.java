package com.github.zaneway.agent.ca.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 先由 LLM 判断问题是否需要 RFC 知识，再按需检索 Qdrant 并生成最终回答。
 *
 * <p>检索路由输出采用结构化 JSON，解析失败时默认执行检索，优先保证 RFC 事实问题的准确性。
 * Qdrant 检索失败时不降级为无依据回答，而是抛出异常交给调用层重试或返回服务不可用。</p>
 */
@Service
public class PlanService {

	private static final Logger LOGGER = LoggerFactory.getLogger(PlanService.class);

	private static final BeanOutputConverter<RetrievalDecision> DECISION_CONVERTER =
		new BeanOutputConverter<>(RetrievalDecision.class);

	private static final String EMPTY_RFC_CONTEXT = "未检索到满足相似度阈值的 RFC 记录。";

	private final ChatModel chatModel;

	private final VectorStore vectorStore;

	private final String routingPrompt;

	private final String contextualAnswerPrompt;

	private final String directAnswerPrompt;

	private final int topK;

	private final double similarityThreshold;

	private final int maxContextChars;


	PlanService(@Lazy ChatModel chatModel, VectorStore vectorStore,
			@Value("${prompt.plan}") String routingPrompt,
			@Value("${prompt.answer-with-rfc-context}") String contextualAnswerPrompt,
			@Value("${prompt.direct-answer}") String directAnswerPrompt,
			@Value("${ca-agent.rag.top-k:5}") int topK,
			@Value("${ca-agent.rag.similarity-threshold:0.65}") double similarityThreshold,
			@Value("${ca-agent.rag.max-context-chars:16000}") int maxContextChars) {
		this.chatModel = chatModel;
		this.vectorStore = vectorStore;
		this.routingPrompt = routingPrompt;
		this.contextualAnswerPrompt = contextualAnswerPrompt;
		this.directAnswerPrompt = directAnswerPrompt;
		this.topK = topK;
		this.similarityThreshold = similarityThreshold;
		this.maxContextChars = maxContextChars;
	}

	/**
	 * 处理用户问题：先判断是否需要 RFC 检索，必要时将 Qdrant 返回内容作为补充上下文再调用 LLM。
	 *
	 * @param question 用户问题，不能为空且不能超过配置的最大字符数
	 * @return LLM 生成的最终回答
	 * @throws IllegalArgumentException 输入或 RAG 配置不合法时抛出
	 * @throws IllegalStateException LLM 调用、响应解析或 RFC 知识库检索失败时抛出
	 */
	public String doPlan(String question) {
		validate(question);
		long startNanos = System.nanoTime();
		LOGGER.info("开始处理用户问题，questionLength={}", question.length());

		RetrievalDecision decision = decideRetrieval(question);
		if (!decision.retrieve()) {
			String answer = callChatModel("direct-answer", directAnswerPrompt, "用户问题：\n" + question);
			LOGGER.info("用户问题处理完成，retrievalUsed=false, elapsedMs={}, answerLength={}",
				elapsedMillis(startNanos), answer.length());
			return answer;
		}

		String searchQuery = normalizeSearchQuery(decision.query(), question);
		List<Document> documents = searchRfcDocuments(searchQuery);
		String context = buildRfcContext(documents);
		String answer = callChatModel("contextual-answer", contextualAnswerPrompt,
			"用户问题：\n" + question + "\n\n<RFC_REFERENCE>\n" + context + "\n</RFC_REFERENCE>");

		LOGGER.info("用户问题处理完成，retrievalUsed=true, documentCount={}, elapsedMs={}, answerLength={}",
			documents.size(), elapsedMillis(startNanos), answer.length());
		return answer;
	}

	private RetrievalDecision decideRetrieval(String question) {
		String systemPrompt = routingPrompt + "\n\n" + DECISION_CONVERTER.getFormat();
		String responseText = callChatModel("retrieval-decision", systemPrompt, "用户问题：\n" + question);
		try {
			RetrievalDecision decision = DECISION_CONVERTER.convert(responseText);
			if (decision == null) {
				throw new IllegalArgumentException("LLM 未返回检索决策");
			}
			LOGGER.info("RFC 检索决策完成，retrieve={}, queryLength={}", decision.retrieve(),
				decision.query() == null ? 0 : decision.query().length());
			return decision;
		}
		catch (RuntimeException exception) {
			// 路由格式异常时宁可增加一次检索，也不直接生成可能缺少 RFC 依据的回答。
			LOGGER.warn("RFC 检索决策解析失败，降级为执行检索，responseLength={}", responseText.length(), exception);
			return new RetrievalDecision(true, question, "decision_parse_failed");
		}
	}

	private String normalizeSearchQuery(String decisionQuery, String question) {
		String query = StringUtils.hasText(decisionQuery) ? decisionQuery.trim() : question;
		return query.length() <= maxQuestionChars ? query : query.substring(0, maxQuestionChars);
	}

	private List<Document> searchRfcDocuments(String searchQuery) {
		SearchRequest request = SearchRequest.builder()
			.query(searchQuery)
			.topK(topK)
			.similarityThreshold(similarityThreshold)
			.build();

		long startNanos = System.nanoTime();
		LOGGER.info("开始检索 RFC 知识库，queryLength={}, topK={}, similarityThreshold={}", searchQuery.length(),
			topK, similarityThreshold);
		try {
			List<Document> documents = vectorStore.similaritySearch(request);
			List<Document> result = documents == null ? List.of() : List.copyOf(documents);
			LOGGER.info("RFC 知识库检索完成，documentCount={}, elapsedMs={}", result.size(),
				elapsedMillis(startNanos));
			return result;
		}
		catch (RuntimeException exception) {
			LOGGER.error("RFC 知识库检索失败，queryLength={}, elapsedMs={}", searchQuery.length(),
				elapsedMillis(startNanos), exception);
			throw new IllegalStateException("RFC 知识库检索失败，请稍后重试", exception);
		}
	}

	private String buildRfcContext(List<Document> documents) {
		if (documents.isEmpty()) {
			return EMPTY_RFC_CONTEXT;
		}

		StringBuilder context = new StringBuilder(Math.min(maxContextChars, 4096));
		for (int index = 0; index < documents.size() && context.length() < maxContextChars; index++) {
			Document document = documents.get(index);
			if (document == null || !StringUtils.hasText(document.getText())) {
				continue;
			}
			String header = "[RFC_SOURCE_" + (index + 1) + "]\n"
				+ "id=" + document.getId() + "\n"
				+ "score=" + document.getScore() + "\n"
				+ "metadata=" + document.getMetadata() + "\n"
				+ "content=\n";
			appendWithinLimit(context, header, maxContextChars);
			appendWithinLimit(context, document.getText(), maxContextChars);
			appendWithinLimit(context, "\n[/RFC_SOURCE_" + (index + 1) + "]\n", maxContextChars);
		}
		return context.isEmpty() ? EMPTY_RFC_CONTEXT : context.toString();
	}

	private static void appendWithinLimit(StringBuilder target, String value, int maxChars) {
		if (target.length() >= maxChars || value == null || value.isEmpty()) {
			return;
		}
		int remaining = maxChars - target.length();
		target.append(value, 0, Math.min(value.length(), remaining));
	}

	private String callChatModel(String stage, String systemPrompt, String userPrompt) {
		Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
		long startNanos = System.nanoTime();
		LOGGER.info("开始调用 LLM，stage={}, userPromptLength={}", stage, userPrompt.length());
		try {
			ChatResponse response = chatModel.call(prompt);
			if (response == null || response.getResult() == null || response.getResult().getOutput() == null
					|| !StringUtils.hasText(response.getResult().getOutput().getText())) {
				throw new IllegalStateException("LLM 返回空响应，stage=" + stage);
			}
			String text = response.getResult().getOutput().getText().trim();
			LOGGER.info("LLM 调用完成，stage={}, elapsedMs={}, responseLength={}", stage,
				elapsedMillis(startNanos), text.length());
			return text;
		}
		catch (RuntimeException exception) {
			LOGGER.error("LLM 调用失败，stage={}, elapsedMs={}", stage, elapsedMillis(startNanos), exception);
			throw exception;
		}
	}

	private void validate(String question) {
		if (!StringUtils.hasText(question)) {
			throw new IllegalArgumentException("用户问题不能为空");
		}
		if (question.length() > maxQuestionChars) {
			throw new IllegalArgumentException("用户问题长度不能超过 " + maxQuestionChars + " 个字符");
		}
		if (topK <= 0) {
			throw new IllegalArgumentException("ca-agent.rag.top-k 必须大于 0");
		}
		if (!Double.isFinite(similarityThreshold) || similarityThreshold < 0 || similarityThreshold > 1) {
			throw new IllegalArgumentException("ca-agent.rag.similarity-threshold 必须在 0 到 1 之间");
		}
		if (maxContextChars <= 0 || maxQuestionChars <= 0) {
			throw new IllegalArgumentException("RAG 字符数限制必须大于 0");
		}
	}

	private static long elapsedMillis(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

	/**
	 * LLM 返回的 RFC 检索路由决策。
	 *
	 * @param retrieve 是否需要查询 RFC 知识库
	 * @param query 用于向量检索的短查询；为空时使用原始问题
	 * @param reason 决策原因，仅用于结构完整性，不写入日志或最终提示词
	 */
	public record RetrievalDecision(boolean retrieve, String query, String reason) {
	}
}
