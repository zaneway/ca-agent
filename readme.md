# ca-agent

* 写一个CA相关的agent
* agent主要做：理解目标、检索资料、生成计划、选择工具、解释结果。
* 最终实现智能化的证书生命周期处理
# 功能
1. 初步先实现知识索引

## MySQL / MyBatis

MySQL 运行时配置、MyBatis Mapper 目录约定以及 MyBatis Generator 使用方式见
[`docs/mybatis-generator.md`](docs/mybatis-generator.md)。

## MiniMax OpenAI 兼容接口测试

项目使用现有的 Spring AI OpenAI ChatModel 调用 MiniMax 的 OpenAI Chat Completions 兼容接口，
所有模型参数统一维护在 `application.yaml` 的 `ca-agent.models` 下，不需要额外引入 OpenAI Java SDK。
配置结构、扩展方式和回滚说明见 [`docs/model-configuration.md`](docs/model-configuration.md)。测试类为
`src/test/java/com/github/zaneway/agent/ca/models/MiniMaxOpenAiCompatibilityTest.java`。

### 配置

| 环境变量 | 生产环境必填 | YAML 默认值 | 说明 |
| --- | --- | --- | --- |
| `MINIMAX_API_KEY` | 是 | 无 | MiniMax API Key；不得提交到版本库 |
| `MINIMAX_BASE_URL` | 否 | `https://api.minimax.io/v1` | OpenAI 兼容接口根地址 |
| `MINIMAX_MODEL` | 否 | `MiniMax-M3` | 待验证的 MiniMax 模型 |

采样温度、token 上限、超时和重试次数直接在 `ca-agent.models.minimax` 中配置。

### 运行

该用例会访问真实外部服务并产生少量费用。未配置 `MINIMAX_API_KEY` 时，JUnit 会自动跳过测试。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export MINIMAX_API_KEY='<your-minimax-api-key>'
mvn -Dtest=MiniMaxOpenAiCompatibilityTest test
```

成功条件是接口返回至少一个生成结果，且文本中包含测试要求模型返回的 `MINIMAX_OK` 标记。
如果需要验证其他 MiniMax 兼容端点，只覆盖 YAML 使用的环境变量，不要修改测试代码。

常见失败包括：API Key 无效（401/403）、余额或配额不足（429）、模型名不可用（400/404），
以及代理、DNS、TLS 或连接超时。测试日志只记录模型、端点、耗时和响应长度，不记录 API Key。

## Qdrant 向量存储

`VectorConfig` 根据 `application.yaml` 中的连接、集合和超时配置创建 `QdrantClient` 与
`QdrantVectorStore`，文本向量由 Ollama `EmbeddingModel` 生成。配置说明、初始化策略与排障方式见
[`docs/vector-configuration.md`](docs/vector-configuration.md)。

## RFC 按需检索问答

`PlanService#doPlan` 先通过 LLM 结构化判断是否需要 RFC 依据；需要时检索 Qdrant，将命中的 RFC
记录作为不可信参考上下文交给 LLM 生成最终回答。流程、提示词、安全边界与错误策略见
[`docs/rfc-rag-flow.md`](docs/rfc-rag-flow.md)。

# 展望
* 设置定时任务，定期检测或自动化的更新证书