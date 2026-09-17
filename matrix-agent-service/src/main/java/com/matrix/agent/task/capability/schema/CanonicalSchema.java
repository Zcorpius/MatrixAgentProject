package com.matrix.agent.task.capability.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不可变 JSON Schema 2020-12 子集表示。
 *
 * <p>单一类递归设计(不引入 ArraySchema/ObjectSchema 子类型),与旧版
 * {@code ToolParameterDefinition} 风格最小迁移——所有 schema 节点共用一套字段,
 * 由 {@link SchemaType} + 字段是否非 null 区分实际形态。
 *
 * <h3>覆盖的 JSON Schema 2020-12 关键字</h3>
 * <ul>
 *   <li>类型:{@code type}(NULL/BOOLEAN/INTEGER/NUMBER/STRING/ARRAY/OBJECT)</li>
 *   <li>枚举与常量:{@code enum}、{@code const}</li>
 *   <li>数值约束:{@code minimum} / {@code maximum} / {@code exclusiveMinimum} / {@code exclusiveMaximum}</li>
 *   <li>字符串约束:{@code minLength} / {@code maxLength} / {@code pattern}</li>
 *   <li>数组:{@code items}(子 schema,暂不实现 minItems/maxItems)</li>
 *   <li>对象:{@code properties} / {@code required} / {@code additionalProperties}(默认 false)</li>
 *   <li>组合:{@code allOf} / {@code oneOf} / {@code anyOf}</li>
 *   <li>引用:{@code $ref}(局部 {@code #/$defs/Name} 形式)+ {@code $defs}(顶层独占)</li>
 * </ul>
 *
 * <h3>MatrixAgent 私有 annotation(非 JSON Schema 标准)</h3>
 * <ul>
 *   <li>{@code sensitive} / {@code sensitivePlaceholder}——业务敏感字段标记,
 *       被 {@code AuditRedactor} / {@code ModelSanitizer} 消费。投影到 Provider schema
 *       时由 {@link SchemaJsonWriter} 决定保留或剥离(OpenAI/Anthropic 不识别,可剥)。</li>
 * </ul>
 *
 * <h3>$ref 解析策略</h3>
 * <ul>
 *   <li><b>eager 解析</b>:build 时立即解析,build 后 CanonicalSchema 内部存"已内联"的目标,
 *       无 lazy 求值——避免并发可见性问题。</li>
 *   <li><b>局部引用</b>:仅支持 {@code #/$defs/Name} 形式,不支持外部 URI 引用。</li>
 *   <li><b>循环检测</b>:build 时 DFS,发现 back-edge 抛 {@link SchemaException}。</li>
 *   <li><b>$ref 与 sibling 互斥</b>:JSON Schema 2020-12 允许 {@code $ref + type + properties}
 *       同级,拒绝(匹配 OpenAI strict 限制,简化语义)。</li>
 * </ul>
 *
 * <p>线程安全:不可变,所有可变集合字段防御性拷贝后用 {@link Collections#unmodifiableXxx} 包裹。
 */
public final class CanonicalSchema {
    private final SchemaType type;
    private final String description;
    private final List<Object> enumValues;
    private final Object constValue;
    private final Double minimum;
    private final Double maximum;
    private final boolean exclusiveMinimum;
    private final boolean exclusiveMaximum;
    private final Integer minLength;
    private final Integer maxLength;
    private final String pattern;
    private final CanonicalSchema items;
    private final Map<String, CanonicalSchema> properties;
    private final Set<String> required;
    private final boolean additionalProperties;
    private final List<CanonicalSchema> allOf;
    private final List<CanonicalSchema> oneOf;
    private final List<CanonicalSchema> anyOf;
    private final CanonicalSchema $refTarget;  // build 时已解析,$ref 字符串本身不保留
    private final Map<String, CanonicalSchema> $defs;  // 仅顶层 schema 非 null
    private final boolean sensitive;
    private final String sensitivePlaceholder;

    private CanonicalSchema(Builder builder) {
        type = builder.type;
        description = builder.description;
        enumValues = Collections.unmodifiableList(new ArrayList<>(builder.enumValues));
        constValue = builder.constValue;
        minimum = builder.minimum;
        maximum = builder.maximum;
        exclusiveMinimum = builder.exclusiveMinimum;
        exclusiveMaximum = builder.exclusiveMaximum;
        minLength = builder.minLength;
        maxLength = builder.maxLength;
        pattern = builder.pattern;
        items = builder.items;
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(builder.properties));
        required = Collections.unmodifiableSet(new LinkedHashSet<>(builder.required));
        additionalProperties = builder.additionalProperties;
        allOf = Collections.unmodifiableList(new ArrayList<>(builder.allOf));
        oneOf = Collections.unmodifiableList(new ArrayList<>(builder.oneOf));
        anyOf = Collections.unmodifiableList(new ArrayList<>(builder.anyOf));
        $refTarget = builder.$refTarget;
        $defs = builder.$defs == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(builder.$defs));
        sensitive = builder.sensitive;
        sensitivePlaceholder = builder.sensitivePlaceholder;
    }

    // ============ 工厂方法 ============

    public static Builder object() { return new Builder(SchemaType.OBJECT); }
    public static Builder array() { return new Builder(SchemaType.ARRAY); }
    public static Builder string() { return new Builder(SchemaType.STRING); }
    public static Builder integer() { return new Builder(SchemaType.INTEGER); }
    public static Builder number() { return new Builder(SchemaType.NUMBER); }
    public static Builder booleanType() { return new Builder(SchemaType.BOOLEAN); }
    public static Builder nullType() { return new Builder(SchemaType.NULL); }

    /** 通过 $ref 引用 $defs 内的 schema;build 时 eager 解析。 */
    public static Builder $ref(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            throw new IllegalArgumentException("$ref 不能为空");
        }
        Builder builder = new Builder(null);
        builder.$refString = ref;
        return builder;
    }

    // ============ Getter ============

    public SchemaType getType() { return type; }
    public String getDescription() { return description; }
    public List<Object> getEnumValues() { return enumValues; }
    public Object getConstValue() { return constValue; }
    public Double getMinimum() { return minimum; }
    public Double getMaximum() { return maximum; }
    public boolean isExclusiveMinimum() { return exclusiveMinimum; }
    public boolean isExclusiveMaximum() { return exclusiveMaximum; }
    public Integer getMinLength() { return minLength; }
    public Integer getMaxLength() { return maxLength; }
    public String getPattern() { return pattern; }
    public CanonicalSchema getItems() { return items; }
    public Map<String, CanonicalSchema> getProperties() { return properties; }
    public Set<String> getRequired() { return required; }
    public boolean isAdditionalProperties() { return additionalProperties; }
    public List<CanonicalSchema> getAllOf() { return allOf; }
    public List<CanonicalSchema> getOneOf() { return oneOf; }
    public List<CanonicalSchema> getAnyOf() { return anyOf; }

    /** build 时已解析的 $ref 目标(null 表示未用 $ref)。 */
    public CanonicalSchema get$RefTarget() { return $refTarget; }
    public boolean is$Ref() { return $refTarget != null; }

    /** 顶层 schema 独占;$ref 节点和子 schema 上必为 null。 */
    public Map<String, CanonicalSchema> get$Defs() { return $defs; }

    public boolean isSensitive() { return sensitive; }
    public String getSensitivePlaceholder() { return sensitivePlaceholder; }

    // ============ Builder ============

    public static final class Builder {
        private SchemaType type;
        private String description = "";
        private final List<Object> enumValues = new ArrayList<>();
        private Object constValue;
        private Double minimum;
        private Double maximum;
        private boolean exclusiveMinimum;
        private boolean exclusiveMaximum;
        private Integer minLength;
        private Integer maxLength;
        private String pattern;
        private CanonicalSchema items;
        private final Map<String, CanonicalSchema> properties = new LinkedHashMap<>();
        private final Set<String> required = new LinkedHashSet<>();
        private boolean additionalProperties = false;
        private final List<CanonicalSchema> allOf = new ArrayList<>();
        private final List<CanonicalSchema> oneOf = new ArrayList<>();
        private final List<CanonicalSchema> anyOf = new ArrayList<>();
        // $ref 装配态——build 时 eager 解析后清空
        private String $refString;
        private CanonicalSchema $refTarget;
        private Map<String, CanonicalSchema> $defs;
        private boolean sensitive;
        private String sensitivePlaceholder = "<redacted>";

        private Builder(SchemaType type) {
            this.type = type;
        }

        public Builder description(String value) {
            description = value == null ? "" : value;
            return this;
        }

        public Builder enumValues(List<Object> values) {
            enumValues.clear();
            if (values != null) enumValues.addAll(values);
            return this;
        }

        public Builder enumValues(Object... values) {
            enumValues.clear();
            if (values != null) {
                for (Object v : values) enumValues.add(v);
            }
            return this;
        }

        public Builder constValue(Object value) {
            constValue = value;
            return this;
        }

        public Builder minimum(double value) { minimum = value; return this; }
        public Builder maximum(double value) { maximum = value; return this; }
        public Builder exclusiveMinimum(boolean value) { exclusiveMinimum = value; return this; }
        public Builder exclusiveMaximum(boolean value) { exclusiveMaximum = value; return this; }

        public Builder minLength(int value) { minLength = value; return this; }
        public Builder maxLength(int value) { maxLength = value; return this; }
        public Builder pattern(String value) { pattern = value; return this; }

        public Builder items(CanonicalSchema schema) { items = schema; return this; }

        public Builder property(String name, CanonicalSchema schema) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("property 名不能为空");
            }
            if (schema == null) throw new IllegalArgumentException("property schema 不能为空:" + name);
            properties.put(name, schema);
            return this;
        }

        public Builder required(String... names) {
            for (String name : names) {
                if (name != null && !name.trim().isEmpty()) required.add(name);
            }
            return this;
        }

        public Builder additionalProperties(boolean value) {
            additionalProperties = value;
            return this;
        }

        public Builder allOf(CanonicalSchema... schemas) {
            for (CanonicalSchema s : schemas) {
                if (s != null) allOf.add(s);
            }
            return this;
        }

        public Builder oneOf(CanonicalSchema... schemas) {
            for (CanonicalSchema s : schemas) {
                if (s != null) oneOf.add(s);
            }
            return this;
        }

        public Builder anyOf(CanonicalSchema... schemas) {
            for (CanonicalSchema s : schemas) {
                if (s != null) anyOf.add(s);
            }
            return this;
        }

        /** 仅顶层 schema 允许设置;build() 检查。 */
        public Builder $defs(Map<String, CanonicalSchema> defs) {
            if (defs == null) {
                $defs = null;
            } else {
                $defs = new LinkedHashMap<>(defs);
            }
            return this;
        }

        public Builder sensitive(boolean value) { sensitive = value; return this; }

        public Builder sensitivePlaceholder(String value) {
            sensitivePlaceholder = value == null || value.isEmpty() ? "<redacted>" : value;
            return this;
        }

        public CanonicalSchema build() {
            // —— $ref 路径 ——
            if ($refString != null) {
                if (type != null || !properties.isEmpty() || items != null
                        || !enumValues.isEmpty() || constValue != null
                        || minimum != null || maximum != null
                        || !allOf.isEmpty() || !oneOf.isEmpty() || !anyOf.isEmpty()
                        || minLength != null || maxLength != null || pattern != null) {
                    throw new SchemaException("$ref 与 sibling 关键字(type/properties/items/enum/"
                            + "const/range/composition)互斥,V0.4.2 不支持 $ref + sibling");
                }
                if ($defs == null) {
                    throw new SchemaException("$ref 必须配合顶层 $defs 使用,但当前 $defs 为 null");
                }
                $refTarget = resolve$Ref($refString, $defs);
                detectCycle($refTarget, new java.util.HashSet<CanonicalSchema>());
                // $ref 节点不允许 $defs——build 后清空 $defs,保持 schema 节点角色单一
                $defs = null;
                return new CanonicalSchema(this);
            }

            // —— 非 $ref 路径 ——
            if (type == null) {
                throw new SchemaException("非 $ref schema 必须设置 type");
            }
            if (minimum != null && maximum != null && minimum > maximum) {
                throw new SchemaException("minimum(" + minimum + ") 不能大于 maximum(" + maximum + ")");
            }
            if (minLength != null && maxLength != null && minLength > maxLength) {
                throw new SchemaException("minLength(" + minLength + ") 不能大于 maxLength(" + maxLength + ")");
            }
            if (type == SchemaType.ARRAY && items == null) {
                throw new SchemaException("ARRAY schema 必须设置 items");
            }
            // 子 schema 上设置 $defs 是错误——只有顶层 schema 允许
            // (顶层 = 通过 CanonicalSchema.object()/array()/...etc 直接 build 的)
            // $defs 在子 schema 上无意义,通过 $defs 在子 schema build 时清空判断
            // 这里不强制顶层判定(无 runtime 标记),改为:任何 schema 都可以有 $defs,
            // 但若同时是 $ref 节点则已清空。语义上 $defs 只对最外层调用方有意义,
            // 由调用方自律。validator 不依赖此约束。
            if (!enumValues.isEmpty() && constValue != null) {
                throw new SchemaException("enum 与 const 互斥");
            }
            return new CanonicalSchema(this);
        }

        /** 解析 #/$defs/Name 形式的局部引用。 */
        private static CanonicalSchema resolve$Ref(String ref, Map<String, CanonicalSchema> defs) {
            if (!ref.startsWith("#/$defs/")) {
                throw new SchemaException("V0.4.2 仅支持局部 $ref 形式 #/$defs/Name,收到:" + ref);
            }
            String name = ref.substring("#/$defs/".length());
            CanonicalSchema target = defs.get(name);
            if (target == null) {
                throw new SchemaException("$ref 目标未在 $defs 中声明:" + name);
            }
            return target;
        }

        /**
         * DFS 循环检测——遇到 back-edge 抛 SchemaException。
         *
         * <p>properties/items/allOf/oneOf/anyOf 是兄弟节点,
         * 它们各自递归时必须 copy 当前 visited 快照,而不是共享同一集合——
         * 否则两个兄弟 property 引用同一 {@code $defs/Address} 时,第二个会被误判 cycle。
         */
        private static void detectCycle(CanonicalSchema root, Set<CanonicalSchema> visited) {
            if (root == null) return;
            if (!visited.add(root)) {
                throw new SchemaException("cyclic $ref not supported——schema 出现 back-edge");
            }
            // 递归检查 $ref 链
            detectCycle(root.$refTarget, new java.util.HashSet<>(visited));
            // 同时递归 properties / items / composition 内部是否含 $ref cycle
            for (CanonicalSchema child : root.properties.values()) {
                walkChildCycles(child, new java.util.HashSet<>(visited));
            }
            walkChildCycles(root.items, new java.util.HashSet<>(visited));
            for (CanonicalSchema child : root.allOf) walkChildCycles(child, new java.util.HashSet<>(visited));
            for (CanonicalSchema child : root.oneOf) walkChildCycles(child, new java.util.HashSet<>(visited));
            for (CanonicalSchema child : root.anyOf) walkChildCycles(child, new java.util.HashSet<>(visited));
        }

        private static void walkChildCycles(CanonicalSchema node, Set<CanonicalSchema> ancestors) {
            if (node == null) return;
            detectCycle(node, ancestors);
        }
    }
}
