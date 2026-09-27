package com.matrix.agent.schedule.execution;

import com.matrix.agent.api.schedule.ScheduleAction;
import com.matrix.agent.api.schedule.ScheduleCodes;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.capability.RiskLevel;

/** Static metadata only; reminder wakeups never instantiate providers or the full Host graph. */
public final class ScheduleActionPolicy {
    private ScheduleActionPolicy() { }
    private static final class Metadata {
        static final CapabilityRegistry REGISTRY = CapabilityRegistry.createRuntimeRegistry();
    }
    public static void validate(ScheduleAction action) {
        if (action.kind == ScheduleCodes.NOTIFICATION) return;
        for (String capability : action.capabilities) {
            var definition = Metadata.REGISTRY.find(capability);
            if (definition == null || definition.getRiskLevel() == RiskLevel.R3_PROHIBITED || capability.startsWith("schedule.")) {
                throw new IllegalArgumentException("不能授权自动执行此能力：" + capability);
            }
            if (action.kind == ScheduleCodes.TOOL) {
                var arguments = com.matrix.agent.schedule.domain.ScheduleCodec.arguments(action.parametersJson);
                var schema = com.matrix.agent.contract.schema.SchemaValidator.INSTANCE.validateArguments(definition.getParameterSchema(), arguments);
                String invalid = definition.validateArguments(arguments);
                if (!schema.isOk() || invalid != null) throw new IllegalArgumentException("确定动作的参数不符合能力约束：" + capability);
            }
        }
    }
}
