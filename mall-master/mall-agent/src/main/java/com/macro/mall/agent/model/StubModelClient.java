package com.macro.mall.agent.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 确定性 Stub 模型，行为对齐 Python {@code model.stub.StubModelClient}。
 *
 * <p>只在 {@code MALL_AGENT_MODEL_MODE=stub} 时供离线评测与本地演示使用：不访问网络、不读取任何
 * 凭据、不编造商品事实。
 *
 * <p>可注入一组预录 {@link ModelResponse} 逐项确定性消费；脚本耗尽后回退到缺省行为，不会重复
 * 返回已消费的预录响应，因此不会跨调用泄漏响应。
 *
 * <p>缺省行为<strong>不依赖实例级会话状态</strong>，只依据当前 {@link ModelRequest} 的消息上下文，
 * 并且只在最后一条消息是 {@code user} 时才返回 {@code searchProducts} 工具调用
 * （{@code pageNum=1}、{@code sort=0}，关键词取自最后一条 {@code user} 消息并按固定优先级命中）；
 * 其他末尾角色（{@code tool} 结果、{@code assistant}、{@code system}，或消息列表为空）一律返回固定
 * 中性演示回答，不会把处于对话中途的请求误判为新的搜索意图。因此同一个默认实例可安全地同时服务
 * 多个彼此独立的会话，一轮调用不会影响另一轮。
 *
 * <p><strong>与 Python 原始实现的有意差异：</strong>Python {@code StubModelClient} 在实例上维护一条
 * 固定的「工具调用 → 中性回答」序列，第二次调用总是得到中性回答，因而同一个实例无法同时服务两个
 * 彼此独立的会话。此处改为按传入消息上下文判断，是对该实例级序列的有意修正。
 *
 * <p>并发安全：{@link #callCount()} 递增与预录脚本消费在同一个同步块内完成，因此同一个实例被多个
 * 会话并发调用时，每条预录响应只会被消费一次，调用计数也不会丢更新。
 */
public final class StubModelClient implements ModelClient {

    public static final String MODE = "stub";

    /** 固定中性演示回答，不含任何商品事实。 */
    public static final String DEFAULT_ANSWER =
            "我根据商城搜索结果为您列出候选商品，名称、价格和库存均来自商城实时数据；"
                    + "如需比较或查看库存，可以继续告诉我商品名称。";

    /** 关键词优先级（顺序即优先级）。 */
    public static final List<String> KEYWORD_HINTS = List.of(
            "手机", "平板", "笔记本", "耳机", "手表", "电视", "洗衣机", "空调", "相机", "男装", "女装", "鞋", "包");

    public static final String SEARCH_TOOL = "searchProducts";
    public static final String TOOL_CALL_ID = "stub_call_1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<ModelResponse> scripted;
    private final String defaultAnswer;
    private final List<String> keywordHints;

    private int callCount;

    /** 纯缺省模式：按输入派生固定的工具调用与中性回答。 */
    public StubModelClient() {
        this(List.of());
    }

    /** 预录脚本模式：逐项消费给定的响应，耗尽后回退到缺省行为。 */
    public StubModelClient(List<ModelResponse> scriptedResponses) {
        this(scriptedResponses, DEFAULT_ANSWER, KEYWORD_HINTS);
    }

    public StubModelClient(
            List<ModelResponse> scriptedResponses, String defaultAnswer, List<String> keywordHints) {
        this.scripted = new ArrayList<>(scriptedResponses == null ? List.of() : scriptedResponses);
        this.defaultAnswer = defaultAnswer;
        this.keywordHints = List.copyOf(keywordHints);
    }

    /** 模式标识，供上层区分 stub 与真实客户端。 */
    public String mode() {
        return MODE;
    }

    /** 已完成的调用次数。 */
    public synchronized int callCount() {
        return callCount;
    }

    @Override
    public synchronized ModelResponse complete(ModelRequest request) {
        callCount++;

        if (!scripted.isEmpty()) {
            return scripted.remove(0);
        }

        // 只依据当前请求上下文决定响应，不维护实例级会话状态：
        // 仅当最后一条消息是 user（独立的新搜索轮）才返回搜索工具调用；
        // tool 结果 / assistant / system / 空消息列表一律返回中性最终文本。
        if (endsWithUserMessage(request)) {
            return searchToolCallResponse(request);
        }
        return new ModelResponse(defaultAnswer, List.of(), null, null);
    }

    /** 缺省行为唯一触发条件：最后一条消息 role=user。 */
    private static boolean endsWithUserMessage(ModelRequest request) {
        List<ModelMessage> messages = request.messages();
        if (messages.isEmpty()) {
            return false;
        }
        return ModelMessage.ROLE_USER.equals(messages.get(messages.size() - 1).role());
    }

    private ModelResponse searchToolCallResponse(ModelRequest request) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("pageNum", 1);
        arguments.put("sort", 0);
        String keyword = extractKeyword(lastUserText(request));
        if (keyword != null) {
            arguments.put("keyword", keyword);
        }
        return new ModelResponse(
                null,
                List.of(new ModelToolCall(TOOL_CALL_ID, SEARCH_TOOL, writeJson(arguments))),
                null,
                null);
    }

    private String extractKeyword(String message) {
        for (String hint : keywordHints) {
            if (message.contains(hint)) {
                return hint;
            }
        }
        return null;
    }

    private static String lastUserText(ModelRequest request) {
        List<ModelMessage> messages = request.messages();
        for (int index = messages.size() - 1; index >= 0; index--) {
            ModelMessage message = messages.get(index);
            if (ModelMessage.ROLE_USER.equals(message.role())
                    && message.content() != null
                    && !message.content().isEmpty()) {
                return message.content();
            }
        }
        return "";
    }

    private static String writeJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stub 工具参数序列化失败", ex);
        }
    }
}
