package com.matrix.agent.task.capability;
import com.matrix.agent.task.redact.*;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.vehicle.VehicleStatePredicate;

import com.matrix.agent.identity.VehicleZone;

import com.matrix.agent.identity.AgentRequest;

import com.matrix.agent.contract.schema.SchemaValidator;
import com.matrix.agent.contract.ToolDefinition;
import com.matrix.agent.contract.schema.CanonicalSchema;
import com.matrix.agent.data.memory.MemoryKeyCatalog;
import com.matrix.agent.identity.*;
import com.matrix.agent.intent.*;
import com.matrix.agent.vehicle.*;


import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CapabilityRegistry {
    private final Map<String, CapabilityDefinition> definitions = new LinkedHashMap<>();

    public synchronized CapabilityRegistry register(CapabilityDefinition definition) {
        definitions.put(definition.getName(), definition);
        return this;
    }

    public synchronized CapabilityDefinition find(String name) {
        return definitions.get(name);
    }

    public synchronized Map<String, CapabilityDefinition> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    /** Generates the capability section consumed by planners from the same policy source. */
    public synchronized String toPlannerInstructions() {
        return toPlannerInstructions(null);
    }

    /**
     * Generates planner instructions from the same zone projection used by tool wire schemas.
     * A compatibility JSON model must never be told about a capability it cannot invoke.
     */
    public synchronized String toPlannerInstructions(VehicleZone zone) {
        StringBuilder text = new StringBuilder();
        for (CapabilityDefinition definition : definitions.values()) {
            if (definition.getRiskLevel() == RiskLevel.R3_PROHIBITED) continue;
            if (zone != null && !definition.getAllowedTargetZones().contains(zone)) continue;
            text.append("- ").append(definition.getName());
            if (!definition.getDescription().isEmpty()) text.append(": ").append(definition.getDescription());
            text.append('\n');
        }
        return text.toString();
    }

    public synchronized List<ToolDefinition> toToolDefinitions() {
        return toToolDefinitions(null);
    }

    /**
     * per-zone 投影——仅暴露允许 {@code zone} 调用的 capability。
     *
     * <p>调用方({@code LlmModelGateway})按 {@link AgentRequest#getOccupantZone()} 派生
     * 主驾/副驾可见工具集,主驾不看到的副驾专属能力(如字段级 mask)被过滤掉。
     * <p>当 {@code zone == null} 时不过滤(等价于 {@link #toToolDefinitions()}),
     * 保默认行为不退绿。
     */
    public synchronized List<ToolDefinition> toToolDefinitions(VehicleZone zone) {
        List<ToolDefinition> tools = new ArrayList<>();
        Set<String> modelNames = new HashSet<>();
        for (CapabilityDefinition definition : definitions.values()) {
            if (definition.getRiskLevel() == RiskLevel.R3_PROHIBITED) continue;
            if (zone != null && !definition.getAllowedTargetZones().contains(zone)) continue;
            String modelName = toModelToolName(definition.getName());
            if (!modelNames.add(modelName)) {
                throw new IllegalStateException("Tool modelName 冲突：" + modelName);
            }
            tools.add(new ToolDefinition(modelName, definition.getName(),
                    definition.getDescription(), definition.getToolParameters(),
                    definition.getParameterSchema()));
        }
        return Collections.unmodifiableList(tools);
    }

    /**
     * 从 capability 列表派生 {@link AgentRequest.Builder#readOnlyHint(boolean)}。
     *
     * <p>规则:集合内所有 capability 都不是 writeOperation 时返回 true(纯读,
     * 允许被主驾同 hint 请求抢占);否则 false(含写操作,TaskScheduler 不抢占)。
     *
     * <p>调用方(Repository / RuntimeApi)在 {@code AgentRequest.Builder} 显式调用:
     * <pre>{@code
     * AgentRequest.builder(text, actor)
     *     .readOnlyHint(registry.deriveReadOnlyHint(capabilityNames))
     *     .build();
     * }</pre>
     * 不修改 {@code AgentRequest} 构造 / {@code TaskScheduler.submit} 签名,
     * 保 TaskSchedulerTest 不断。
     *
     * @param capabilityNames 调用方期望执行的 capability 名集合(可空——保守返回 false)
     */
    public synchronized boolean deriveReadOnlyHint(java.util.Collection<String> capabilityNames) {
        if (capabilityNames == null || capabilityNames.isEmpty()) return false;
        for (String name : capabilityNames) {
            CapabilityDefinition def = definitions.get(name);
            if (def == null) continue;  // 未知 capability 不影响 hint
            if (def.isWriteOperation()) return false;
        }
        return true;
    }

    /**
     * per-zone readOnlyHint 派生——按 zone 投影后的 tool 集合是否纯读。
     *
     * <p>语义:zone 内任一 capability 是 writeOperation 时返回 false(保守,不抢占);
     * 全部纯读时返回 true(允许主驾同 hint 请求抢占)。空 zone(无 capability)保守返回 false。
     *
     * <p>调用方({@code AgentRuntimeRepository.execute})按 {@code actor.zone} 派生,
     * 让 TaskScheduler 的主驾优先抢占在 zone 级别生效。
     */
    public synchronized boolean deriveReadOnlyHint(VehicleZone zone) {
        if (zone == null) return false;
        for (CapabilityDefinition def : definitions.values()) {
            if (def.getRiskLevel() == RiskLevel.R3_PROHIBITED) continue;
            if (!def.getAllowedTargetZones().contains(zone)) continue;
            if (def.isWriteOperation()) return false;
        }
        return true;
    }

    public static String toModelToolName(String capabilityName) {
        return capabilityName.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public static CapabilityRegistry createDemoRegistry() {
        CapabilityRegistry registry = new CapabilityRegistry()
                .register(CapabilityDefinition.builder("vehicle.climate.set_temperature", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("设置 driver 或 passenger 区域温度，temperature 为 16..30")
                        .writeOperation(true).verificationRequired(true).targetZoneRequired(true)
                        .allowedTargetZones(EnumSet.of(VehicleZone.DRIVER, VehicleZone.PASSENGER))
                        .requiredVehicleStates(VehicleStatePredicate.PARKED_ONLY)
                        .parameterSchema(CanonicalSchema.object()
                                .description("设置 driver 或 passenger 区域温度")
                                .property("zone", zoneSchema())
                                .property("temperature", CanonicalSchema.integer()
                                        .description("目标温度，单位摄氏度").minimum(16).maximum(30).build())
                                .required("zone", "temperature")
                                .additionalProperties(false)
                                .build())
                        .validator(args -> validateNumber(args, "temperature", 16, 30, "温度必须在 16℃ 到 30℃ 之间"))
                        .build())
                .register(CapabilityDefinition.builder("vehicle.seat.set_heating_level", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("设置 driver 或 passenger 座椅加热等级，level 为 0..3")
                        .writeOperation(true).verificationRequired(true).targetZoneRequired(true)
                        .allowedTargetZones(EnumSet.of(VehicleZone.DRIVER, VehicleZone.PASSENGER))
                        .requiredVehicleStates(VehicleStatePredicate.PARKED_ONLY)
                        .parameterSchema(CanonicalSchema.object()
                                .description("设置 driver 或 passenger 座椅加热等级")
                                .property("zone", zoneSchema())
                                .property("level", CanonicalSchema.integer()
                                        .description("座椅加热等级").minimum(0).maximum(3).build())
                                .required("zone", "level")
                                .additionalProperties(false)
                                .build())
                        .validator(args -> validateNumber(args, "level", 0, 3, "座椅加热等级必须在 0 到 3 之间"))
                        .build())
                .register(readOnly("vehicle.info.get_battery", "查询车辆电量"))
                .register(readOnly("vehicle.info.get_tire_pressure", "查询四轮胎压"))
                .register(CapabilityDefinition.builder("navigation.start_route", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("开始导航，destination 必须是明确目的地")
                        .writeOperation(true).idempotent(false).timeoutMillis(5_000L)
                        .requiredVehicleStates(VehicleStatePredicate.PARKED_ONLY)
                        .parameterSchema(CanonicalSchema.object()
                                .description("开始导航参数")
                                .property("destination", CanonicalSchema.string()
                                        .description("明确的导航目的地")
                                        .sensitive(true).sensitivePlaceholder("<destination>")
                                        .build())
                                .required("destination")
                                .additionalProperties(false)
                                .build())
                        .validator(args -> requireText(args, "destination", "未指定目的地", "缺少明确的导航目的地"))
                        // Provider 的 message / observedState 会带真实目的地,
                        // Audit 视图必须按 schema 投影——message 用模板,observedState 替换为占位符。
                        .auditMessageTemplate("已开始导航到 <destination>")
                        .sensitiveObservedField("navigation.destination", "<destination>")
                        // 失败 message fail-closed(Provider 诊断文本常含真实地址),
                        // observedState allowlist 只放行诊断安全字段;schema 外字段(如
                        // navigation.requested_destination / route.destination)默认 mask。
                        .auditFailureMessageTemplate("导航失败,详情见 errorCode")
                        .auditObservedAllowlist("navigation.status", "navigation.error_code")
                        .build())
                .register(CapabilityDefinition.builder("system.media.set_volume", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("设置当前 Android 用户的媒体音量百分比，percent 为 0..100；仅用于用户明确要求调节音量")
                        .writeOperation(true).idempotent(true).verificationRequired(true)
                        .parameterSchema(CanonicalSchema.object()
                                .description("设置媒体音量参数")
                                .property("percent", CanonicalSchema.integer()
                                        .description("目标媒体音量百分比，0 为静音，100 为最大")
                                        .minimum(0).maximum(100).build())
                                .required("percent")
                                .additionalProperties(false)
                                .build())
                        .validator(args -> validateNumber(args, "percent", 0, 100,
                                "媒体音量必须在 0% 到 100% 之间"))
                        .build())
                .register(CapabilityDefinition.builder("system.display.set_brightness", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("设置当前 Android 用户的屏幕亮度百分比，percent 为 1..100；会切换为手动亮度以确保用户命令生效")
                        .writeOperation(true).idempotent(true).verificationRequired(true)
                        .parameterSchema(CanonicalSchema.object()
                                .description("设置屏幕亮度参数")
                                .property("percent", CanonicalSchema.integer()
                                        .description("目标屏幕亮度百分比，最低 1 以避免屏幕全黑")
                                        .minimum(1).maximum(100).build())
                                .required("percent")
                                .additionalProperties(false)
                                .build())
                        .validator(args -> validateNumber(args, "percent", 1, 100,
                                "屏幕亮度必须在 1% 到 100% 之间"))
                        .build())
                .register(CapabilityDefinition.builder("memory.preference.save", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("仅在用户明确要求保存时，把本轮同一句中提到的偏好或个人设置存起来（例如「记住我喜欢24度」「记住我家在XX」）。需要 key 和 value").writeOperation(true)
                        .parameterSchema(CanonicalSchema.object()
                                .description("记忆偏好 save 参数")
                                .property("key", CanonicalSchema.string()
                                        .description("偏好键，常用约定：preferred_temperature / preferred_seat_level / home_address / common_destinations")
                                        .pattern(MemoryKeyCatalog.PREFERENCE_PATTERN)
                                        .maxLength(MemoryKeyCatalog.MAX_KEY_LENGTH)
                                        .sensitive(true).sensitivePlaceholder("<memory>")
                                        .build())
                                .property("value", CanonicalSchema.string()
                                        .description("偏好值")
                                        .maxLength(2048)
                                        // Memory Value 是用户私人事实(温度/家地址/常去地),
                                        // Audit 视图全脱敏——主驾副驾可能共用 Android User,UI 查看者未必是数据所有者。
                                        .sensitive(true).sensitivePlaceholder("<memory>").build())
                                .required("key", "value")
                                .additionalProperties(false)
                                .build())
                        .validator(args -> requireKeys(args, "key", "value")).build())
                .register(CapabilityDefinition.builder("memory.preference.get", RiskLevel.R0_READ_ONLY)
                        .description("读取用户之前明确告诉系统要记住的偏好或个人设置（例如：用户问「我喜欢多少度」、「我家在哪」、「我常去哪」）。需要 key")
                        .parameterSchema(CanonicalSchema.object()
                                .description("记忆偏好 get 参数")
                                .property("key", CanonicalSchema.string()
                                        .description("偏好键，常用约定：preferred_temperature / preferred_seat_level / home_address / common_destinations")
                                        .maxLength(2048)
                                        // key 本身可能暴露"用户保存过哪些敏感事实"——home_address 等
                                        // 命名直接进 Audit 也不合适,Audit 视图同样替换为 <memory>。
                                        .sensitive(true).sensitivePlaceholder("<memory>").build())
                                .required("key")
                                .additionalProperties(false)
                                .build())
                        .validator(args -> requireKeys(args, "key")).build())
                .register(CapabilityDefinition.builder("memory.preference.list", RiskLevel.R0_READ_ONLY)
                        .description("列出当前乘员的偏好目录，不返回内容。旧版键显示为稳定 legacy: 别名；用返回的 key 调用 get 读取，用户明确选择该 key 后调用 delete 删除。每页最多20条，有 next_after 时可继续")
                        .parameterSchema(CanonicalSchema.object()
                                .property("after", CanonicalSchema.string()
                                        .description("上一页 next_after；首页省略")
                                        .maxLength(com.matrix.agent.data.memory.PreferenceReferences.MAX_REFERENCE_LENGTH)
                                        .sensitive(true).sensitivePlaceholder("<memory>").build())
                                .additionalProperties(false).build()).build())
                .register(CapabilityDefinition.builder("memory.preference.delete", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("仅当用户明确要求忘记指定偏好时，删除当前乘员的一条偏好。需要 key")
                        .writeOperation(true)
                        .parameterSchema(CanonicalSchema.object()
                                .property("key", CanonicalSchema.string()
                                        .description("要忘记的精确偏好键或 list 返回的 legacy: 别名")
                                        .maxLength(2048)
                                        .sensitive(true).sensitivePlaceholder("<memory>").build())
                                .required("key").additionalProperties(false).build())
                        .validator(args -> requireKeys(args, "key")).build())
                // Semantic 记忆层——用户**明确要求长期记住**的事实/知识,
                // 区别于 preference(存偏好如温度/座椅),semantic 存事实性知识(如「我女儿叫小红」、
                // 「我对花生过敏」)。PII key(home_address / contact_phone / id_card 等)写入会被
                // 接受,但投影路径不进 prompt(只附"已保存,请用工具查询"),
                // 模型需通过 memory.semantic.get 查询读取。
                .register(CapabilityDefinition.builder("memory.semantic.save", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("仅在用户明确要求长期记住时，把本轮同一句中提到的事实存到语义层（例如「记住我女儿叫小红」「记住我对花生过敏」）。偏好用 memory.preference.save。需要 key 和 value")
                        .writeOperation(true)
                        .parameterSchema(CanonicalSchema.object()
                                .description("记忆语义 save 参数")
                                .property("key", CanonicalSchema.string()
                                        .description("语义键,**必须** namespace.family / namespace.allergy / namespace.work / namespace.fact 之一开头"
                                                + "(如 family.daughter_name / allergy.peanut / work.role / fact.home_city),"
                                                + "后缀仅字母数字与 ._ 字符。PII key(home_address / contact_phone / id_card)不再接受,"
                                                + "请改用 family.* / fact.* 等命名空间。Schema 强制 pattern + maxLength(64)")
                                        // namespace 白名单 + 字符集 + 长度上限。
                                        // 用户硬约束:"memory.semantic.save 只接受明确支持的 key namespace"。
                                        .pattern(MemoryKeyCatalog.SEMANTIC_PATTERN)
                                        .maxLength(MemoryKeyCatalog.MAX_KEY_LENGTH)
                                        .build())
                                .property("value", CanonicalSchema.string()
                                        .description("事实值(最多 2048 字符)。空字符串 / 纯空白被拒,超长被拒"
                                                + "。注意:maxLength 是 Java char(UTF-16 code unit)数,中文 / emoji "
                                                + "的 UTF-8 字节数会更多(最坏 ~4 倍)")
                                        // maxLength 是 JSON Schema 标准语义
                                        // (Java String.length() = UTF-16 code unit 数),不是 UTF-8 字节数。
                                        // 文档/UI 必须说"最多 2048 字符",不要说"≤ 2KB"——后者误导。
                                        // UTF-8 字节上限留后续版本视真实 PII 容量需求评估(最坏 8KB/行,
                                        // SQLite TEXT 无压力,不阻塞当前版本)。
                                        // 空字符串已被 SchemaValidator 的 trim().isEmpty() → EMPTY_STRING 拦截。
                                        .maxLength(2048)
                                        .sensitive(true).sensitivePlaceholder("<memory>").build())
                                .required("key", "value")
                                .additionalProperties(false)
                                .build())
                        .validator(args -> requireKeys(args, "key", "value")).build())
                .register(CapabilityDefinition.builder("memory.semantic.get", RiskLevel.R0_READ_ONLY)
                        .description("读取用户之前明确要求记住的事实或知识（例如：用户问「我女儿叫什么」、「我对什么过敏」）。需要 key")
                        .parameterSchema(CanonicalSchema.object()
                                .description("记忆语义 get 参数")
                                .property("key", CanonicalSchema.string()
                                        .description("语义键")
                                        .pattern(MemoryKeyCatalog.SEMANTIC_PATTERN)
                                        .maxLength(MemoryKeyCatalog.MAX_KEY_LENGTH)
                                        .sensitive(true).sensitivePlaceholder("<memory>").build())
                                .required("key")
                                .additionalProperties(false)
                                .build())
                        .validator(args -> requireKeys(args, "key")).build())
                .register(CapabilityDefinition.builder("memory.semantic.delete", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("仅当用户明确要求忘记指定事实时，删除当前乘员的一条语义记忆。需要 key")
                        .writeOperation(true)
                        .parameterSchema(CanonicalSchema.object()
                                .property("key", CanonicalSchema.string()
                                        .pattern(MemoryKeyCatalog.SEMANTIC_PATTERN)
                                        .maxLength(MemoryKeyCatalog.MAX_KEY_LENGTH)
                                        .sensitive(true).sensitivePlaceholder("<memory>").build())
                                .required("key").additionalProperties(false).build())
                        .validator(args -> requireKeys(args, "key")).build())
                .register(CapabilityDefinition.builder("memory.episodic.get", RiskLevel.R0_READ_ONLY)
                        .description("查询近期任务事件的已核验细节。仅在用户询问历史事件时使用；event_id 是召回键 recent.* 的最后一段")
                        .parameterSchema(CanonicalSchema.object()
                                .property("event_id", CanonicalSchema.string()
                                        .pattern("[0-9a-f]{32}").maxLength(32)
                                        .sensitive(true).sensitivePlaceholder("<event-id>").build())
                                .required("event_id").additionalProperties(false).build())
                        .validator(args -> requireKeys(args, "event_id")).build())
                .register(CapabilityDefinition.builder("memory.episodic.delete", RiskLevel.R1_LOW_RISK_WRITE)
                        .description("删除用户明确指定编号的历史事件。先从召回键 recent.* 最后一段取得 event_id，向用户展示编号并让其指定要忘记的编号；不允许用笼统的忘记请求删除任意事件")
                        .writeOperation(true)
                        .parameterSchema(CanonicalSchema.object()
                                .property("event_id", CanonicalSchema.string()
                                        .pattern("[0-9a-f]{32}").maxLength(32)
                                        .sensitive(true).sensitivePlaceholder("<event-id>").build())
                                .required("event_id").additionalProperties(false).build())
                        .validator(args -> requireKeys(args, "event_id")).build())
                .register(CapabilityDefinition.builder("knowledge.answer", RiskLevel.R0_READ_ONLY)
                        .description("回答与车辆状态、用户个人偏好都无关的常识性问题（例如：「今天几号」、「水的沸点」）。不要用此能力查询用户偏好或车辆状态——那些必须用 memory.preference.get 或 vehicle.info.* 系列。需要 question")
                        .parameterSchema(CanonicalSchema.object()
                                .description("常识问答参数")
                                .property("question", CanonicalSchema.string()
                                        .description("用户问题").build())
                                .required("question")
                                .additionalProperties(false)
                                .build())
                        // Provider message 是模型自由文本 (常含用户问题原文回显),
                        // 凭据正则识别不出业务 PII (地址 / 联系人 / 电话) —— 配 audit template
                        // 让 AuditRedactor 走 fail-closed 路径,success/失败均替换为占位符。
                        .auditMessageTemplate("[knowledge answer redacted: free-text contains potential PII]")
                        .auditFailureMessageTemplate("[knowledge answer failed: details redacted]")
                        .validator(args -> requireKeys(args, "question")).build());

        String[] prohibited = {
                "vehicle.adas.set_enabled", "vehicle.steering.set_angle", "vehicle.brake.apply",
                "vehicle.acceleration.set", "vehicle.gear.set", "vehicle.parking.set",
                "vehicle.powertrain.set", "vehicle.high_voltage.set",
                "vehicle.charging.set_enabled", "vehicle.door_lock.set"
        };
        for (String name : prohibited) {
            registry.register(CapabilityDefinition.builder(name, RiskLevel.R3_PROHIBITED)
                    .description("禁止由 Agent 控制").writeOperation(true).build());
        }
        return registry;
    }

    private static CapabilityDefinition readOnly(String name, String description) {
        return CapabilityDefinition.builder(name, RiskLevel.R0_READ_ONLY)
                .description(description).writeOperation(false).build();
    }

    private static CanonicalSchema zoneSchema() {
        return CanonicalSchema.string()
                .description("目标座舱区域")
                .enumValues("driver", "passenger")
                .build();
    }

    private static String validateNumber(Map<String, Object> args, String key, int min, int max, String rangeMessage) {
        Object value = args.get(key);
        if (!(value instanceof Number)) return "缺少或无法识别参数：" + key;
        double raw = ((Number) value).doubleValue();
        if (!Double.isFinite(raw) || raw != Math.rint(raw)) return key + " 必须是整数";
        int number = (int) raw;
        return number < min || number > max ? rangeMessage : null;
    }

    private static String requireText(Map<String, Object> args, String key, String invalid, String message) {
        Object value = args.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty() || invalid.equals(((String) value).trim())) {
            return message;
        }
        return null;
    }

    private static String requireKeys(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            Object value = args.get(key);
            if (value == null || String.valueOf(value).trim().isEmpty()) return "缺少参数：" + key;
        }
        return null;
    }
}
