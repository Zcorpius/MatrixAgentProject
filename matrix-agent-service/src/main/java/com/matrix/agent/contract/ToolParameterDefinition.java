package com.matrix.agent.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider-neutral subset of JSON Schema used for model tool definitions. */
public final class ToolParameterDefinition {
    public enum Type { STRING, INTEGER, NUMBER, BOOLEAN }

    private final String name;
    private final Type type;
    private final String description;
    private final boolean required;
    private final List<String> enumValues;
    private final Double minimum;
    private final Double maximum;
    private final boolean sensitive;
    private final String sensitivePlaceholder;

    private ToolParameterDefinition(Builder builder) {
        name = builder.name;
        type = builder.type;
        description = builder.description;
        required = builder.required;
        enumValues = Collections.unmodifiableList(new ArrayList<>(builder.enumValues));
        minimum = builder.minimum;
        maximum = builder.maximum;
        sensitive = builder.sensitive;
        sensitivePlaceholder = builder.sensitivePlaceholder;
    }

    public String getName() { return name; }
    public Type getType() { return type; }
    public String getDescription() { return description; }
    public boolean isRequired() { return required; }
    public List<String> getEnumValues() { return enumValues; }
    public Double getMinimum() { return minimum; }
    public Double getMaximum() { return maximum; }
    public boolean isSensitive() { return sensitive; }
    public String getSensitivePlaceholder() { return sensitivePlaceholder; }

    public static Builder builder(String name, Type type) { return new Builder(name, type); }

    public static final class Builder {
        private final String name;
        private final Type type;
        private String description = "";
        private boolean required;
        private List<String> enumValues = Collections.emptyList();
        private Double minimum;
        private Double maximum;
        private boolean sensitive;
        private String sensitivePlaceholder = "<redacted>";

        private Builder(String name, Type type) {
            if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("参数名不能为空");
            if (type == null) throw new IllegalArgumentException("参数类型不能为空");
            this.name = name;
            this.type = type;
        }

        public Builder description(String value) { description = value == null ? "" : value; return this; }
        public Builder required(boolean value) { required = value; return this; }
        public Builder enumValues(List<String> value) {
            enumValues = value == null ? Collections.emptyList() : new ArrayList<>(value);
            return this;
        }
        public Builder range(double min, double max) { minimum = min; maximum = max; return this; }
        /**
         * 标记此参数为业务敏感字段——AuditRedactor 会把 value 替换为占位符,
         * 不进 Trajectory / UI / 日志。模型仍能看到真实值(ModelSanitizer 不脱敏业务字段)。
         */
        public Builder sensitive(boolean value) { sensitive = value; return this; }
        public Builder sensitivePlaceholder(String value) {
            sensitivePlaceholder = value == null || value.isEmpty() ? "<redacted>" : value;
            return this;
        }
        public ToolParameterDefinition build() {
            if (minimum != null && maximum != null && minimum > maximum) {
                throw new IllegalArgumentException("参数最小值不能大于最大值");
            }
            return new ToolParameterDefinition(this);
        }
    }
}
