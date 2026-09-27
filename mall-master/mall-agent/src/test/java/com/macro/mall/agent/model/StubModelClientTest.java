package com.macro.mall.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link StubModelClient} 的确定性、离线与关键词优先级测试。
 *
 * <p>对齐 Python {@code mall_shopping_agent.model.stub}：从最后一条 user 消息按固定关键词优先级
 * 提取一个关键词，生成固定 {@code searchProducts} 工具调用，再返回固定中性演示回答；
 * 不联网、不读取任何凭据、不编造商品事实。
 *
 * <p>缺省响应只由当前请求的消息上下文决定（不维护实例级会话状态）：以 user 结束的独立轮返回
 * 搜索工具调用，以 tool 结果结束的后续轮返回中性最终文本。
 */
class StubModelClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String NEUTRAL_ANSWER =
            "我根据商城搜索结果为您列出候选商品，名称、价格和库存均来自商城实时数据；"
                    + "如需比较或查看库存，可以继续告诉我商品名称。";

    @Test
    @DisplayName("模式标识为 stub")
    void modeIsStub() {
        assertThat(new StubModelClient().mode()).isEqualTo("stub");
    }

    @Test
    @DisplayName("缺省脚本先返回 searchProducts 工具调用，关键词按固定优先级取首个命中")
    void defaultScriptEmitsSearchToolCallWithPriorityKeyword() throws Exception {
        StubModelClient stub = new StubModelClient();

        ModelResponse first = stub.complete(userRequest("我想买鞋，再看看手机"));

        assertThat(first.content()).isNull();
        assertThat(first.toolCalls()).hasSize(1);
        ModelToolCall call = first.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("stub_call_1");
        assertThat(call.name()).isEqualTo("searchProducts");

        JsonNode arguments = JSON.readTree(call.arguments());
        assertThat(arguments.path("pageNum").asInt()).isEqualTo(1);
        assertThat(arguments.path("sort").asInt()).isEqualTo(0);
        assertThat(arguments.path("keyword").asText()).isEqualTo("手机");
    }

    @Test
    @DisplayName("关键词取自最后一条 user 消息")
    void keywordComesFromLastUserMessage() throws Exception {
        StubModelClient stub = new StubModelClient();
        ModelRequest request = new ModelRequest(
                List.of(
                        ModelMessage.user("手机"),
                        ModelMessage.assistant("好的", List.of()),
                        ModelMessage.user("耳机")),
                List.of(),
                ModelRequest.TOOL_CHOICE_AUTO,
                0.2,
                null);

        ModelResponse first = stub.complete(request);

        JsonNode arguments = JSON.readTree(first.toolCalls().get(0).arguments());
        assertThat(arguments.path("keyword").asText()).isEqualTo("耳机");
    }

    @Test
    @DisplayName("非 user 角色不提供关键词")
    void nonUserMessagesDoNotSupplyKeyword() throws Exception {
        StubModelClient stub = new StubModelClient();
        // 真实的独立 user 轮：user → assistant(tool_calls) → tool 结果 → 新 user 追问；
        // 关键词只应来自最后一条 user（"再看看别的"，无命中），assistant 参数与 tool 结果里的关键词不得被采用。
        ModelRequest request = new ModelRequest(
                List.of(
                        ModelMessage.user("随便看看"),
                        ModelMessage.assistant(null, List.of(
                                new ModelToolCall("call_1", "searchProducts", "{\"keyword\": \"相机\"}"))),
                        ModelMessage.tool("call_1", "手机"),
                        ModelMessage.user("再看看别的")),
                List.of(),
                ModelRequest.TOOL_CHOICE_AUTO,
                0.2,
                null);

        ModelResponse first = stub.complete(request);

        JsonNode arguments = JSON.readTree(first.toolCalls().get(0).arguments());
        assertThat(arguments.has("keyword")).isFalse();
        assertThat(arguments.path("pageNum").asInt()).isEqualTo(1);
        assertThat(arguments.path("sort").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("没有命中关键词时不添加 keyword 字段")
    void noKeywordWhenNoHintMatches() throws Exception {
        StubModelClient stub = new StubModelClient();

        ModelResponse first = stub.complete(userRequest("随便看看有什么"));

        JsonNode arguments = JSON.readTree(first.toolCalls().get(0).arguments());
        assertThat(arguments.has("keyword")).isFalse();
    }

    @Test
    @DisplayName("缺省模式：以工具结果结束的工具后续轮返回固定中性演示回答")
    void toolFollowUpReturnsNeutralAnswer() {
        StubModelClient stub = new StubModelClient();

        ModelResponse first = stub.complete(userRequest("有哪些手机"));
        ModelResponse second = stub.complete(toolFollowUp("有哪些手机", first.toolCalls().get(0)));

        assertThat(first.toolCalls()).hasSize(1);
        assertThat(second.toolCalls()).isEmpty();
        assertThat(second.content()).isEqualTo(NEUTRAL_ANSWER);
    }

    @Test
    @DisplayName("中性回答不含编造的商品事实")
    void neutralAnswerCarriesNoProductFacts() {
        StubModelClient stub = new StubModelClient();
        ModelResponse first = stub.complete(userRequest("有哪些手机"));

        String answer = stub.complete(toolFollowUp("有哪些手机", first.toolCalls().get(0))).content();

        assertThat(answer).isEqualTo(NEUTRAL_ANSWER);
        assertThat(answer).contains("商城实时数据");
    }

    @Test
    @DisplayName("预录响应按顺序逐次消费")
    void scriptedResponsesAreConsumedInOrder() {
        StubModelClient stub = new StubModelClient(List.of(
                new ModelResponse("第一条", List.of(), null, "stop"),
                new ModelResponse("第二条", List.of(), null, "stop")));

        assertThat(stub.complete(userRequest("随便")).content()).isEqualTo("第一条");
        assertThat(stub.complete(userRequest("随便")).content()).isEqualTo("第二条");
    }

    @Test
    @DisplayName("预录脚本耗尽后回退到缺省行为，不重复泄漏已消费响应")
    void scriptExhaustionFallsBackToDefaultWithoutLeaking() {
        StubModelClient stub = new StubModelClient(List.of(
                new ModelResponse("只预录一条", List.of(), null, "stop")));

        assertThat(stub.complete(userRequest("有哪些手机")).content()).isEqualTo("只预录一条");

        // 耗尽后：独立 user 轮得到缺省工具调用，其工具后续轮得到中性回答
        ModelResponse fallbackToolCall = stub.complete(userRequest("有哪些手机"));
        assertThat(fallbackToolCall.toolCalls()).hasSize(1);
        assertThat(fallbackToolCall.content()).isNull();

        ModelToolCall call = fallbackToolCall.toolCalls().get(0);
        ModelResponse fallbackAnswer = stub.complete(toolFollowUp("有哪些手机", call));
        assertThat(fallbackAnswer.content()).isEqualTo(NEUTRAL_ANSWER);
        assertThat(fallbackAnswer.toolCalls()).isEmpty();

        // 同一工具后续轮重复调用保持稳定，且不再出现预录内容
        ModelResponse repeated = stub.complete(toolFollowUp("有哪些手机", call));
        assertThat(repeated.content()).isEqualTo(NEUTRAL_ANSWER);
        assertThat(repeated.content()).isNotEqualTo("只预录一条");
    }

    @Test
    @DisplayName("同一输入在不同实例上得到完全一致的响应")
    void sameInputProducesIdenticalResultsAcrossInstances() {
        ModelResponse fromFirst = new StubModelClient().complete(userRequest("有哪些手机"));
        ModelResponse fromSecond = new StubModelClient().complete(userRequest("有哪些手机"));

        assertThat(fromSecond).isEqualTo(fromFirst);
        assertThat(fromSecond.toolCalls().get(0).arguments())
                .isEqualTo(fromFirst.toolCalls().get(0).arguments());
    }

    @Test
    @DisplayName("callCount 记录已消费的调用次数")
    void callCountTracksCompletedCalls() {
        StubModelClient stub = new StubModelClient();

        assertThat(stub.callCount()).isZero();
        stub.complete(userRequest("有哪些手机"));
        stub.complete(userRequest("有哪些手机"));
        assertThat(stub.callCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("无需任何凭据即可离线运行")
    void runsOfflineWithoutCredentials() {
        // 不传入任何 Key、URL 或 HTTP 客户端，仅构造并调用即可工作
        StubModelClient stub = new StubModelClient();

        ModelResponse first = stub.complete(userRequest("有哪些平板"));
        ModelResponse second = stub.complete(toolFollowUp("有哪些平板", first.toolCalls().get(0)));

        assertThat(first.toolCalls().get(0).name()).isEqualTo("searchProducts");
        assertThat(second.content()).isEqualTo(NEUTRAL_ANSWER);
    }

    @Test
    @DisplayName("共享默认 Stub 隔离两个独立会话：A 首轮与独立会话 B 首轮都得到搜索工具调用，不被实例级默认序列消耗")
    void sharedDefaultStubKeepsIndependentConversationsIsolated() throws Exception {
        StubModelClient stub = new StubModelClient();

        // 会话 A 首轮：全新 user 问题，应得到 searchProducts 工具调用
        ModelRequest sessionAFirst = new ModelRequest(
                List.of(ModelMessage.system("系统规则"), ModelMessage.user("有哪些手机")),
                List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null);
        ModelResponse sessionAFirstResponse = stub.complete(sessionAFirst);

        assertThat(sessionAFirstResponse.content()).isNull();
        assertThat(sessionAFirstResponse.toolCalls()).hasSize(1);
        ModelToolCall sessionACall = sessionAFirstResponse.toolCalls().get(0);
        assertThat(sessionACall.name()).isEqualTo("searchProducts");
        assertThat(JSON.readTree(sessionACall.arguments()).path("keyword").asText()).isEqualTo("手机");

        // 会话 A 后续轮：上下文包含 assistant 工具调用与 tool 结果，应得到中性最终文本
        ModelRequest sessionAFollowUp = new ModelRequest(
                List.of(
                        ModelMessage.system("系统规则"),
                        ModelMessage.user("有哪些手机"),
                        ModelMessage.assistant(null, List.of(sessionACall)),
                        ModelMessage.tool(sessionACall.id(), "{\"products\": []}")),
                List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null);
        ModelResponse sessionAFollowUpResponse = stub.complete(sessionAFollowUp);

        assertThat(sessionAFollowUpResponse.toolCalls()).isEmpty();
        assertThat(sessionAFollowUpResponse.content()).isEqualTo(StubModelClient.DEFAULT_ANSWER);

        // 会话 B 首轮：独立的新 user 问题，仍必须得到 searchProducts 工具调用
        ModelRequest sessionBFirst = new ModelRequest(
                List.of(ModelMessage.system("系统规则"), ModelMessage.user("有哪些平板")),
                List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null);
        ModelResponse sessionBFirstResponse = stub.complete(sessionBFirst);

        assertThat(sessionBFirstResponse.content()).isNull();
        assertThat(sessionBFirstResponse.toolCalls()).hasSize(1);
        assertThat(sessionBFirstResponse.toolCalls().get(0).name()).isEqualTo("searchProducts");
        assertThat(JSON.readTree(sessionBFirstResponse.toolCalls().get(0).arguments())
                .path("keyword").asText()).isEqualTo("平板");
    }

    /**
     * 缺省行为的唯一触发条件是「最后一条消息 role=user」。只有这种独立的新 user 轮才应产出
     * {@code searchProducts} 工具调用，避免把已经处于对话中途（最后一条为 assistant/system）的请求
     * 误判为新的搜索意图。
     */
    @Test
    @DisplayName("缺省模式只在最后一条消息 role=user 时返回 searchProducts 工具调用")
    void defaultScriptOnlySearchesWhenLastMessageIsUser() throws Exception {
        StubModelClient stub = new StubModelClient();

        ModelResponse response = stub.complete(new ModelRequest(
                List.of(ModelMessage.system("系统规则"), ModelMessage.user("有哪些手机")),
                List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null));

        assertThat(response.content()).isNull();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("searchProducts");
        assertThat(JSON.readTree(response.toolCalls().get(0).arguments()).path("keyword").asText())
                .isEqualTo("手机");
    }

    /** 最后一条消息是 assistant 或 system（而非 tool/user）时，缺省模式必须回中性文本且不发工具调用。 */
    @Test
    @DisplayName("缺省模式在最后一条消息为 assistant 或 system 时返回中性文本且不发工具调用")
    void defaultScriptReturnsNeutralAnswerWhenLastMessageIsNotUser() {
        StubModelClient stub = new StubModelClient();

        ModelResponse assistantTail = stub.complete(new ModelRequest(
                List.of(
                        ModelMessage.user("有哪些手机"),
                        ModelMessage.assistant("请问您偏好哪个价位？", List.of())),
                List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null));
        assertThat(assistantTail.content()).isEqualTo(NEUTRAL_ANSWER);
        assertThat(assistantTail.toolCalls()).isEmpty();

        ModelResponse systemTail = stub.complete(new ModelRequest(
                List.of(ModelMessage.user("有哪些手机"), ModelMessage.system("系统规则")),
                List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null));
        assertThat(systemTail.content()).isEqualTo(NEUTRAL_ANSWER);
        assertThat(systemTail.toolCalls()).isEmpty();
    }

    /**
     * Stub 明确承诺「同一个默认实例可安全地同时服务多个彼此独立的会话」，因此预录脚本消费与
     * {@code callCount} 递增都必须并发安全：任何一条预录响应只能被消费一次，调用计数不得丢失更新。
     */
    @Test
    @DisplayName("高并发消费预录脚本时每条响应只被消费一次且 callCount 精确")
    void concurrentScriptedConsumptionIsThreadSafe() throws Exception {
        final int workers = 64;
        final int callsPerWorker = 8;
        final int totalCalls = workers * callsPerWorker;

        List<ModelResponse> scripted = IntStream.range(0, totalCalls)
                .mapToObj(index -> new ModelResponse("响应-" + index, List.of(), null, "stop"))
                .toList();
        StubModelClient stub = new StubModelClient(scripted);

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch startGate = new CountDownLatch(1);
        List<String> consumed = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                tasks.add(executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int call = 0; call < callsPerWorker; call++) {
                            ModelResponse response = stub.complete(userRequest("随便看看"));
                            consumed.add(String.valueOf(response.content()));
                        }
                    } catch (Throwable error) {
                        failures.add(error);
                    }
                }));
            }
            startGate.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(failures).as("并发消费预录脚本不得出现异常").isEmpty();
        assertThat(consumed).as("每次调用都必须返回一条预录响应").hasSize(totalCalls);
        assertThat(new HashSet<>(consumed))
                .as("每条预录响应只能被消费一次，不得重复或漏发")
                .hasSize(totalCalls);
        assertThat(stub.callCount()).as("callCount 必须精确等于调用次数").isEqualTo(totalCalls);
    }

    private static ModelRequest userRequest(String message) {
        return new ModelRequest(
                List.of(ModelMessage.user(message)), List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null);
    }

    /** 构造真实的工具后续轮上下文：system + user + assistant(tool_calls) + tool 结果。 */
    private static ModelRequest toolFollowUp(String userMessage, ModelToolCall call) {
        return new ModelRequest(
                List.of(
                        ModelMessage.system("系统规则"),
                        ModelMessage.user(userMessage),
                        ModelMessage.assistant(null, List.of(call)),
                        ModelMessage.tool(call.id(), "{\"products\": []}")),
                List.of(), ModelRequest.TOOL_CHOICE_AUTO, 0.2, null);
    }
}
