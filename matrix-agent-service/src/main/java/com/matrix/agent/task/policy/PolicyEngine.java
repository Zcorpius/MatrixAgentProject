package com.matrix.agent.task.policy;
import com.matrix.agent.task.redact.*;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.demo.MockCapabilityProvider;

import com.matrix.agent.vehicle.VehicleStatePredicate;

import com.matrix.agent.vehicle.VehicleState;

import com.matrix.agent.intent.IntentClassifier;

import com.matrix.agent.identity.VehicleZone;

import com.matrix.agent.identity.ExplicitIntentConstraints;

import com.matrix.agent.identity.AgentRequest;

import android.util.Log;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.contract.schema.CanonicalSchema;
import com.matrix.agent.contract.schema.SchemaError;
import com.matrix.agent.contract.schema.SchemaErrorCode;
import com.matrix.agent.contract.schema.SchemaValidator;
import com.matrix.agent.contract.schema.ValidationResult;
import com.matrix.agent.task.capability.*;
import com.matrix.agent.identity.*;
import com.matrix.agent.intent.*;
import com.matrix.agent.vehicle.*;


public final class PolicyEngine {
    private static final String TAG = "MatrixAgent";

    /**
     * 用户硬约束——"长期记忆仅由用户决定"。PolicyEngine 是受信任执行边界,
     * 在所有 CapabilityProvider 之前强制 memory.semantic.save 必须 request.isMemorySaveAllowed()=true。
     * MockCapabilityProvider.MemorySemanticSaveHandler 内的同款 gate 保留作 defence-in-depth
     * (若未来 Provider 不经过 PolicyEngine 直接调 handler,这层仍守住)。
     */
    private static final String MEMORY_SEMANTIC_SAVE = "memory.semantic.save";

    private final CapabilityRegistry registry;

    public PolicyEngine(CapabilityRegistry registry) {
        this.registry = registry;
    }

    public PolicyDecision evaluate(AgentRequest request, ToolCall call) {
        String cap = call.getCapabilityName();
        // Tool 参数含 destination / home_address / preferred_temperature 等业务敏感字段,
        // 不能整段进 logcat。这里只暴露 capability 名 + 元数据(actor / occupantZone)。
        Log.d(TAG, "[Policy] evaluate cap=" + cap
                + " actor=" + request.getActor()
                + " occupantZone=" + request.getOccupantZone()
                + " args=" + com.matrix.agent.task.redact.SafeLog.TOOL_ARGS_PLACEHOLDER);

        CapabilityDefinition definition = registry.find(cap);
        if (definition == null) {
            Log.w(TAG, "[Policy]   DENY (capability) Capability 未注册或未进入白名单: " + cap);
            return PolicyDecision.denyCapability("Capability 未注册或未进入白名单");
        }
        if (definition.getRiskLevel() == RiskLevel.R3_PROHIBITED) {
            Log.w(TAG, "[Policy]   DENY (capability) R3 禁止能力: " + cap);
            return PolicyDecision.denyCapability("R3 禁止能力：不允许由 Agent 控制");
        }

        // capability-specific 显式许可 gate。
        // 必须在 schema / parameter 校验之前——memorySaveAllowed=false 是 capability-level
        // 不可上诉(模型只能放弃或换 capability),不应让模型通过修参数绕过。
        // 同语义:R3 / vehicleState / readOnlyHint + writeOperation 都是 capability-level fail-closed。
        PolicyDecision memorySaveGate = checkMemorySaveExplicitGate(request, cap);
        if (memorySaveGate != null) return memorySaveGate;

        // 写操作 + MULTI_TARGET/CONFLICT 用户意图 → fail-closed。
        // 必须在参数校验之前——多目标 / 否定冲突的 ToolCall 不允许执行,与参数是否合法无关。
        if (definition.isWriteOperation()) {
            PolicyDecision blocked = checkExplicitIntentBlocked(request, cap);
            if (blocked != null) return blocked;
        }

        // 任务级 readOnlyHint 强制约束。
        // IntentClassifier 把"查电量"等查询命令标 readOnlyHint=true 让 TaskScheduler 抢占可触达,
        // 但模型输出不可信——若 LLM 错误或被 prompt injection 后返回 climate.set_temperature 等写
        // capability,旧实现仍 ALLOW,导致"被抢占的查询任务"实际跑了写操作,半路被 cancel 时
        // 违反"写操作不可半路中断"的安全契约。这里在 PolicyEngine 受信任边界强制:readOnlyHint=true
        // 时,所有 writeOperation=true 的 capability 一律 CAPABILITY 拒绝(不可上诉)。模型换参数
        // 解决不了,Agent Loop 把对应 capability 加入禁用集合,模型必须改走读 capability 或返回
        // directAnswer 终止。误判的代价最多是"查询任务失败",不会放出或中断写车控。
        if (request.isReadOnlyHint() && definition.isWriteOperation()) {
            Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                    + " 任务级 readOnlyHint=true 禁止执行写操作"
                    + "(IntentClassifier 已标只读,模型不应返回 write capability)");
            return PolicyDecision.denyCapability(
                    "任务被标记为只读(readOnlyHint=true),禁止执行写操作");
        }

        // capability 前置车辆状态约束(AND 语义)。
        // 不满足时归 CAPABILITY 拒绝(不可上诉)——vehicle state 是车辆物理事实,
        // 模型换参数解决不了,等用户停车 / 启动引擎后重新下达。
        PolicyDecision vehicleStateDecision = checkVehicleState(definition, request, cap);
        if (vehicleStateDecision != null) return vehicleStateDecision;

        // strict schema 校验——模型输出是不可信输入,即使 JSON Schema 设了
        // additionalProperties=false 也不能依赖。在 PolicyEngine(受信任执行边界)拒绝所有
        // 未声明字段;统一 required / range / enum / type 校验。
        // 委托 SchemaValidator 校验 CanonicalSchema(替换旧版
        // checkStrictSchema + checkTypeAndRangeAndEnum 双方法)。
        PolicyDecision schemaDecision = checkCanonicalSchema(definition, call, cap);
        if (schemaDecision != null) return schemaDecision;

        // 参数级:数值/格式/可补全参数(CapabilityValidator lambda,业务自定义)
        String violation = definition.validateArguments(call.getArguments());
        if (violation != null) {
            Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap + " violation=" + violation);
            return PolicyDecision.denyParameter(violation);
        }

        if (definition.isTargetZoneRequired() && !call.getArguments().containsKey("zone")) {
            Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap + " 缺少 zone");
            return PolicyDecision.denyParameter("缺少参数：zone");
        }
        if (call.getArguments().containsKey("zone")) {
            VehicleZone targetZone = VehicleZone.parse(call.argument("zone"));
            if (targetZone == null) {
                Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap + " zone 解析失败");
                return PolicyDecision.denyParameter("无效的车辆区域：zone");
            }
            // 能力级:该 Capability 不支持此区域,换参数解决不了
            if (!definition.getAllowedTargetZones().contains(targetZone)) {
                Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                        + " 不支持目标区域: " + targetZone);
                return PolicyDecision.denyCapability("该 Capability 不支持目标区域：" + targetZone);
            }
            // 显式用户目标统一前置约束。权威来源是 ExplicitIntentConstraints,
            // 由 Runtime 受信任边界(关键词识别 / NLU)提取,**不依赖模型 adapter 标 provenance**。
            //
            // 评审指出的两种遗漏场景(都必须堵):
            //   1. 副驾说"主驾调温",模型第一次直接返回 zone=passenger → 用户原意被替换
            //   2. 主驾说"副驾调温",模型返回 zone=driver → 用户原意被替换
            //
            // 修复策略(对所有写操作):
            //   - explicitZone 存在 + targetZone != explicitZone → PARAMETER 拒绝,让模型保持用户原目标重试
            //   - 重试用对 zone 后,还要独立校验 explicitZone 本身是否越权
            //     (副驾不可写主驾,即使模型已经"修正"成合法区域)
            if (definition.isWriteOperation()) {
                PolicyDecision explicitDecision = checkExplicitZoneConsistency(
                        request, call, cap, targetZone);
                if (explicitDecision != null) return explicitDecision;
            }
        }
        Log.d(TAG, "[Policy]   ALLOW cap=" + cap);
        return PolicyDecision.allow();
    }

    /**
     * 写操作 + 用户意图被降级(MULTI_TARGET/CONFLICT)时 fail-closed。
     * 在参数 / zone 校验之前执行,确保多目标 / 否定冲突的 ToolCall 永不进入 Provider。
     */
    private static PolicyDecision checkExplicitIntentBlocked(AgentRequest request, String cap) {
        ExplicitIntentConstraints constraints = request.getExplicitIntent();
        if (constraints == null || !constraints.isBlocked()) return null;
        String reason = constraints.getIntentType() == ExplicitIntentConstraints.IntentType.MULTI_TARGET
                ? "指令包含多个区域目标,出于安全考虑,请逐个区域分开下达"
                : "指令的目标区域不明确,请明确指定单个 zone";
        Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                + " intentType=" + constraints.getIntentType() + " reason=" + reason);
        return PolicyDecision.denyCapability(reason);
    }

    /**
     * memory.semantic.save capability-level 显式许可 gate。
     *
     * <p>返回 null 表示放行(交回主流程);返回非 null 表示已得出决策。
     * 触发条件:capability == memory.semantic.save 且 request.isMemorySaveAllowed()==false。
     * 决策类型:denyCapability(CAPABILITY_REJECTED,不可上诉)——与 R3 / vehicleState /
     * readOnlyHint+writeOperation 同模式。模型不应通过换参数绕过用户许可要求。
     *
     * <p>MockCapabilityProvider.MemorySemanticSaveHandler 内的同款 gate 保留作 defence-in-depth。
     */
    private static PolicyDecision checkMemorySaveExplicitGate(AgentRequest request, String cap) {
        if (!MEMORY_SEMANTIC_SAVE.equals(cap)) return null;
        if (request.isMemorySaveAllowed()) return null;
        Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                + " 用户未显式要求长期记忆(memorySaveAllowed=false)");
        return PolicyDecision.denyCapability(
                "memory.semantic.save 需要用户显式长期记忆意图(如\"记住X\"/\"别忘了X\")");
    }

    /**
     * capability 前置车辆状态约束判定(AND 语义)。
     *
     * <p>插在 {@link #checkExplicitIntentBlocked} 之后、{@link #checkCanonicalSchema} 之前——
     * vehicle state 是车辆物理事实,与参数无关,先于 schema 校验判定。
     * 不满足任一 predicate → CAPABILITY 拒绝(不可上诉)。
     */
    private static PolicyDecision checkVehicleState(CapabilityDefinition definition,
            AgentRequest request, String cap) {
        if (definition.getRequiredVehicleStates().isEmpty()) return null;
        VehicleState state = request.getCurrentVehicleState();
        for (VehicleStatePredicate predicate : definition.getRequiredVehicleStates()) {
            if (!predicate.matches(state)) {
                String reason = "vehicle state 不满足:" + predicate
                        + "(当前 gear=" + state.getGear() + " speedKmh=" + state.getSpeedKmh()
                        + " engineRunning=" + state.isEngineRunning()
                        + " charging=" + state.isCharging() + ")";
                Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap + " " + reason);
                return PolicyDecision.denyCapability(reason);
            }
        }
        return null;
    }

    /**
     * strict schema 校验——拒绝 schema 外字段、检查
     * required/type/range/enum/const/pattern/composition。
     *
     * <p>委托 {@link SchemaValidator#validateArguments(CanonicalSchema, java.util.Map)}
     * 递归校验 {@link CanonicalSchema}(JSON Schema 2020-12 子集)。旧版
     * checkStrictSchema + checkTypeAndRangeAndEnum 双方法已删除——旧版 quirks
     * (enum 大小写不敏感、integer enum String.valueOf 比对、空白字符串拒绝)在
     * SchemaValidator 内部保留。
     *
     * <p>校验通过返回 null(允许进入下一步);失败返回 PARAMETER 拒绝(模型可换参数重试)。
     * capability 业务级 validator 仍由 {@link CapabilityDefinition#validateArguments} 处理。
     */
    private static PolicyDecision checkCanonicalSchema(CapabilityDefinition definition,
            ToolCall call, String cap) {
        CanonicalSchema schema = definition.getParameterSchema();
        ValidationResult result = SchemaValidator.INSTANCE.validateArguments(
                schema, call.getArguments());
        if (result.isOk()) return null;
        SchemaError first = result.getErrors().get(0);
        String reason = buildReasonFromError(first);
        Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap
                + " schemaErrorCode=" + first.getCode()
                + " path=" + first.getPath()
                + " reason=" + reason);
        return PolicyDecision.denyParameter(reason);
    }

    /**
     * 把 SchemaError 转成模型可读的 deny reason——保留旧版文案兼容
     * (PolicyEngineTest 测试 reason.contains("temperature") / contains("priority") 等)。
     */
    private static String buildReasonFromError(SchemaError error) {
        switch (error.getCode()) {
            case MISSING_REQUIRED:
                return error.getMessage();  // "缺少必需参数:xxx"
            case ADDITIONAL_PROPERTY:
                return error.getMessage();  // "未声明参数:xxx(strict schema 不允许 additionalProperties)"
            case TYPE_MISMATCH:
                return "参数 " + error.getPath() + " " + error.getMessage();
            case NOT_INTEGER:
                return "参数 " + error.getPath() + " 必须是整数";
            case OUT_OF_RANGE:
            case EXCLUSIVE_RANGE_VIOLATION:
                return "参数 " + error.getPath() + " " + error.getMessage();
            case ENUM_VIOLATION:
                return error.getMessage();
            case CONST_MISMATCH:
                return "参数 " + error.getPath() + " " + error.getMessage();
            case LENGTH_VIOLATION:
            case PATTERN_MISMATCH:
                return "参数 " + error.getPath() + " " + error.getMessage();
            case EMPTY_STRING:
                return "参数 " + error.getPath() + " " + error.getMessage();
            case COMPOSITION_FAILED:
                return "参数 " + error.getPath() + " " + error.getMessage();
            case NOT_NULL:
                return "参数 " + error.getPath() + " " + error.getMessage();
            default:
                return error.getMessage();
        }
    }

    /**
     * 显式 zone 一致性 + 越权双检查。返回非 null 表示已得出决策(允许 / 拒绝),
     * 返回 null 表示没有显式约束,交回主流程兜底处理。
     *
     * <p>顺序:
     * <ol>
     *   <li>explicitZone 缺失 → 返回 null(主流程走 OccupantZone 兜底)</li>
     *   <li>explicitZone 与模型 targetZone 不一致 → PARAMETER 拒绝,要求模型用 explicitZone 重试</li>
     *   <li>explicitZone 本身越权(发起者无权写此 zone)→ CAPABILITY 拒绝,不可上诉</li>
     *   <li>explicitZone 与 targetZone 一致且不越权 → ALLOW</li>
     * </ol>
     *
     * <p>MULTI_TARGET / CONFLICT 已在 {@link #checkExplicitIntentBlocked} 前置
     * fail-closed,本方法只处理 SINGLE_TARGET 与 UNSPECIFIED。
     */
    private PolicyDecision checkExplicitZoneConsistency(AgentRequest request, ToolCall call,
            String cap, VehicleZone targetZone) {
        ExplicitIntentConstraints constraints = request.getExplicitIntent();
        if (constraints == null
                || constraints.getIntentType() == ExplicitIntentConstraints.IntentType.UNSPECIFIED) {
            return fallbackWithoutExplicitIntent(request, call, cap, targetZone);
        }

        VehicleZone explicitZone = constraints.getExplicitZone();

        // 1. 模型目标与用户明确目标不一致 → 让模型保持用户原目标重试
        if (targetZone != explicitZone) {
            Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap
                    + " 模型 zone=" + targetZone + " 与用户明确 zone=" + explicitZone
                    + " 不一致,要求用 zone=" + explicitZone + " 重试");
            return PolicyDecision.denyParameter(
                    "用户明确要求 zone=" + explicitZone + ",请保持用户目标重试,不要替换为 zone=" + targetZone);
        }

        // 2. explicitZone 本身越权(发起者无权写此 zone)→ CAPABILITY 拒绝不可上诉
        //    覆盖场景:副驾说"主驾调温"模型已用 zone=driver,但 driver 越权
        if (isCrossZoneViolation(request.getOccupantZone(), explicitZone)) {
            Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                    + " 用户明确越权: occupantZone=" + request.getOccupantZone()
                    + " 不能写 explicitZone=" + explicitZone + ",不可改 zone 重试");
            return PolicyDecision.denyCapability(
                    "用户明确要求越权:occupantZone=" + request.getOccupantZone()
                            + " 不能写 zone=" + explicitZone);
        }

        return PolicyDecision.allow();
    }

    /**
     * 无显式意图约束(UNSPECIFIED 或 constraints==null)时的兜底:
     * 仅靠发起者 zone 与模型 targetZone 判定是否越权。
     */
    private static PolicyDecision fallbackWithoutExplicitIntent(AgentRequest request, ToolCall call,
            String cap, VehicleZone targetZone) {
        if (request.getOccupantZone() == VehicleZone.PASSENGER
                && targetZone == VehicleZone.DRIVER) {
            boolean userExplicitProvenance =
                    call.provenanceOf("zone") == ToolCall.ArgumentProvenance.USER_EXPLICIT;
            if (userExplicitProvenance) {
                Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                        + " 副驾越权 zone=DRIVER(provenance=USER_EXPLICIT),不可改 zone 重试");
                return PolicyDecision.denyCapability("副驾不能修改主驾区域：用户明确要求越权");
            }
            Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap
                    + " 副驾 zone=DRIVER(模型脑补),可改 zone=passenger 重试");
            return PolicyDecision.denyParameter("副驾不能修改主驾区域，请改用 zone=passenger");
        }
        return null;
    }

    /**
     * 判断发起者所在 zone 是否有权写目标 zone。
     *
     * <p>规则:副驾(PASSENGER)不能写主驾(DRIVER)。其他组合(主驾写副驾、
     * 主驾写主驾、副驾写副驾)允许。后排 / 第三排留待扩展。
     */
    private static boolean isCrossZoneViolation(VehicleZone occupantZone, VehicleZone targetZone) {
        return occupantZone == VehicleZone.PASSENGER && targetZone == VehicleZone.DRIVER;
    }

    public CapabilityDefinition findDefinition(String capabilityName) {
        return registry.find(capabilityName);
    }
}
