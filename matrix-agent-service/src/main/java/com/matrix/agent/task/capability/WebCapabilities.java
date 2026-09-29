package com.matrix.agent.task.capability;

import com.matrix.agent.contract.schema.CanonicalSchema;

/** First release is restricted to explicitly network-authorized scheduled work. */
public final class WebCapabilities {
    public static final String SEARCH = "web.search";
    private WebCapabilities() { }
    public static CapabilityRegistry registerInto(CapabilityRegistry registry) {
        return registry.register(CapabilityDefinition.builder(SEARCH, RiskLevel.R0_READ_ONLY)
                .description("联网计划的受控只读检索：搜索百科、Crossref 学术元数据或 arXiv 摘要；返回来源与检索时间，未读取全文。需要计划的网络授权。")
                .writeOperation(false).idempotent(true).timeoutMillis(20_000).maxRetries(0)
                .parameterSchema(CanonicalSchema.object().additionalProperties(false)
                        .property("query", CanonicalSchema.string().minLength(1).maxLength(300)
                                .sensitive(true).sensitivePlaceholder("<search-query>").build())
                        .property("source", CanonicalSchema.string().enumValues("wikipedia", "crossref", "arxiv").build())
                        .required("query", "source").build())
                .auditMessageTemplate("只读检索已完成（摘要/元数据）").auditFailureMessageTemplate("只读检索未完成")
                .sensitiveObservedField("sources", "<search-sources>")
                .auditObservedAllowlist("source", "count", "coverage", "version", "retrievedAt").build());
    }
}
