package com.matrix.agent.task.capability;
import com.matrix.agent.task.redact.*;

import com.matrix.agent.vehicle.VehicleStatePredicate;

import com.matrix.agent.identity.VehicleZone;

import com.matrix.agent.contract.schema.SchemaJsonWriter;
import com.matrix.agent.contract.ToolParameterDefinition;
import com.matrix.agent.contract.schema.CanonicalSchema;
import com.matrix.agent.contract.schema.SchemaType;
import com.matrix.agent.identity.*;
import com.matrix.agent.intent.*;
import com.matrix.agent.vehicle.*;


import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class CapabilityDefinition {
    private final String name;
    private final String description;
    private final RiskLevel riskLevel;
    private final boolean writeOperation;
    private final boolean idempotent;
    private final long timeoutMillis;
    private final int maxRetries;
    /**
     * 兼容字段——已 {@code @Deprecated},内部桥接到 {@link #verifyMethod}。
     * 通过 {@link #isVerificationRequired()} 暴露,语义为 {@code verifyMethod != NONE}。
     */
    private final boolean verificationRequired;
    /** 主字段——区分 4 种 verify 策略(NONE / READBACK_FIELD / READBACK_GET / 预留)。 */
    private final VerifyMethod verifyMethod;
    private final boolean targetZoneRequired;
    private final Set<VehicleZone> allowedTargetZones;
    /**
     * capability 前置车辆状态约束(AND 语义,空集表示无约束)。
     * PolicyEngine 在 schema 校验之前判定,不满足归 CAPABILITY 拒绝(不可上诉)。
     */
    private final Set<VehicleStatePredicate> requiredVehicleStates;
    private final CapabilityValidator validator;
    /**
     * 主字段——{@link CanonicalSchema} 完整 JSON Schema 2020-12 子集。
     * <p>{@link #toolParameters} 是兼容字段,由 capability registry 走 deprecated
     * {@code parameter()} 入口填入;{@code ModelApiClient} 走 schema 投影后该字段
     * 不再被 production 代码读,后续删除。
     */
    private final CanonicalSchema parameterSchema;
    private final List<ToolParameterDefinition> toolParameters;
    // Audit 视图投影规则。
    private final String auditMessageTemplate;
    private final Map<String, String> sensitiveObservedFields;
    // 失败状态专用模板 + observedState allowlist(失败 / 未知字段 fail-closed)。
    private final String auditFailureMessageTemplate;
    private final Set<String> auditObservedAllowlist;

    private CapabilityDefinition(Builder builder) {
        name = builder.name;
        description = builder.description;
        riskLevel = builder.riskLevel;
        writeOperation = builder.writeOperation;
        idempotent = builder.idempotent;
        timeoutMillis = builder.timeoutMillis;
        maxRetries = builder.maxRetries;
        verificationRequired = builder.verificationRequired;
        verifyMethod = builder.verifyMethod;
        targetZoneRequired = builder.targetZoneRequired;
        allowedTargetZones = Collections.unmodifiableSet(EnumSet.copyOf(builder.allowedTargetZones));
        requiredVehicleStates = Collections.unmodifiableSet(EnumSet.copyOf(builder.requiredVehicleStates));
        validator = builder.validator;
        parameterSchema = builder.parameterSchema;
        toolParameters = Collections.unmodifiableList(new ArrayList<>(builder.toolParameters));
        auditMessageTemplate = builder.auditMessageTemplate;
        sensitiveObservedFields = Collections.unmodifiableMap(new LinkedHashMap<>(builder.sensitiveObservedFields));
        auditFailureMessageTemplate = builder.auditFailureMessageTemplate;
        auditObservedAllowlist = Collections.unmodifiableSet(new LinkedHashSet<>(builder.auditObservedAllowlist));
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public boolean isWriteOperation() { return writeOperation; }
    public boolean isIdempotent() { return idempotent; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public int getMaxRetries() { return maxRetries; }
    /**
     * 兼容入口——内部映射 {@code verifyMethod != NONE}。
     * <p>调用方应直接用 {@link #getVerifyMethod()} 区分具体策略。
     */
    public boolean isVerificationRequired() { return verifyMethod != VerifyMethod.NONE; }
    /** 主入口——区分 NONE / READBACK_FIELD / READBACK_GET 等。 */
    public VerifyMethod getVerifyMethod() { return verifyMethod; }
    public boolean isTargetZoneRequired() { return targetZoneRequired; }
    public Set<VehicleZone> getAllowedTargetZones() { return allowedTargetZones; }
    /** capability 前置车辆状态约束(AND 语义)。 */
    public Set<VehicleStatePredicate> getRequiredVehicleStates() { return requiredVehicleStates; }

    /**
     * 主入口——返回完整 JSON Schema 2020-12 子集 OBJECT。
     *
     * <p>若 Builder 直接配了 {@code parameterSchema()} 用之;若仍走
     * {@code parameter()} 入口,由 {@link #projectSchemaFromToolParameters} 投影
     * 出 OBJECT 节点(损失 nested 信息,但保行为兼容)。
     */
    public CanonicalSchema getParameterSchema() {
        if (parameterSchema != null) return parameterSchema;
        if (toolParameters.isEmpty()) {
            // 无参数 capability,返回 empty OBJECT(additionalProperties=false,无 properties)
            return CanonicalSchema.object().additionalProperties(false).build();
        }
        return projectSchemaFromToolParameters();
    }

    /**
     * 兼容入口——仅 scalar 4 种 type(STRING/INTEGER/NUMBER/BOOLEAN)。
     * <p>若 Builder 直接配了 {@code parameterSchema()},由 schema 反向投影出
     * ToolParameterDefinition;否则返回 Builder 显式配的 toolParameters。
     */
    public List<ToolParameterDefinition> getToolParameters() {
        if (!toolParameters.isEmpty()) return toolParameters;
        if (parameterSchema == null) return Collections.emptyList();
        return projectToolParametersFromSchema();
    }

    /**
     * Schema → ToolParameterDefinition 投影——仅供 ModelApiClient 旧版路径兼容。
     * 重写 ModelApiClient 用 SchemaJsonWriter 后此方法删除。
     */
    private List<ToolParameterDefinition> projectToolParametersFromSchema() {
        List<ToolParameterDefinition> list = new ArrayList<>();
        for (Map.Entry<String, CanonicalSchema> entry : parameterSchema.getProperties().entrySet()) {
            String name = entry.getKey();
            CanonicalSchema node = entry.getValue();
            ToolParameterDefinition.Type mapped = mapSchemaType(node.getType());
            if (mapped == null) continue;  // ARRAY/OBJECT 节点无法投影到 Type,跳过
            ToolParameterDefinition.Builder b = ToolParameterDefinition.builder(name, mapped)
                    .description(node.getDescription())
                    .required(parameterSchema.getRequired().contains(name));
            if (!node.getEnumValues().isEmpty()) {
                List<String> enums = new ArrayList<>();
                for (Object v : node.getEnumValues()) enums.add(String.valueOf(v));
                b.enumValues(enums);
            }
            if (node.getMinimum() != null) {
                if (node.getMaximum() != null) {
                    b.range(node.getMinimum(), node.getMaximum());
                } else {
                    b.range(node.getMinimum(), Double.MAX_VALUE);
                }
            } else if (node.getMaximum() != null) {
                b.range(-Double.MAX_VALUE, node.getMaximum());
            }
            if (node.isSensitive()) {
                b.sensitive(true).sensitivePlaceholder(node.getSensitivePlaceholder());
            }
            list.add(b.build());
        }
        return list;
    }

    private static ToolParameterDefinition.Type mapSchemaType(SchemaType type) {
        switch (type) {
            case STRING: return ToolParameterDefinition.Type.STRING;
            case INTEGER: return ToolParameterDefinition.Type.INTEGER;
            case NUMBER: return ToolParameterDefinition.Type.NUMBER;
            case BOOLEAN: return ToolParameterDefinition.Type.BOOLEAN;
            default: return null;
        }
    }

    /**
     * ToolParameterDefinition → Schema 投影——兼容入口 {@link Builder#parameter} 的桥接。
     */
    private CanonicalSchema projectSchemaFromToolParameters() {
        CanonicalSchema.Builder b = CanonicalSchema.object().additionalProperties(false);
        for (ToolParameterDefinition p : toolParameters) {
            SchemaType t;
            switch (p.getType()) {
                case STRING: t = SchemaType.STRING; break;
                case INTEGER: t = SchemaType.INTEGER; break;
                case NUMBER: t = SchemaType.NUMBER; break;
                case BOOLEAN: t = SchemaType.BOOLEAN; break;
                default: throw new IllegalStateException("未知 type:" + p.getType());
            }
            b.property(p.getName(), toSchemaNode(p, t));
            if (p.isRequired()) b.required(p.getName());
        }
        return b.build();
    }

    /** ToolParameterDefinition → CanonicalSchema scalar 节点(STRING/INTEGER/NUMBER/BOOLEAN)。 */
    private static CanonicalSchema toSchemaNode(ToolParameterDefinition p, SchemaType t) {
        CanonicalSchema.Builder prop;
        switch (t) {
            case STRING: prop = CanonicalSchema.string(); break;
            case INTEGER: prop = CanonicalSchema.integer(); break;
            case NUMBER: prop = CanonicalSchema.number(); break;
            case BOOLEAN: prop = CanonicalSchema.booleanType(); break;
            default: throw new IllegalStateException("未知 type:" + t);
        }
        prop.description(p.getDescription());
        if (!p.getEnumValues().isEmpty()) {
            List<Object> enums = new ArrayList<>();
            for (String v : p.getEnumValues()) enums.add(v);
            prop.enumValues(enums);
        }
        if (p.getMinimum() != null) prop.minimum(p.getMinimum());
        if (p.getMaximum() != null) prop.maximum(p.getMaximum());
        if (p.isSensitive()) {
            prop.sensitive(true).sensitivePlaceholder(p.getSensitivePlaceholder());
        }
        return prop.build();
    }

    /**
     * Audit 视图中 ToolResult.message 的固定模板。
     *
     * <p>当 capability 涉及业务敏感字段时(导航目的地 / 联系人 / 地址),Provider 的 message
     * 通常会把真实值嵌入(如 "已开始导航到 \"北京市某小区\"")——这种字符串原样进 Trajectory /
     * UI / 日志会泄漏。配 auditMessageTemplate 后,AuditRedactor 用此模板替换原 message。
     */
    public String getAuditMessageTemplate() { return auditMessageTemplate; }

    /**
     * ToolResult.observedState 中的敏感字段及其占位符。
     *
     * <p>key = observedState 字段名(如 "navigation.destination"),
     * value = 替换占位符(如 "&lt;destination&gt;")。
     * AuditRedactor.redact(observation) 按此 map 替换 observedState 对应字段。
     */
    public Map<String, String> getSensitiveObservedFields() { return sensitiveObservedFields; }

    /**
     * 失败状态(EXECUTION_FAILED / VERIFICATION_FAILED)专用 Audit 模板。
     *
     * <p>背景:Provider 失败 message 常含业务值(如 "导航到北京市某小区失败:网络不可用"),
     * 通用凭据正则只识别 API Key/Token/Bearer,识别不出目的地、地址、联系人。
     * 仅当 capability 已经配置了 {@link #getAuditMessageTemplate()}(声明 Audit Schema),
     * 失败状态也必须按 schema 投影——配了 failure template 用之,没配用通用占位符,
     * 不允许 fall through 到 {@code redact(message)}(凭据正则识别不出业务值)。
     */
    public String getAuditFailureMessageTemplate() { return auditFailureMessageTemplate; }

    /**
     * observedState 的显式 allowlist(诊断安全字段)。
     *
     * <p>非空时,AuditRedactor 走 fail-closed:sensitiveObservedFields 中的字段用占位符,
     * allowlist 中的字段走 redactValue(允许通过),**其余字段一律替换为 {@code ***}**。
     *
     * <p>Provider 新增 schema 外字段(如 navigation.poi / route.destination)默认 mask,
     * 防止真实 Provider 把目的地 / 联系人 / 地址塞进未知回读字段。
     */
    public Set<String> getAuditObservedAllowlist() { return auditObservedAllowlist; }

    public String validateArguments(java.util.Map<String, Object> arguments) {
        return validator == null ? null : validator.validate(arguments);
    }

    public static Builder builder(String name, RiskLevel riskLevel) {
        return new Builder(name, riskLevel);
    }

    public static final class Builder {
        private final String name;
        private final RiskLevel riskLevel;
        private String description = "";
        private boolean writeOperation;
        private boolean idempotent = true;
        private long timeoutMillis = 3_000L;
        private int maxRetries;
        private boolean verificationRequired;
        private VerifyMethod verifyMethod = VerifyMethod.NONE;
        private boolean targetZoneRequired;
        private Set<VehicleZone> allowedTargetZones = EnumSet.allOf(VehicleZone.class);
        private Set<VehicleStatePredicate> requiredVehicleStates = EnumSet.noneOf(VehicleStatePredicate.class);
        private CapabilityValidator validator;
        /**
         * 主字段——完整 JSON Schema 2020-12 子集 OBJECT。
         * 调用方应优先使用 {@link #parameterSchema(CanonicalSchema)} 入口;
         * {@link #parameter(ToolParameterDefinition)} 入口仍可用(@Deprecated),
         * 由 {@link CapabilityDefinition#getParameterSchema()} 投影出 schema。
         */
        private CanonicalSchema parameterSchema;
        private final List<ToolParameterDefinition> toolParameters = new ArrayList<>();
        private String auditMessageTemplate;
        private final Map<String, String> sensitiveObservedFields = new LinkedHashMap<>();
        private String auditFailureMessageTemplate;
        private final Set<String> auditObservedAllowlist = new LinkedHashSet<>();

        private Builder(String name, RiskLevel riskLevel) {
            if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Capability name 不能为空");
            this.name = name;
            this.riskLevel = riskLevel;
        }

        public Builder description(String value) { description = value == null ? "" : value; return this; }
        public Builder writeOperation(boolean value) { writeOperation = value; return this; }
        public Builder idempotent(boolean value) { idempotent = value; return this; }
        public Builder timeoutMillis(long value) { timeoutMillis = value; return this; }
        public Builder maxRetries(int value) { maxRetries = value; return this; }

        /** 主入口——配置 verify 策略(NONE / READBACK_FIELD / READBACK_GET 等)。 */
        public Builder verifyMethod(VerifyMethod value) {
            if (value == null) throw new IllegalArgumentException("verifyMethod 不能为空");
            verifyMethod = value;
            // 同步旧字段,调用方仍能读 isVerificationRequired()
            verificationRequired = value != VerifyMethod.NONE;
            return this;
        }

        /**
         * 入口——二值 verify。
         *
         * @deprecated 改用 {@link #verifyMethod(VerifyMethod)}。{@code true}
         * 桥接到 {@link VerifyMethod#READBACK_FIELD}(mock 默认行为),
         * {@code false} 设为 {@link VerifyMethod#NONE}。
         */
        @Deprecated
        public Builder verificationRequired(boolean value) {
            verificationRequired = value;
            verifyMethod = value ? VerifyMethod.READBACK_FIELD : VerifyMethod.NONE;
            return this;
        }
        public Builder targetZoneRequired(boolean value) { targetZoneRequired = value; return this; }
        public Builder allowedTargetZones(Set<VehicleZone> value) {
            allowedTargetZones = value == null || value.isEmpty()
                    ? EnumSet.noneOf(VehicleZone.class) : EnumSet.copyOf(value);
            return this;
        }
        /** 声明前置车辆状态约束(AND 语义,POLICYENGINE schema 校验之前判定)。 */
        public Builder requiredVehicleStates(VehicleStatePredicate... predicates) {
            Set<VehicleStatePredicate> set = EnumSet.noneOf(VehicleStatePredicate.class);
            if (predicates != null) {
                for (VehicleStatePredicate p : predicates) {
                    if (p != null) set.add(p);
                }
            }
            requiredVehicleStates = set;
            return this;
        }
        public Builder validator(CapabilityValidator value) { validator = value; return this; }

        /** 主入口——配置完整 JSON Schema 2020-12 OBJECT 参数 schema。 */
        public Builder parameterSchema(CanonicalSchema value) {
            if (value == null) throw new IllegalArgumentException("parameterSchema 不能为空");
            if (value.getType() != SchemaType.OBJECT) {
                throw new IllegalArgumentException("parameterSchema 必须是 OBJECT 类型,实际:"
                        + value.getType());
            }
            parameterSchema = value;
            return this;
        }

        /** 设置 Audit 视图中 ToolResult.message 的固定模板。 */
        public Builder auditMessageTemplate(String value) {
            auditMessageTemplate = value == null || value.isEmpty() ? null : value;
            return this;
        }
        /** 声明 observedState 中的敏感字段及其占位符。 */
        public Builder sensitiveObservedField(String fieldName, String placeholder) {
            if (fieldName == null || fieldName.isEmpty()) {
                throw new IllegalArgumentException("敏感 observed 字段名不能为空");
            }
            if (placeholder == null || placeholder.isEmpty()) {
                throw new IllegalArgumentException("占位符不能为空");
            }
            sensitiveObservedFields.put(fieldName, placeholder);
            return this;
        }
        /** 失败状态(EXECUTION_FAILED / VERIFICATION_FAILED)专用 Audit 模板。 */
        public Builder auditFailureMessageTemplate(String value) {
            auditFailureMessageTemplate = value == null || value.isEmpty() ? null : value;
            return this;
        }
        /** 声明 observedState 中允许进 Audit 视图的诊断安全字段。 */
        public Builder auditObservedAllowlist(String... fields) {
            auditObservedAllowlist.clear();
            if (fields == null) return this;
            for (String f : fields) {
                if (f != null && !f.isEmpty()) auditObservedAllowlist.add(f);
            }
            return this;
        }
        public CapabilityDefinition build() {
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis 必须大于 0");
            if (maxRetries < 0) throw new IllegalArgumentException("maxRetries 不能小于 0");
            return new CapabilityDefinition(this);
        }
    }
}
