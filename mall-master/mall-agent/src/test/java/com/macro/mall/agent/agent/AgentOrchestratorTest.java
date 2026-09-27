package com.macro.mall.agent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.api.SessionMessage;
import com.macro.mall.agent.model.ModelClient;
import com.macro.mall.agent.model.ModelException;
import com.macro.mall.agent.model.ModelMessage;
import com.macro.mall.agent.model.ModelRequest;
import com.macro.mall.agent.model.ModelResponse;
import com.macro.mall.agent.model.ModelToolCall;
import com.macro.mall.agent.safety.RefusalPolicy;
import com.macro.mall.agent.session.SessionSnapshot;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.tools.AgentTool;
import com.macro.mall.agent.tools.ProductToolsTest;
import com.macro.mall.agent.tools.ToolContext;
import com.macro.mall.agent.tools.ToolRegistry;
import com.macro.mall.agent.tools.ToolResult;

/**
 * 受限工具循环的 TDD 契约测试（计划 Task 9 Step 1 / Step 2）。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.agent.orchestrator}：
 * 写操作拒绝优先于任何模型/门户调用；模型请求包含固定 system、历史与本轮 user 以及注册工具 schema；
 * {@code assistant(tool_calls)} 与对应 {@code tool} 消息严格按 ID/name/arguments/执行顺序追加；
 * 工具错误以围栏结构化结果反馈模型但绝不产生商品事实；未知工具与畸形参数零门户请求；
 * 最多执行配置轮次（默认 4），第 5 轮仍返回工具调用抛固定 422 {@link ToolRoundLimitException}；
 * 卡片只由本轮成功工具事实经 {@code ProductCardBuilder} 构造；
 * 最终文本按 Unicode 码点裁剪到 {@link ConversationBuilder#MAX_ANSWER_CHARS}，空答使用固定 fallback。
 *
 * <p>模型用测试内 Fake 驱动，门户用 Mockito 替身 + 既有脱敏 fixture，不访问任何外部服务。
 */
class AgentOrchestratorTest {

    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private MallPortalClient portal;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        portal = mock(MallPortalClient.class);
        given(portal.searchProducts(any())).willReturn(ProductToolsTest.searchPageFixture());
        // doThrow/doReturn 风格：避免在重设桩时触发上一条会抛异常的桩
        doThrow(PortalException.notFound()).when(portal).getProductDetail(anyLong());
        doReturn(ProductToolsTest.detailFixture()).when(portal).getProductDetail(27L);
        registry = ToolRegistry.defaultRegistry(FIXED_CLOCK);
    }

    // ------------------------------------------------------------------ //
    // 测试替身与构造
    // ------------------------------------------------------------------ //

    /** 按脚本顺序返回响应的确定性模型；脚本耗尽即断言失败，避免测试靠缺省行为蒙混过关。 */
    private static final class FakeModelClient implements ModelClient {

        private final Deque<ModelResponse> scripted;
        private final List<ModelRequest> requests = new ArrayList<>();

        FakeModelClient(ModelResponse... responses) {
            this.scripted = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            requests.add(request);
            if (scripted.isEmpty()) {
                throw new AssertionError("模型脚本已耗尽，但编排层仍然发起了第 " + requests.size() + " 次调用");
            }
            return scripted.poll();
        }

        int callCount() {
            return requests.size();
        }

        List<ModelRequest> requests() {
            return requests;
        }
    }

    /** 永远抛出门户异常的工具，用于验证编排层只向上传播固定分类异常。 */
    private static final class ThrowingPortalTool implements AgentTool {

        private static final String NAME = "throwingPortalTool";

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public String description() {
            return "测试替身：直接抛出门户异常";
        }

        @Override
        public boolean requiresMember() {
            return false;
        }

        @Override
        public Map<String, Object> parameters() {
            return AgentTool.objectSchema(Map.of(), List.of());
        }

        @Override
        public ToolResult invoke(JsonNode arguments, ToolContext context) {
            throw PortalException.unavailable();
        }
    }

    private AgentOrchestrator orchestratorWith(ModelClient model) {
        return new AgentOrchestrator(model, registry, portal, AgentLimits.defaults());
    }

    private AgentOrchestrator orchestratorWith(ModelClient model, AgentLimits limits) {
        return new AgentOrchestrator(model, registry, portal, limits);
    }

    private AgentTurnRequest request(String message) {
        return AgentTurnRequest.guest(message, SESSION_ID, SessionSnapshot.empty());
    }

    private static ModelResponse text(String content) {
        return new ModelResponse(content, List.of(), null, null);
    }

    private static ModelResponse toolCall(ModelToolCall... calls) {
        return new ModelResponse(null, List.of(calls), null, null);
    }

    private static ModelResponse searchToolCall(String callId) {
        return toolCall(new ModelToolCall(callId, "searchProducts", "{\"keyword\":\"手机\"}"));
    }

    /** 先执行一次搜索（产生服务端事实），再返回最终文本。 */
    private static FakeModelClient searchThen(String finalContent) {
        return new FakeModelClient(searchToolCall("c1"), text(finalContent));
    }

    /** 先执行一次商品详情（产生服务端事实），再返回最终文本。 */
    private static FakeModelClient detailThen(String finalContent) {
        return new FakeModelClient(
                toolCall(new ModelToolCall("c1", "getProductDetail", "{\"productId\":27}")), text(finalContent));
    }

    private static List<ModelMessage> messagesOf(ModelRequest request) {
        return request.messages();
    }

    private static String nameOfTool(Map<String, Object> tool) {
        return (String) functionOf(tool).get("name");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> functionOf(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("function");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parametersOfTool(Map<String, Object> tool) {
        return (Map<String, Object>) functionOf(tool).get("parameters");
    }

    private List<String> questionsOf(ModelClient model, String message) {
        return orchestratorWith(model).run(request(message)).suggestedQuestions();
    }

    // ------------------------------------------------------------------ //
    // 直接回答与模型请求构造
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("无工具调用时直接返回模型文本且不触达门户")
    void noToolCallReturnsModelTextDirectly() {
        FakeModelClient model = new FakeModelClient(text("你好，我可以帮你找商品。"));

        AgentTurnResult result = orchestratorWith(model).run(request("有什么推荐"));

        assertThat(result.answer()).isEqualTo("你好，我可以帮你找商品。");
        assertThat(result.products()).isEmpty();
        assertThat(result.requiresLogin()).isFalse();
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.toolRounds()).isZero();
        assertThat(result.stoppedReason()).isEqualTo(AgentTurnResult.StoppedReason.ANSWER);
        assertThat(result.suggestedQuestions()).hasSize(3);
        assertThat(model.callCount()).isEqualTo(1);
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("模型请求包含固定 system、历史与本轮 user 以及四个注册工具 schema")
    void modelRequestCarriesSystemHistoryAndToolSchemas() {
        FakeModelClient model = new FakeModelClient(text("好的。"));
        AgentTurnRequest request = AgentTurnRequest.guest("现在的问题", SESSION_ID,
                new SessionSnapshot(List.of(
                        new SessionMessage("user", "之前的问题"),
                        new SessionMessage("assistant", "之前的回答")), List.of()));

        orchestratorWith(model).run(request);

        ModelRequest sent = model.requests().get(0);
        assertThat(sent.toolChoice()).isEqualTo(ModelRequest.TOOL_CHOICE_AUTO);
        assertThat(sent.temperature()).isEqualTo(ModelRequest.DEFAULT_TEMPERATURE);

        List<ModelMessage> messages = messagesOf(sent);
        assertThat(messages).extracting(ModelMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        assertThat(messages.get(0).content())
                .contains("Mall 商城的商品导购助手")
                .contains("单次回答最多进行 4 轮工具调用")
                .contains("[[MALL_PRODUCTS: 商品ID,商品ID]]");
        assertThat(messages.get(1).content()).isEqualTo("之前的问题");
        assertThat(messages.get(2).content()).isEqualTo("之前的回答");
        assertThat(messages.get(3).content()).isEqualTo("现在的问题");

        // 持久化历史不得携带工具调用或原始工具/模型响应
        assertThat(messages.get(1).toolCalls()).isEmpty();
        assertThat(messages.get(1).toolCallId()).isNull();
        assertThat(messages.get(2).toolCalls()).isEmpty();
        assertThat(messages.get(2).toolCallId()).isNull();

        assertThat(sent.tools()).hasSize(4);
        assertThat(sent.tools()).extracting(AgentOrchestratorTest::nameOfTool).containsExactly(
                "searchProducts", "getProductDetail", "compareProducts", "getMemberCouponsForProduct");
        assertThat(sent.tools().get(0)).containsEntry("type", "function");
        assertThat(parametersOfTool(sent.tools().get(0)))
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", Boolean.FALSE);
    }

    @Test
    @DisplayName("会话历史只保留最近 maxMessages 条消息且不携带工具调用")
    void conversationKeepsRecentHistoryOnly() {
        List<SessionMessage> history = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            history.add(new SessionMessage(index % 2 == 0 ? "user" : "assistant", "历史 " + index));
        }

        List<ModelMessage> messages = ConversationBuilder.build(history, "现在的问题", 4, 20);

        // system + 最近 20 条历史 + 本轮 user
        assertThat(messages).hasSize(22);
        assertThat(messages.get(0).role()).isEqualTo("system");
        assertThat(messages.get(1).content()).isEqualTo("历史 5");
        assertThat(messages.get(21).content()).isEqualTo("现在的问题");
        assertThat(messages).allSatisfy(message -> {
            assertThat(message.toolCalls()).isEmpty();
            assertThat(message.toolCallId()).isNull();
        });
    }

    @Test
    @DisplayName("历史与本轮合计超过上下文字符上限时丢弃较早的完整轮次")
    void contextTrimDropsOldestTurnsWithinCharacterBound() {
        String longText = "字".repeat(5000);
        SessionSnapshot session = new SessionSnapshot(List.of(
                new SessionMessage("user", longText),
                new SessionMessage("assistant", longText),
                new SessionMessage("user", longText),
                new SessionMessage("assistant", longText),
                new SessionMessage("user", longText),
                new SessionMessage("assistant", longText)), List.of());
        FakeModelClient model = new FakeModelClient(text("好的。"));

        orchestratorWith(model).run(AgentTurnRequest.guest(longText, SESSION_ID, session));

        List<ModelMessage> messages = messagesOf(model.requests().get(0));
        // system + 最末一轮 user/assistant + 本轮 user
        assertThat(messages).hasSize(4);
        assertThat(messages).extracting(ModelMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        int nonSystemChars = messages.subList(1, messages.size()).stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        assertThat(nonSystemChars).isLessThanOrEqualTo(16000);
    }

    // ------------------------------------------------------------------ //
    // 工具调用协议
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("一轮工具调用严格按 assistant(tool_calls) 与 tool 消息顺序追加")
    void singleRoundToolProtocolOrder() {
        ModelToolCall call = new ModelToolCall("call_1", "searchProducts", "{\"keyword\":\"手机\"}");
        FakeModelClient model = new FakeModelClient(
                new ModelResponse("正在为您检索。", List.of(call), null, null),
                text("结果如下。\n[[MALL_PRODUCTS: 27]]"));

        AgentTurnResult result = orchestratorWith(model).run(request("找手机"));

        assertThat(result.toolCalls()).containsExactly(new ToolCallRecord("searchProducts", "OK", true));
        assertThat(result.toolRounds()).isEqualTo(1);
        assertThat(result.stoppedReason()).isEqualTo(AgentTurnResult.StoppedReason.ANSWER);
        assertThat(result.products()).extracting(ProductCard::id).containsExactly(27L);
        assertThat(result.answer()).isEqualTo("结果如下。");

        List<ModelMessage> second = messagesOf(model.requests().get(1));
        assertThat(second).extracting(ModelMessage::role)
                .containsExactly("system", "user", "assistant", "tool");

        ModelMessage assistant = second.get(2);
        assertThat(assistant.content()).isEqualTo("正在为您检索。");
        assertThat(assistant.toolCalls()).containsExactly(call);

        ModelMessage toolMessage = second.get(3);
        assertThat(toolMessage.toolCallId()).isEqualTo("call_1");
        assertThat(toolMessage.content())
                .startsWith("<<<MALL_UNTRUSTED_DATA>>>")
                .contains("\"tool\":\"searchProducts\"")
                .contains("\"status\":\"OK\"");

        verify(portal).searchProducts(any());
    }

    @Test
    @DisplayName("同一条 assistant 消息的多个工具调用按顺序执行并各自追加 tool 消息")
    void multipleToolCallsExecuteInOrder() {
        ModelToolCall first = new ModelToolCall("call_a", "searchProducts", "{\"keyword\":\"手机\"}");
        ModelToolCall second = new ModelToolCall("call_b", "getProductDetail", "{\"productId\":27}");
        FakeModelClient model = new FakeModelClient(
                new ModelResponse(null, List.of(first, second), null, null),
                text("done"));

        AgentTurnResult result = orchestratorWith(model).run(request("找手机并看详情"));

        assertThat(result.toolCalls()).extracting(ToolCallRecord::name)
                .containsExactly("searchProducts", "getProductDetail");
        assertThat(result.toolRounds()).isEqualTo(1);

        List<ModelMessage> second2 = messagesOf(model.requests().get(1));
        assertThat(second2).extracting(ModelMessage::role)
                .containsExactly("system", "user", "assistant", "tool", "tool");
        assertThat(second2.get(3).toolCallId()).isEqualTo("call_a");
        assertThat(second2.get(4).toolCallId()).isEqualTo("call_b");
        verify(portal).searchProducts(any());
        verify(portal).getProductDetail(27L);
    }

    @Test
    @DisplayName("多轮工具调用累计轮次，工具消息与调用一一对应")
    void multipleRoundsAccumulateToolMessages() {
        FakeModelClient model = new FakeModelClient(
                searchToolCall("c1"),
                toolCall(new ModelToolCall("c2", "getProductDetail", "{\"productId\":27}")),
                text("最终回答。"));

        AgentTurnResult result = orchestratorWith(model).run(request("两步查询"));

        assertThat(result.toolRounds()).isEqualTo(2);
        assertThat(result.toolCalls()).extracting(ToolCallRecord::name)
                .containsExactly("searchProducts", "getProductDetail");
        List<ModelMessage> third = messagesOf(model.requests().get(2));
        assertThat(third).extracting(ModelMessage::role)
                .containsExactly("system", "user", "assistant", "tool", "assistant", "tool");
        assertThat(third.get(3).toolCallId()).isEqualTo("c1");
        assertThat(third.get(5).toolCallId()).isEqualTo("c2");
    }

    // ------------------------------------------------------------------ //
    // 轮数上限
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("第 5 轮仍返回工具调用时抛出固定 TOOL_ROUND_LIMIT，且最多只执行 4 轮")
    void roundLimitThrowsFixedDomainException() {
        FakeModelClient model = new FakeModelClient(
                searchToolCall("c1"), searchToolCall("c2"), searchToolCall("c3"),
                searchToolCall("c4"), searchToolCall("c5"));

        assertThatThrownBy(() -> orchestratorWith(model).run(request("一直查")))
                .isInstanceOf(ToolRoundLimitException.class)
                .hasMessage("工具调用超过 4 轮，本次问题需要缩小范围")
                .satisfies(exception -> {
                    ToolRoundLimitException limit = (ToolRoundLimitException) exception;
                    assertThat(limit.httpStatus()).isEqualTo(422);
                    assertThat(limit.code()).isEqualTo("TOOL_ROUND_LIMIT");
                    assertThat(limit.maxToolRounds()).isEqualTo(4);
                });

        assertThat(model.callCount()).isEqualTo(5);
        verify(portal, times(4)).searchProducts(any());
    }

    @Test
    @DisplayName("配置为 1 轮时第二轮工具调用立即拒绝")
    void configuredRoundLimitIsEnforced() {
        FakeModelClient model = new FakeModelClient(searchToolCall("c1"), searchToolCall("c2"));
        AgentLimits limits = new AgentLimits(1, 20, 4000, 16000, 5, 3);

        assertThatThrownBy(() -> orchestratorWith(model, limits).run(request("查两次")))
                .isInstanceOf(ToolRoundLimitException.class)
                .hasMessage("工具调用超过 1 轮，本次问题需要缩小范围");
        assertThat(model.callCount()).isEqualTo(2);
        verify(portal, times(1)).searchProducts(any());
    }

    @Test
    @DisplayName("恰好用满轮次后模型给出最终文本时标记 ROUNDS_EXHAUSTED")
    void exhaustedRoundsAreReportedWhenFinalAnswerArrives() {
        FakeModelClient model = new FakeModelClient(
                searchToolCall("c1"), searchToolCall("c2"), searchToolCall("c3"), searchToolCall("c4"),
                text("最终回答。"));

        AgentTurnResult result = orchestratorWith(model).run(request("分四步查"));

        assertThat(result.toolRounds()).isEqualTo(4);
        assertThat(result.stoppedReason()).isEqualTo(AgentTurnResult.StoppedReason.ROUNDS_EXHAUSTED);
        assertThat(result.answer()).isEqualTo("最终回答。");
        assertThat(model.callCount()).isEqualTo(5);
        verify(portal, times(4)).searchProducts(any());
    }

    // ------------------------------------------------------------------ //
    // 工具错误与未知工具
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("工具失败以围栏结构化结果反馈模型但不产生商品事实卡")
    void failingToolProducesNoFactsButIsFedBackToModel() {
        doThrow(PortalException.timeout()).when(portal).getProductDetail(27L);
        ModelToolCall call = new ModelToolCall("c1", "getProductDetail", "{\"productId\":27}");
        FakeModelClient model = new FakeModelClient(
                new ModelResponse(null, List.of(call), null, null),
                text("详情接口暂时不可用。\n[[MALL_PRODUCTS: 27]]"));

        AgentTurnResult result = orchestratorWith(model).run(request("27 还有货吗"));

        assertThat(result.toolCalls()).containsExactly(new ToolCallRecord("getProductDetail", "ERROR", false));
        assertThat(result.products()).isEmpty();
        assertThat(result.answer()).isEqualTo("详情接口暂时不可用。");
        assertThat(messagesOf(model.requests().get(1)).get(3).content())
                .startsWith("<<<MALL_UNTRUSTED_DATA>>>")
                .contains("\"status\":\"ERROR\"")
                .contains("STOREFRONT_TIMEOUT");
    }

    @Test
    @DisplayName("未知工具与畸形参数以固定拒绝结果交模型继续处理且零门户请求")
    void unknownToolAndMalformedArgumentsNeverTouchPortal() {
        ModelToolCall unknown = new ModelToolCall("c1", "deleteProductIndex", "{}");
        ModelToolCall malformed = new ModelToolCall("c2", "searchProducts", "{\"keyword\":");
        FakeModelClient model = new FakeModelClient(
                new ModelResponse(null, List.of(unknown, malformed), null, null),
                text("我换个说法。\n[[MALL_PRODUCTS: 27]]"));

        AgentTurnResult result = orchestratorWith(model).run(request("帮我找找有没有新手机"));

        assertThat(result.toolCalls()).containsExactly(
                new ToolCallRecord("deleteProductIndex", "UNKNOWN_TOOL", false),
                new ToolCallRecord("searchProducts", "INVALID_TOOL_ARGUMENTS", false));
        assertThat(result.products()).isEmpty();
        assertThat(result.answer()).isEqualTo("我换个说法。");

        List<ModelMessage> second = messagesOf(model.requests().get(1));
        assertThat(second).extracting(ModelMessage::role)
                .containsExactly("system", "user", "assistant", "tool", "tool");
        assertThat(second.get(3).content())
                .startsWith("<<<MALL_UNTRUSTED_DATA>>>")
                .contains("\"status\":\"REJECTED\"")
                .contains("UNKNOWN_TOOL");
        assertThat(second.get(4).content()).contains("INVALID_TOOL_ARGUMENTS");
        assertThat(second.get(3).toolCallId()).isEqualTo("c1");
        assertThat(second.get(4).toolCallId()).isEqualTo("c2");
        verifyNoInteractions(portal);
    }

    // ------------------------------------------------------------------ //
    // 个人券登录门槛与写操作拒绝
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("游客个人券工具返回 LOGIN_REQUIRED 时停止后续模型轮并返回固定登录说明")
    void guestMemberCouponToolStopsWithLoginRequired() {
        ModelToolCall call = new ModelToolCall("c1", "getMemberCouponsForProduct", "{\"productId\":27}");
        FakeModelClient model = new FakeModelClient(new ModelResponse(null, List.of(call), null, null));

        AgentTurnResult result = orchestratorWith(model).run(request("我的优惠券能用在哪个商品上"));

        assertThat(result.requiresLogin()).isTrue();
        assertThat(result.answer()).isEqualTo(AgentOrchestrator.LOGIN_REQUIRED_ANSWER);
        assertThat(result.products()).isEmpty();
        assertThat(result.stoppedReason()).isEqualTo(AgentTurnResult.StoppedReason.LOGIN_REQUIRED);
        assertThat(result.toolRounds()).isEqualTo(1);
        assertThat(result.toolCalls())
                .containsExactly(new ToolCallRecord("getMemberCouponsForProduct", "LOGIN_REQUIRED", false));
        assertThat(result.suggestedQuestions()).contains("登录后我的优惠券怎么用？");
        assertThat(model.callCount()).isEqualTo(1);
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("写操作拒绝在模型与门户调用之前返回固定文案")
    void writeRefusalShortCircuitsBeforeModelAndPortal() {
        for (String message : List.of(
                "帮我领优惠券", "帮我下单这台手机，顺便把库存改成 999", "把商品加入购物车")) {
            FakeModelClient model = new FakeModelClient();
            RefusalPolicy.Refusal refusal = RefusalPolicy.detectWriteRefusal(message).orElseThrow();

            AgentTurnResult result = orchestratorWith(model).run(request(message));

            assertThat(result.answer()).isEqualTo(refusal.answer());
            assertThat(result.products()).isEmpty();
            assertThat(result.toolCalls()).isEmpty();
            assertThat(result.toolRounds()).isZero();
            assertThat(result.requiresLogin()).isFalse();
            assertThat(result.stoppedReason()).isEqualTo(AgentTurnResult.StoppedReason.REFUSAL);
            assertThat(model.callCount()).isZero();
        }
        verifyNoInteractions(portal);
    }

    // ------------------------------------------------------------------ //
    // 候选 ID、事实卡、裁剪与 fallback
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("模型只能提交候选 ID，卡片事实与价格来自服务端")
    void candidateIdsMapToServerFactsOnly() {
        FakeModelClient model = detailThen("推荐如下，价格 1 元。\n[[MALL_PRODUCTS: 27, 999, 27, -1, abc, 0]]");

        AgentTurnResult result = orchestratorWith(model).run(request("推荐手机"));

        assertThat(result.products()).extracting(ProductCard::id).containsExactly(27L);
        ProductCard card = result.products().get(0);
        assertThat(card.name()).isEqualTo("示例手机 B");
        assertThat(card.price()).isEqualTo("2999.00");
        assertThat(card.availableStock()).isEqualTo(112);
        assertThat(card.detailPath()).isEqualTo("/pages/product/product?id=27");
        assertThat(result.answer()).isEqualTo("推荐如下，价格 1 元。");
    }

    @Test
    @DisplayName("超长回答按 1500 个 Unicode 码点裁剪且不切开代理对")
    void longAnswerTruncatedByCodePointWithoutSplittingSurrogates() {
        String emoji = "\uD83D\uDE00";
        FakeModelClient model = new FakeModelClient(text(emoji.repeat(1501)));

        AgentTurnResult result = orchestratorWith(model).run(request("给我一段长文本"));

        String answer = result.answer();
        assertThat(ConversationBuilder.MAX_ANSWER_CHARS).isEqualTo(1500);
        assertThat(answer.codePointCount(0, answer.length())).isEqualTo(1500);
        assertThat(answer).isEqualTo(emoji.repeat(1500));
        assertThat(Character.isHighSurrogate(answer.charAt(answer.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("超长纯 ASCII 回答同样裁剪到 1500 码点")
    void asciiAnswerTruncatedToLimit() {
        FakeModelClient model = new FakeModelClient(text("a".repeat(1600)));

        AgentTurnResult result = orchestratorWith(model).run(request("给我一段长说明"));

        assertThat(result.answer()).hasSize(1500).isEqualTo("a".repeat(1500));
    }

    @Test
    @DisplayName("清理内部标记后为空时使用固定 fallback，但候选事实仍生效")
    void emptyAfterStrippingFallsBackToFixedAnswer() {
        FakeModelClient model = searchThen("[[MALL_PRODUCTS: 27]]");

        AgentTurnResult result = orchestratorWith(model).run(request("只给标记"));

        assertThat(result.answer()).isEqualTo(AgentOrchestrator.FALLBACK_ANSWER);
        assertThat(result.products()).extracting(ProductCard::id).containsExactly(27L);
    }

    @Test
    @DisplayName("模型返回空内容时使用固定 fallback 且无卡片")
    void nullContentFallsBackWithoutCards() {
        FakeModelClient model = new FakeModelClient(new ModelResponse(null, List.of(), null, null));

        AgentTurnResult result = orchestratorWith(model).run(request("给我一段说明"));

        assertThat(result.answer()).isEqualTo(AgentOrchestrator.FALLBACK_ANSWER);
        assertThat(result.products()).isEmpty();
    }

    @Test
    @DisplayName("建议问题模板随卡片数与登录状态切换")
    void suggestedQuestionsSwitchTemplates() {
        assertThat(questionsOf(new FakeModelClient(text("好的。")), "随便问问"))
                .containsExactly("3000 元左右有哪些手机？", "换个关键词再搜一次", "这个商品支持哪些优惠？");
        assertThat(questionsOf(searchThen("如下。\n[[MALL_PRODUCTS: 27]]"), "推荐一款"))
                .containsExactly("这款有哪些规格？", "这款的库存和发货情况如何？", "有没有同价位的其他选择？");
        assertThat(questionsOf(searchThen("如下。\n[[MALL_PRODUCTS: 27, 26]]"), "推荐两款"))
                .containsExactly("比较这几款的规格", "哪款库存更充足？", "有没有更便宜的同类商品？");
    }

    @Test
    @DisplayName("模板之外可从答案末句追加一条建议，并裁剪到 30 个 Unicode 码点")
    void suggestedQuestionsAppendAnswerTailAndCapLength() {
        String longQuestion = "这款手机在节假日期间会不会有额外的赠品或者优惠活动可以领取呢？";
        assertThat(longQuestion.codePointCount(0, longQuestion.length())).isGreaterThan(30);
        FakeModelClient model = searchThen("说明。\n[[MALL_PRODUCTS: 27]]\n" + longQuestion);

        AgentTurnResult result = orchestratorWith(model, new AgentLimits(4, 20, 4000, 16000, 5, 4))
                .run(request("推荐一款"));

        assertThat(result.suggestedQuestions()).hasSize(4);
        assertThat(result.suggestedQuestions().get(0)).isEqualTo("这款有哪些规格？");
        String appended = result.suggestedQuestions().get(3);
        assertThat(appended.codePointCount(0, appended.length())).isEqualTo(30);
        assertThat(appended).isEqualTo(longQuestion.substring(0, 30));
    }

    // ------------------------------------------------------------------ //
    // 异常安全传播
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("模型异常按既有固定分类向上传播且不触达门户")
    void modelExceptionsPropagateUnchanged() {
        record ModelFailure(ModelException exception, int status, String code) {
        }
        List<ModelFailure> failures = List.of(
                new ModelFailure(ModelException.protocol("模型响应结构非法"), 503, "MODEL_PROTOCOL_ERROR"),
                new ModelFailure(ModelException.unavailable("模型服务未配置"), 503, "MODEL_UNAVAILABLE"),
                new ModelFailure(ModelException.timeout("模型响应超时"), 502, "MODEL_TIMEOUT"),
                new ModelFailure(ModelException.upstream("模型上游失败"), 502, "MODEL_UPSTREAM_ERROR"));

        for (ModelFailure failure : failures) {
            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    ignored -> {
                        throw failure.exception();
                    }, registry, portal, AgentLimits.defaults());

            assertThatThrownBy(() -> orchestrator.run(request("找手机")))
                    .isSameAs(failure.exception());
            assertThat(failure.exception().httpStatus()).isEqualTo(failure.status());
            assertThat(failure.exception().code()).isEqualTo(failure.code());
        }
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("门户异常只以固定分类向上传播，不保留 Token 或上游正文")
    void portalExceptionPropagatesUnchanged() {
        ToolRegistry throwingRegistry = new ToolRegistry(List.of(new ThrowingPortalTool()));
        FakeModelClient model = new FakeModelClient(
                toolCall(new ModelToolCall("c1", "throwingPortalTool", "{}")));
        AgentOrchestrator orchestrator =
                new AgentOrchestrator(model, throwingRegistry, portal, AgentLimits.defaults());

        assertThatThrownBy(() -> orchestrator.run(request("查一下")))
                .isInstanceOf(PortalException.class)
                .satisfies(exception -> {
                    PortalException portalException = (PortalException) exception;
                    assertThat(portalException.httpStatus()).isEqualTo(502);
                    assertThat(portalException.code()).isEqualTo("STOREFRONT_UNAVAILABLE");
                    assertThat(portalException.getMessage()).doesNotContain("http://").doesNotContain("Bearer");
                });
        verifyNoInteractions(portal);
    }

    // ------------------------------------------------------------------ //
    // 会话落库辅助
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("会话摘要只保存用户消息、助手回答与最近卡片")
    void sessionUpdateKeepsUserAssistantAndRecentCards() {
        FakeModelClient model = searchThen("推荐如下。\n[[MALL_PRODUCTS: 27]]");
        AgentOrchestrator orchestrator = orchestratorWith(model);
        AgentTurnRequest request = request("推荐手机");
        AgentTurnResult result = orchestrator.run(request);

        SessionSnapshot snapshot = orchestrator.buildSessionUpdate(request, result);

        assertThat(snapshot.messages()).extracting(SessionMessage::role)
                .containsExactly("user", "assistant");
        assertThat(snapshot.messages().get(0).content()).isEqualTo("推荐手机");
        assertThat(snapshot.messages().get(1).content()).isEqualTo("推荐如下。");
        assertThat(snapshot.products()).extracting(ProductCard::id).containsExactly(27L);
    }
}
