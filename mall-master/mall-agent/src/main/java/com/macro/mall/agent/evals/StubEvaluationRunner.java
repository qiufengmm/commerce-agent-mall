package com.macro.mall.agent.evals;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.macro.mall.agent.agent.AgentLimits;
import com.macro.mall.agent.agent.AgentOrchestrator;
import com.macro.mall.agent.agent.AgentTurnRequest;
import com.macro.mall.agent.agent.AgentTurnResult;
import com.macro.mall.agent.agent.ToolCallRecord;
import com.macro.mall.agent.model.ModelClient;
import com.macro.mall.agent.model.ModelMessage;
import com.macro.mall.agent.model.ModelRequest;
import com.macro.mall.agent.session.SessionSnapshot;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.tools.ToolRegistry;

/**
 * 离线 Stub 对话评测驱动，行为对照 Python {@code tests.support.eval_harness}。
 *
 * <p>对每条 {@link EvaluationCase} 用真实 {@link AgentOrchestrator} 运行一次问答，并断言：
 * 未注册工具、允许/禁止工具、工具调用次数上限、{@code requiresLogin}、卡片数、实际触达的
 * {@link PortalOperationAudit 门户只读操作}是否落在 Python {@code _expected_backend_operations}
 * 允许集内，以及「受围栏 {@code tool} 消息 + 最终回答」证据中的必需/禁止事实。
 *
 * <p>本类<strong>不</strong>创建任何模型客户端或门户客户端：两者由调用方通过
 * {@link ModelHarnessFactory} 与 {@link MallPortalClient} 注入（测试侧使用确定性 Stub 模型与
 * Mockito 门户替身），因此评测完全离线，不接触真实模型供应商、mall-portal、Redis、
 * MySQL、ES 或 RabbitMQ，也不依赖任何凭据。
 */
public final class StubEvaluationRunner {

    /** 评测使用的合成会话 ID（非任何真实会话）。 */
    public static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";

    /** 评测使用的合成会员 ID（非任何真实会员）。 */
    public static final long DEFAULT_MEMBER_ID = 7L;

    /** 评测使用的合成会员 Token（占位值，不是真实凭据）。 */
    public static final String MEMBER_TOKEN = "Bearer placeholder-member-token";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一条用例所需的确定性模型与可复核的模型请求记录。 */
    public interface ModelHarness {

        ModelClient model();

        /** 模型实际收到的请求（用于复核受围栏 {@code tool} 消息中的事实）。 */
        List<ModelRequest> requests();
    }

    /** 按 Stub 对话脚本 ID 创建模型替身；{@code null} 表示该用例不应调用模型。 */
    public interface ModelHarnessFactory {

        ModelHarness create(String stubConversationId);
    }

    /**
     * 必需注入的门户操作审计 seam，行为对照 Python {@code FakeStorefrontBackend.call_names}。
     *
     * <p>调用方在真实门户替身上记录每条用例实际触达的只读门户方法名（含 {@code resolveMember}）；
     * {@link StubEvaluationRunner} 在每条用例开始前 {@link #reset()}、结束后读取 {@link #operations()}，
     * 据此复核「工具名允许」之外的<strong>真实门户调用</strong>。生产源不依赖任何测试工具，
     * 也没有默认 NOOP 实现，避免审计被静默绕过。
     */
    public interface PortalOperationAudit {

        /** 清空当前记录；每条用例开始前由 runner 调用。 */
        void reset();

        /** 自上次 {@link #reset()} 以来按发生顺序记录的门户只读方法名。 */
        List<String> operations();
    }

    /**
     * 单条用例结果。
     *
     * @param caseId          用例 ID
     * @param category        用例类别
     * @param passed          是否通过
     * @param problems        失败原因
     * @param toolNames       实际执行的工具调用名
     * @param portalOperations 实际触达的门户只读方法名（按发生顺序，可复核）
     * @param unknownTools    未注册的工具名
     */
    public record CaseOutcome(
            String caseId,
            String category,
            boolean passed,
            List<String> problems,
            List<String> toolNames,
            List<String> portalOperations,
            List<String> unknownTools) {

        public CaseOutcome {
            problems = List.copyOf(problems);
            toolNames = List.copyOf(toolNames);
            portalOperations = List.copyOf(portalOperations);
            unknownTools = List.copyOf(unknownTools);
        }
    }

    /**
     * 整体评测报告。
     *
     * @param total    用例总数
     * @param passed   通过数
     * @param failures 逐条失败摘要 {@code id: 原因}
     * @param outcomes 逐例结果
     */
    public record Report(int total, int passed, List<String> failures, List<CaseOutcome> outcomes) {

        public Report {
            failures = List.copyOf(failures);
            outcomes = List.copyOf(outcomes);
        }

        /**
         * 仅在汇总自洽且全部通过时为真：除「无失败且 {@code passed == total}」外，还要求逐例结果数与
         * {@code total} 一致，避免 {@code total/passed} 与 {@code outcomes} 不一致时误报通过。
         */
        public boolean allPassed() {
            return total > 0 && failures.isEmpty() && passed == total && outcomes.size() == total;
        }

        /** 逐例可读结果，便于写入报告与人工复核。 */
        public List<String> summaryLines() {
            List<String> lines = new ArrayList<>(outcomes.size());
            for (CaseOutcome outcome : outcomes) {
                StringBuilder line = new StringBuilder()
                        .append("[eval] id=").append(outcome.caseId())
                        .append(" category=").append(outcome.category())
                        .append(" result=").append(outcome.passed() ? "PASS" : "FAIL")
                        .append(" tools=").append(outcome.toolNames())
                        .append(" portalOps=").append(outcome.portalOperations());
                if (!outcome.problems().isEmpty()) {
                    line.append(" problems=").append(outcome.problems());
                }
                lines.add(line.toString());
            }
            return lines;
        }
    }

    private final ModelHarnessFactory harnesses;
    private final MallPortalClient portal;
    private final ToolRegistry registry;
    private final AgentLimits limits;
    private final PortalOperationAudit audit;

    public StubEvaluationRunner(
            ModelHarnessFactory harnesses,
            MallPortalClient portal,
            ToolRegistry registry,
            AgentLimits limits,
            PortalOperationAudit audit) {
        this.harnesses = Objects.requireNonNull(harnesses, "harnesses 不能为空");
        this.portal = Objects.requireNonNull(portal, "portal 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.limits = Objects.requireNonNull(limits, "limits 不能为空");
        this.audit = Objects.requireNonNull(audit, "audit 不能为空");
    }

    /** 根对象允许的字段（对齐 Python {@code EvalCaseSet} 的 {@code extra="forbid"}）。 */
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("schemaVersion", "cases");

    /** 单条用例允许的字段（对齐 Python {@code EvalCase} 的 {@code extra="forbid"}）。 */
    private static final Set<String> CASE_FIELDS = Set.of(
            "id", "category", "message", "identity", "allowedTools", "forbiddenTools",
            "requiresLogin", "maxToolCalls", "requiredFacts", "forbiddenFacts",
            "productCardCount", "stubConversationId");

    /**
     * 从 JSON 输入读取评测用例；结构非法或引用未注册工具时抛 {@link IllegalArgumentException}。
     *
     * <p>校验与 Python {@code EvalCaseSet}/{@code EvalCase} 对齐并保持安全：根必须是对象且只含
     * {@code schemaVersion}/{@code cases}；{@code schemaVersion} 为不小于 1 的整数；{@code cases} 为
     * 非空数组；每条用例必须显式提供 {@code id/category/message/maxToolCalls/productCardCount} 且类型
     * 正确（拒绝 null、浮点、字符串代整数与负数），拒绝未知字段与重复 id，缺省字段回落 Python 默认值。
     * 所有失败只抛固定文案，绝不回显原始 JSON 取值。
     */
    public static List<EvaluationCase> loadCases(InputStream json) {
        if (json == null) {
            throw new IllegalArgumentException("评测用例输入不能为空");
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("评测用例结构非法：输入不是合法 JSON");
        } catch (IOException exception) {
            throw new IllegalArgumentException("评测用例读取失败", exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("评测用例结构非法：根对象必须是 JSON 对象");
        }
        for (Iterator<String> fields = root.fieldNames(); fields.hasNext(); ) {
            if (!TOP_LEVEL_FIELDS.contains(fields.next())) {
                throw new IllegalArgumentException("评测用例结构非法：存在未知的顶层字段");
            }
        }

        JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || schemaVersion.isNull()) {
            throw new IllegalArgumentException("评测用例结构非法：缺少 schemaVersion");
        }
        if (!schemaVersion.isIntegralNumber()
                || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() < 1) {
            throw new IllegalArgumentException("评测用例结构非法：schemaVersion 必须是不小于 1 的整数");
        }

        JsonNode cases = root.get("cases");
        if (cases == null || !cases.isArray() || cases.isEmpty()) {
            throw new IllegalArgumentException("评测用例结构非法：缺少非空 cases 数组");
        }

        List<EvaluationCase> result = new ArrayList<>(cases.size());
        Set<String> seenIds = new LinkedHashSet<>();
        for (JsonNode node : cases) {
            EvaluationCase evalCase = parseCase(node);
            if (!seenIds.add(evalCase.id())) {
                throw new IllegalArgumentException("评测用例结构非法：存在重复的用例 id");
            }
            result.add(evalCase);
        }
        return List.copyOf(result);
    }

    /** 解析单条用例；缺字段、类型错误、未知字段与负数都在这里拒绝。 */
    private static EvaluationCase parseCase(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("评测用例结构非法：用例必须是 JSON 对象");
        }
        for (Iterator<String> fields = node.fieldNames(); fields.hasNext(); ) {
            if (!CASE_FIELDS.contains(fields.next())) {
                throw new IllegalArgumentException("评测用例结构非法：用例存在未知字段");
            }
        }

        String id = requiredText(node, "id");
        String category = requiredText(node, "category");
        String message = requiredText(node, "message");
        int maxToolCalls = requiredInt(node, "maxToolCalls");
        int productCardCount = requiredInt(node, "productCardCount");

        String identity = null;
        JsonNode identityNode = node.get("identity");
        if (identityNode != null) {
            if (!identityNode.isTextual()) {
                throw fieldType("identity");
            }
            identity = identityNode.asText();
        }

        List<String> allowedTools = optionalTextArray(node, "allowedTools");
        List<String> forbiddenTools = optionalTextArray(node, "forbiddenTools");
        List<String> requiredFacts = optionalTextArray(node, "requiredFacts");
        List<String> forbiddenFacts = optionalTextArray(node, "forbiddenFacts");

        boolean requiresLogin = false;
        JsonNode requiresLoginNode = node.get("requiresLogin");
        if (requiresLoginNode != null) {
            if (!requiresLoginNode.isBoolean()) {
                throw fieldType("requiresLogin");
            }
            requiresLogin = requiresLoginNode.booleanValue();
        }

        String stubConversationId = null;
        JsonNode stubConversationNode = node.get("stubConversationId");
        if (stubConversationNode != null && !stubConversationNode.isNull()) {
            if (!stubConversationNode.isTextual()) {
                throw fieldType("stubConversationId");
            }
            stubConversationId = stubConversationNode.asText();
        }

        return new EvaluationCase(
                id, category, message, identity, allowedTools, forbiddenTools, requiresLogin,
                maxToolCalls, requiredFacts, forbiddenFacts, productCardCount, stubConversationId);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw missingField(field);
        }
        if (!value.isTextual()) {
            throw fieldType(field);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw missingField(field);
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw fieldType(field);
        }
        int parsed = value.intValue();
        if (parsed < 0) {
            throw new IllegalArgumentException("评测用例结构非法：用例数值字段不能为负：" + field);
        }
        return parsed;
    }

    /** 缺省 -> {@code null}（由 {@link EvaluationCase} 回落空列表）；显式 null 或非字符串数组都拒绝。 */
    private static List<String> optionalTextArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isArray()) {
            throw fieldType(field);
        }
        List<String> items = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw fieldType(field);
            }
            items.add(item.asText());
        }
        return items;
    }

    private static IllegalArgumentException missingField(String field) {
        return new IllegalArgumentException("评测用例结构非法：用例缺少必填字段：" + field);
    }

    private static IllegalArgumentException fieldType(String field) {
        return new IllegalArgumentException("评测用例结构非法：用例字段类型非法：" + field);
    }

    /** 逐例运行并汇总结果。空用例集合抛 {@link IllegalArgumentException}，避免空评测假绿灯。 */
    public Report run(List<EvaluationCase> cases) {
        Objects.requireNonNull(cases, "cases 不能为空");
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("评测用例不能为空");
        }
        List<CaseOutcome> outcomes = new ArrayList<>(cases.size());
        List<String> failures = new ArrayList<>();

        for (EvaluationCase evalCase : cases) {
            CaseOutcome outcome = runCase(evalCase);
            outcomes.add(outcome);
            if (!outcome.passed()) {
                failures.add(outcome.caseId() + ": " + String.join("; ", outcome.problems()));
            }
        }

        int passed = (int) outcomes.stream().filter(CaseOutcome::passed).count();
        return new Report(outcomes.size(), passed, failures, outcomes);
    }

    private CaseOutcome runCase(EvaluationCase evalCase) {
        audit.reset();
        ModelHarness harness = harnesses.create(evalCase.stubConversationId());
        AgentOrchestrator orchestrator = new AgentOrchestrator(harness.model(), registry, portal, limits);
        AgentTurnResult result = orchestrator.run(buildRequest(evalCase));

        List<String> toolNames = result.toolCalls().stream().map(ToolCallRecord::name).toList();
        List<String> unknownTools = toolNames.stream().filter(name -> !registry.names().contains(name)).toList();
        List<String> portalOperations = List.copyOf(audit.operations());
        List<String> problems = new ArrayList<>();

        if (!unknownTools.isEmpty()) {
            problems.add("调用了未注册工具：" + unknownTools);
        }
        for (String name : toolNames) {
            if (!evalCase.allowedTools().contains(name)) {
                problems.add("调用了未允许的工具：" + name);
            }
        }
        for (String name : evalCase.forbiddenTools()) {
            if (toolNames.contains(name)) {
                problems.add("调用了禁止的工具：" + name);
            }
        }
        if (toolNames.size() > evalCase.maxToolCalls()) {
            problems.add("工具调用次数 " + toolNames.size() + " 超过上限 " + evalCase.maxToolCalls());
        }
        if (result.requiresLogin() != evalCase.requiresLogin()) {
            problems.add("requiresLogin 期望 " + evalCase.requiresLogin() + "，实际 " + result.requiresLogin());
        }
        if (result.products().size() != evalCase.productCardCount()) {
            problems.add("商品卡片数量期望 " + evalCase.productCardCount() + "，实际 " + result.products().size());
        }

        // 真实门户操作必须落在 Python _expected_backend_operations 允许集内（含 resolveMember 等其它方法）
        Set<String> allowedOperations = expectedPortalOperations(evalCase);
        List<String> unexpectedOperations = portalOperations.stream()
                .map(StubEvaluationRunner::portalOperationName)
                .distinct()
                .filter(name -> !allowedOperations.contains(name))
                .sorted()
                .toList();
        if (!unexpectedOperations.isEmpty()) {
            problems.add("调用了未允许的门户接口：" + unexpectedOperations);
        }

        String evidence = evidence(harness.requests(), result.answer());
        for (String fact : evalCase.requiredFacts()) {
            if (!evidence.contains(fact)) {
                problems.add("缺少必须出现的事实：" + fact);
            }
        }
        for (String fact : evalCase.forbiddenFacts()) {
            if (evidence.contains(fact)) {
                problems.add("出现了禁止出现的内容：" + fact);
            }
        }

        return new CaseOutcome(
                evalCase.id(), evalCase.category(), problems.isEmpty(), problems,
                toolNames, portalOperations, unknownTools);
    }

    /**
     * Python {@code _expected_backend_operations} 的 Java 等价：由用例的 {@code allowedTools} 与身份
     * 推导允许的门户操作。<strong>游客</strong>对会员券工具的预期门户操作集合为空。
     */
    public static Set<String> expectedPortalOperations(EvaluationCase evalCase) {
        Objects.requireNonNull(evalCase, "evalCase 不能为空");
        Set<String> allowed = new LinkedHashSet<>();
        List<String> tools = evalCase.allowedTools();
        if (tools.contains("searchProducts")) {
            allowed.add("search_products");
        }
        if (tools.contains("getProductDetail") || tools.contains("compareProducts")) {
            allowed.add("get_product_detail");
        }
        if (evalCase.isMember() && tools.contains("getMemberCouponsForProduct")) {
            allowed.add("list_unused_coupon_history");
            allowed.add("list_product_coupons");
        }
        return allowed;
    }

    /**
     * Java 门户方法名映射为 Python 评测的操作名（对齐 {@code FakeStorefrontBackend.call_names}）；
     * 未识别的方法名原样返回，从而仍会被允许集检查判为未允许。
     */
    public static String portalOperationName(String portalMethod) {
        return switch (portalMethod) {
            case "searchProducts" -> "search_products";
            case "getProductDetail" -> "get_product_detail";
            case "listUnusedCouponHistory" -> "list_unused_coupon_history";
            case "listProductCoupons" -> "list_product_coupons";
            case "resolveMember" -> "resolve_member";
            default -> portalMethod;
        };
    }

    private static AgentTurnRequest buildRequest(EvaluationCase evalCase) {
        SessionSnapshot session = SessionSnapshot.empty();
        if (evalCase.isMember()) {
            return AgentTurnRequest.member(
                    evalCase.message(), SESSION_ID, DEFAULT_MEMBER_ID, session, MEMBER_TOKEN);
        }
        return AgentTurnRequest.guest(evalCase.message(), SESSION_ID, session);
    }

    /** 证据 = 模型收到的全部受围栏 {@code tool} 消息 + 最终回答（对齐 Python 评测驱动）。 */
    private static String evidence(List<ModelRequest> requests, String answer) {
        StringBuilder text = new StringBuilder();
        for (ModelRequest request : requests) {
            for (ModelMessage message : request.messages()) {
                if (ModelMessage.ROLE_TOOL.equals(message.role()) && message.content() != null) {
                    text.append(message.content());
                }
            }
        }
        return text.append('\n').append(answer == null ? "" : answer).toString();
    }
}
