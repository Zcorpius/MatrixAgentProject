package com.matrix.agent.contract;

import com.matrix.agent.contract.schema.CanonicalSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ToolDefinition {
    private final String modelName;
    private final String capabilityName;
    private final String description;
    private final List<ToolParameterDefinition> parameters;
    /**
     * Provider-neutral JSON Schema 2020-12 参数视图。
     * 由 {@link CapabilityRegistry#toToolDefinitions()} 填充。
     * ModelApiClient 优先用此字段走 SchemaJsonWriter;若 null(测试或兼容场景),
     * fallback 到 {@link #parameters} 的旧版路径。
     */
    private final CanonicalSchema parametersSchema;

    public ToolDefinition(String modelName, String capabilityName, String description,
            List<ToolParameterDefinition> parameters) {
        this(modelName, capabilityName, description, parameters, null);
    }

    public ToolDefinition(String modelName, String capabilityName, String description,
            List<ToolParameterDefinition> parameters, CanonicalSchema parametersSchema) {
        this.modelName = modelName;
        this.capabilityName = capabilityName;
        this.description = description == null ? "" : description;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        this.parametersSchema = parametersSchema;
    }

    public String getModelName() { return modelName; }
    public String getCapabilityName() { return capabilityName; }
    public String getDescription() { return description; }
    public List<ToolParameterDefinition> getParameters() { return parameters; }
    public CanonicalSchema getParametersSchema() { return parametersSchema; }
}
