package com.matrix.agent.task.capability;

import com.matrix.agent.contract.schema.CanonicalSchema;
import java.util.Set;

/** Read-only capabilities used by the frozen weather workflow. */
public final class WeatherCapabilities {
    public static final String RESOLVE_CITY = "location.resolve_city";
    public static final String TODAY = "weather.today";
    public static final Set<String> ALL = Set.of(RESOLVE_CITY, TODAY);

    private WeatherCapabilities() { }

    public static CapabilityRegistry registerInto(CapabilityRegistry registry) {
        registry.register(CapabilityDefinition.builder(RESOLVE_CITY, RiskLevel.R0_READ_ONLY)
                .description("仅用于用户授权的天气计划：到点解析城市，不向模型返回原始经纬度")
                .writeOperation(false).timeoutMillis(8_000).idempotent(true)
                .parameterSchema(CanonicalSchema.object()
                        .property("mode", CanonicalSchema.string().enumValues("CURRENT_AT_TRIGGER", "FIXED_CITY").build())
                        .property("allowLocation", CanonicalSchema.booleanType().build())
                        .property("fixedCityId", CanonicalSchema.string().maxLength(32).build())
                        .property("fixedCityName", CanonicalSchema.string().maxLength(80).build())
                        .property("fixedCityZone", CanonicalSchema.string().maxLength(80).build())
                        .property("backupCityId", CanonicalSchema.string().maxLength(32).build())
                        .property("backupCityName", CanonicalSchema.string().maxLength(80).build())
                        .property("backupCityZone", CanonicalSchema.string().maxLength(80).build())
                        .required("mode").additionalProperties(false).build())
                .auditMessageTemplate("天气计划城市解析").auditFailureMessageTemplate("天气计划城市解析失败")
                .auditObservedAllowlist("cityId", "cityName", "zoneId", "source", "locatedAt", "accuracyBand").build());
        registry.register(CapabilityDefinition.builder(TODAY, RiskLevel.R0_READ_ONLY)
                .description("仅用于用户授权的天气计划：读取已解析城市的当天实况与预报")
                .writeOperation(false).timeoutMillis(16_000).idempotent(true)
                .parameterSchema(CanonicalSchema.object()
                        .property("cityId", CanonicalSchema.string().maxLength(32).build())
                        .property("cityName", CanonicalSchema.string().maxLength(80).build())
                        .property("zoneId", CanonicalSchema.string().maxLength(80).build())
                        .property("localDate", CanonicalSchema.string().maxLength(10).build())
                        .required("cityId", "cityName", "zoneId", "localDate")
                        .additionalProperties(false).build())
                .auditMessageTemplate("天气计划数据查询").auditFailureMessageTemplate("天气计划数据查询失败")
                .auditObservedAllowlist("cityId", "cityName", "localDate", "provider", "retrievedAt").build());
        return registry;
    }
}
