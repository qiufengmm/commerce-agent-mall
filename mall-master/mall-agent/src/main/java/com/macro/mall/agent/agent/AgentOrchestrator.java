package com.macro.mall.agent.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.api.PythonText;
import com.macro.mall.agent.api.SessionMessage;
import com.macro.mall.agent.model.ModelClient;
import com.macro.mall.agent.model.ModelMessage;
import com.macro.mall.agent.model.ModelRequest;
import com.macro.mall.agent.model.ModelResponse;
import com.macro.mall.agent.model.ModelToolCall;
import com.macro.mall.agent.presentation.ProductCardBuilder;
import com.macro.mall.agent.presentation.ProductFact;
import com.macro.mall.agent.presentation.ProductFactCollector;
import com.macro.mall.agent.safety.RefusalPolicy;
import com.macro.mall.agent.safety.UntrustedTextFence;
import com.macro.mall.agent.session.SessionSnapshot;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.tools.ToolContext;
import com.macro.mall.agent.tools.ToolRegistry;
import com.macro.mall.agent.tools.ToolResult;

/**
 * 受限工具循环编排，行为对照 Python
 * {@code mall_shopping_agent.agent.orchestrator.ShoppingAgentOrchestrator}。
 *
 * <p>不变量：
 * <ul>
 *   <li>交易写操作拒绝优先于一切模型与门户调用（{@link RefusalPolicy}）。</li>
 *   <li>模型请求由 {@link ConversationBuilder} 构造，携带固定 system、历史与本轮 user，
 *       以及注册表暴露的 JSON tool schema 与 {@code tool_choice=auto}。</li>
 *   <li>每个工具调用先经 {@link ToolRegistry} 校验：未知工具与畸形参数在触达门户前被拒绝，
 *       并以围栏内的固定结构化结果反馈模型继续处理。</li>
 *   <li>工具失败只以结构化错误反馈模型，绝不进入 {@link ProductFactCollector}，因此不会产生事实卡。</li>
 *   <li>会员工具返回 {@code LOGIN_REQUIRED} 时置 {@code requiresLogin}、停止后续模型轮并给出固定登录说明。</li>
 *   <li>最多执行 {@link AgentLimits#maxToolRounds()} 轮；第 N+1 轮仍返回工具调用时抛
 *       {@link ToolRoundLimitException}（422 / {@code TOOL_ROUND_LIMIT}）。</li>
 *   <li>商品卡片只由本轮成功工具事实经 {@link ProductCardBuilder} 构造，模型只能提交候选 ID。</li>
 *   <li>最终回答按 Python 规则剥离内部标记、必要时回退固定文案，并裁剪到
 *       {@link ConversationBuilder#MAX_ANSWER_CHARS} 个 Unicode 码点（不切开代理对）。</li>
 * </ul>
 *
 * <p>异常语义：模型异常（{@link com.macro.mall.agent.model.ModelException}）与门户异常
 * （{@link com.macro.mall.agent.storefront.PortalException}）都是既有固定分类异常，编排层原样向上传播，
 * 不包装、不改写，也不记录其文本。本类不写日志，从源头避免提示词、商品全量响应或凭据进入日志。
 *
 * <p>本类为纯 Java 组件：不依赖 Spring 容器、HTTP、会话存储或 Redis。
 */
public final class AgentOrchestrator {

    /** 清理后为空时的固定兜底回答，逐字符对齐 Python {@code FALLBACK_ANSWER}。 */
    public static final String FALLBACK_ANSWER =
            "抱歉，我暂时无法根据当前商品信息给出回答，请换个说法或缩小问题范围再试一次。";

    /** 轮数超限时供 API 层使用的固定说明，逐字符对齐 Python {@code ROUND_LIMIT_ANSWER}。 */
    public static final String ROUND_LIMIT_ANSWER =
            "本次问题涉及的信息过多，请缩小范围后重试，例如只比较两件商品或指定一个关键词。";

    /** 个人券身份门槛命中的固定登录说明，逐字符对齐 Python {@code LOGIN_REQUIRED_ANSWER}。 */
    public static final String LOGIN_REQUIRED_ANSWER = "查询您本人的优惠券信息需要先登录，登录后可以继续当前对话。";

    /** 单条建议问题的 Unicode 码点上限，对齐 Python {@code MAX_QUESTION_CHARS}。 */
    public static final int MAX_QUESTION_CHARS = 30;

    /** 从回答末句提取建议问题时的原文长度上限，对齐 Python {@code MAX_QUESTION_SOURCE_CHARS}。 */
    public static final int MAX_QUESTION_SOURCE_CHARS = 60;

    /** 工具拒绝结果进入模型前的字符上限，对齐 Python {@code fence_json(..., max_chars=800)}。 */
    public static final int MAX_TOOL_ERROR_CHARS = 800;

    /** 缺省建议问题，对齐 Python {@code agent.types.DEFAULT_SUGGESTED_QUESTIONS}。 */
    public static final List<String> DEFAULT_SUGGESTED_QUESTIONS = List.of(
            "3000 元左右有哪些手机？",
            "比较一下前两款的规格",
            "哪款库存更充足？",
            "这个商品支持哪些优惠？");

    private static final Pattern PRODUCT_SELECTION_PATTERN =
            Pattern.compile("\\[\\[MALL_PRODUCTS:\\s*([^\\]]*)\\]\\]");

    private static final List<String> LOGIN_QUESTIONS = List.of(
            "登录后我的优惠券怎么用？", "登录后能查哪些优惠？", "登录后我的优惠券还有效吗？");
    private static final List<String> MULTI_CARD_QUESTIONS = List.of(
            "比较这几款的规格", "哪款库存更充足？", "有没有更便宜的同类商品？");
    private static final List<String> SINGLE_CARD_QUESTIONS = List.of(
            "这款有哪些规格？", "这款的库存和发货情况如何？", "有没有同价位的其他选择？");
    private static final List<String> GENERIC_QUESTIONS = List.of(
            "3000 元左右有哪些手机？", "换个关键词再搜一次", "这个商品支持哪些优惠？");

    private final ModelClient model;
    private final ToolRegistry registry;
    private final MallPortalClient backend;
    private final AgentLimits limits;
    private final ProductCardBuilder cardBuilder;

    public AgentOrchestrator(
            ModelClient model, ToolRegistry registry, MallPortalClient backend, AgentLimits limits) {
        this.model = Objects.requireNonNull(model, "model 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.backend = Objects.requireNonNull(backend, "backend 不能为空");
        this.limits = Objects.requireNonNull(limits, "limits 不能为空");
        this.cardBuilder = new ProductCardBuilder(limits.maxCards());
    }

    /** 使用缺省边界（4 轮 / 20 条 / 4000 / 16000 / 5 卡 / 3 问）。 */
    public AgentOrchestrator(ModelClient model, ToolRegistry registry, MallPortalClient backend) {
        this(model, registry, backend, AgentLimits.defaults());
    }

    // ------------------------------------------------------------------ //
    // 主流程
    // ------------------------------------------------------------------ //

    public AgentTurnResult run(AgentTurnRequest request) {
        Objects.requireNonNull(request, "request 不能为空");

        // 交易写操作直接拒绝：不调用模型，也不触发任何工具
        Optional<RefusalPolicy.Refusal> refusal = RefusalPolicy.detectWriteRefusal(request.message());
        if (refusal.isPresent()) {
            return new AgentTurnResult(
                    refusal.get().answer(),
                    List.of(),
                    false,
                    defaultSuggestedQuestions(),
                    List.of(),
                    0,
                    AgentTurnResult.StoppedReason.REFUSAL);
        }

        List<ModelMessage> messages = ConversationBuilder.build(
                request.session().messages(), request.message(), limits.maxToolRounds(), limits.maxMessages());
        ToolContext context = new ToolContext(backend, request.authorization());
        List<Map<String, Object>> tools = registry.openAiTools();

        ProductFactCollector collector = new ProductFactCollector();
        List<ToolCallRecord> records = new ArrayList<>();
        boolean requiresLogin = false;
        int rounds = 0;
        String answerText = "";
        AgentTurnResult.StoppedReason stoppedReason = AgentTurnResult.StoppedReason.ANSWER;

        while (true) {
            messages = trimContext(messages);
            ModelResponse response = model.complete(new ModelRequest(
                    messages, tools, ModelRequest.TOOL_CHOICE_AUTO, ModelRequest.DEFAULT_TEMPERATURE, null));

            if (response.toolCalls().isEmpty()) {
                answerText = response.content() == null ? "" : response.content();
                break;
            }

            if (rounds >= limits.maxToolRounds()) {
                throw new ToolRoundLimitException(limits.maxToolRounds());
            }
            rounds++;
            messages.add(ModelMessage.assistant(response.content(), response.toolCalls()));

            for (ModelToolCall call : response.toolCalls()) {
                ToolResult result;
                try {
                    result = registry.invoke(call.name(), call.arguments(), context);
                } catch (ToolRegistry.ToolException exception) {
                    records.add(new ToolCallRecord(call.name(), exception.code(), false));
                    messages.add(ModelMessage.tool(call.id(), toolErrorContent(call.name(), exception)));
                    continue;
                }

                records.add(new ToolCallRecord(result.name(), result.status().name(), result.ok()));
                if (result.status() == ToolResult.Status.LOGIN_REQUIRED) {
                    requiresLogin = true;
                }
                collector.add(result);
                messages.add(ModelMessage.tool(call.id(), result.toModelContent(limits.toolResultMaxChars())));
            }

            if (requiresLogin) {
                // 会员身份失效时不再继续调用模型，避免拿无效 Token 反复请求会员接口
                answerText = LOGIN_REQUIRED_ANSWER;
                stoppedReason = AgentTurnResult.StoppedReason.LOGIN_REQUIRED;
                break;
            }
        }

        if (stoppedReason == AgentTurnResult.StoppedReason.ANSWER && rounds >= limits.maxToolRounds()) {
            stoppedReason = AgentTurnResult.StoppedReason.ROUNDS_EXHAUSTED;
        }

        Map<Long, ProductFact> facts = collector.facts();
        String cleanAnswer = stripInternalMarkers(answerText);
        if (cleanAnswer.isEmpty()) {
            cleanAnswer = FALLBACK_ANSWER;
        }
        List<ProductCard> products = cardBuilder.build(parseProductSelection(answerText), facts);
        List<String> questions = buildSuggestedQuestions(
                products, requiresLogin, cleanAnswer, limits.maxSuggestedQuestions());
        String boundedAnswer = truncate(cleanAnswer, ConversationBuilder.MAX_ANSWER_CHARS);

        return new AgentTurnResult(
                boundedAnswer, products, requiresLogin, questions, records, rounds, stoppedReason);
    }

    /**
     * 生成会话落库内容：只保存用户消息、助手摘要与最近一组卡片，不保存工具或模型原始响应。
     *
     * <p>供服务层的会话写回使用；本方法为纯函数，不接触任何存储。
     */
    public SessionSnapshot buildSessionUpdate(AgentTurnRequest request, AgentTurnResult result) {
        Objects.requireNonNull(request, "request 不能为空");
        Objects.requireNonNull(result, "result 不能为空");
        int maxChars = SessionSnapshot.MAX_MESSAGE_CHARS;
        List<SessionMessage> messages = new ArrayList<>(request.session().messages());
        messages.add(new SessionMessage("user", truncate(request.message(), maxChars)));
        messages.add(new SessionMessage("assistant", truncate(result.answer(), maxChars)));
        return new SessionSnapshot(messages, result.products()).trimmed(limits.maxMessages());
    }

    /** 轮数超限时对外使用的固定说明。 */
    public String roundLimitAnswer() {
        return ROUND_LIMIT_ANSWER;
    }

    // ------------------------------------------------------------------ //
    // 回答解析与建议问题
    // ------------------------------------------------------------------ //

    /** 解析模型给出的候选商品 ID；非法值（非十进制、非正、重复）会被忽略。 */
    public static List<Long> parseProductSelection(String text) {
        List<Long> selected = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return selected;
        }
        Matcher matcher = PRODUCT_SELECTION_PATTERN.matcher(text);
        while (matcher.find()) {
            for (String chunk : matcher.group(1).split(",", -1)) {
                Long value = positiveDecimalOrNull(PythonText.strip(chunk));
                if (value == null || value < 1 || selected.contains(value)) {
                    continue;
                }
                selected.add(value);
            }
        }
        return selected;
    }

    /** 剥离内部的候选商品标记，并按 Python {@code str.strip()} 去空白。 */
    public static String stripInternalMarkers(String text) {
        String body = text == null ? "" : text;
        return PythonText.strip(PRODUCT_SELECTION_PATTERN.matcher(body).replaceAll(""));
    }

    /**
     * 受控模板 + 模型文本末句共同产生建议问题，最多 {@code limit} 条。
     *
     * <p>每条按 Unicode 码点裁剪到 {@link #MAX_QUESTION_CHARS}，语义对齐 Python
     * {@code build_suggested_questions}。
     */
    public static List<String> buildSuggestedQuestions(
            List<ProductCard> cards, boolean requiresLogin, String answer, int limit) {
        int cardCount = cards == null ? 0 : cards.size();
        List<String> templates;
        if (requiresLogin) {
            templates = LOGIN_QUESTIONS;
        } else if (cardCount >= 2) {
            templates = MULTI_CARD_QUESTIONS;
        } else if (cardCount == 1) {
            templates = SINGLE_CARD_QUESTIONS;
        } else {
            templates = GENERIC_QUESTIONS;
        }

        List<String> questions = new ArrayList<>(templates);
        String[] lines = (answer == null ? "" : answer).split("\n", -1);
        for (int index = lines.length - 1; index >= 0; index--) {
            String text = PythonText.strip(lines[index]);
            int codePoints = text.codePointCount(0, text.length());
            if (codePoints >= 2
                    && codePoints <= MAX_QUESTION_SOURCE_CHARS
                    && (text.endsWith("？") || text.endsWith("?"))
                    && !questions.contains(text)) {
                questions.add(text);
                break;
            }
        }

        int bound = Math.min(Math.max(1, limit), questions.size());
        List<String> result = new ArrayList<>(bound);
        for (int index = 0; index < bound; index++) {
            result.add(truncate(questions.get(index), MAX_QUESTION_CHARS));
        }
        return result;
    }

    // ------------------------------------------------------------------ //
    // 上下文裁剪
    // ------------------------------------------------------------------ //

    /**
     * 只保留下 system 与最近的有效对话，并在超过 {@link AgentLimits#contextMaxChars()} 时
     * 丢弃较早的完整轮次（对齐 Python {@code _trim_context}）。
     */
    private List<ModelMessage> trimContext(List<ModelMessage> messages) {
        List<ModelMessage> system = new ArrayList<>();
        List<ModelMessage> rest = new ArrayList<>();
        for (ModelMessage message : messages) {
            if (ModelMessage.ROLE_SYSTEM.equals(message.role())) {
                system.add(message);
            } else {
                rest.add(message);
            }
        }

        List<Integer> userBoundaries = new ArrayList<>();
        for (int index = 0; index < rest.size(); index++) {
            if (ModelMessage.ROLE_USER.equals(rest.get(index).role())) {
                userBoundaries.add(index);
            }
        }
        if (!userBoundaries.isEmpty()) {
            int start = userBoundaries.get(0);
            for (int index : userBoundaries) {
                start = index;
                if (rest.size() - index <= limits.maxMessages()) {
                    break;
                }
            }
            rest = new ArrayList<>(rest.subList(start, rest.size()));
        } else if (rest.size() > limits.maxMessages()) {
            rest = new ArrayList<>(rest.subList(rest.size() - limits.maxMessages(), rest.size()));
        }

        while (totalChars(rest) > limits.contextMaxChars()) {
            List<ModelMessage> advanced = advanceToNextUser(rest);
            if (advanced == rest || advanced.size() == rest.size()) {
                break;
            }
            rest = advanced;
        }

        List<ModelMessage> result = new ArrayList<>(system.size() + rest.size());
        result.addAll(system);
        result.addAll(rest);
        return result;
    }

    private static List<ModelMessage> advanceToNextUser(List<ModelMessage> messages) {
        for (int index = 1; index < messages.size(); index++) {
            if (ModelMessage.ROLE_USER.equals(messages.get(index).role())) {
                return new ArrayList<>(messages.subList(index, messages.size()));
            }
        }
        return messages;
    }

    private static int totalChars(List<ModelMessage> messages) {
        int total = 0;
        for (ModelMessage message : messages) {
            total += codePoints(message.content());
            for (ModelToolCall call : message.toolCalls()) {
                total += codePoints(call.arguments());
                total += codePoints(call.name());
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private static String toolErrorContent(String name, ToolRegistry.ToolException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", name);
        body.put("status", "REJECTED");
        body.put("errorCode", exception.code());
        body.put("errorMessage", exception.getMessage());
        return UntrustedTextFence.fenceJson(body, "tool_error", MAX_TOOL_ERROR_CHARS);
    }

    private List<String> defaultSuggestedQuestions() {
        int bound = Math.min(Math.max(1, limits.maxSuggestedQuestions()), DEFAULT_SUGGESTED_QUESTIONS.size());
        return List.copyOf(DEFAULT_SUGGESTED_QUESTIONS.subList(0, bound));
    }

    private static int codePoints(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0 || text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars));
    }

    /**
     * 只接受十进制正整数文本；非数字、正负号、小数、空串与超出 signed long 的取值都返回 {@code null}。
     *
     * <p>与 Python {@code str.isdigit() + int()} 对齐（Python 的 {@code int} 为任意精度，
     * Java 只有 signed long，溢出按非法值忽略，不会影响卡片事实）。
     */
    private static Long positiveDecimalOrNull(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        long value = 0L;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int digit = Character.digit(codePoint, 10);
            if (digit < 0 || !Character.isDigit(codePoint)) {
                return null;
            }
            if (value > (Long.MAX_VALUE - digit) / 10) {
                return null;
            }
            value = value * 10 + digit;
            index += Character.charCount(codePoint);
        }
        return value;
    }
}
