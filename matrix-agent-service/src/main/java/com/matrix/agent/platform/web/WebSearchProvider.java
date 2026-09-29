package com.matrix.agent.platform.web;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.WebCapabilities;
import com.matrix.agent.task.tool.ToolResult;
import java.util.*;

/** Verified means a bounded, well-formed provider response, never independent verification of its claims. */
public final class WebSearchProvider implements CapabilityProvider {
    @FunctionalInterface public interface Fetcher { String fetch(WebSearchSource source, String query, AgentRequest request) throws Exception; }
    private final Fetcher fetcher;
    public WebSearchProvider(Fetcher fetcher) { this.fetcher = Objects.requireNonNull(fetcher); }
    @Override public ToolResult execute(AgentRequest request, ToolCall call) {
        long start = System.nanoTime();
        if (!WebCapabilities.SEARCH.equals(call.getCapabilityName())) return ToolResult.rejected(call.getCapabilityName(),"未知检索能力");
        try {
            authorize(request);
            Object queryValue=call.argument("query"), sourceValue=call.argument("source");
            if (!(queryValue instanceof String query) || query.isBlank() || query.length()>300 || !(sourceValue instanceof String name)) {
                return ToolResult.rejected(WebCapabilities.SEARCH,"检索参数不符合限制");
            }
            WebSearchSource source=WebSearchSource.parse(name);
            String body=fetcher.fetch(source,query,request);
            authorize(request);
            var results=WebSearchResults.parse(source,body);
            authorize(request);
            return new ToolResult(ToolResult.Status.SUCCESS,WebCapabilities.SEARCH,
                    "已获取检索摘要/书目元数据，未读取全文，也未独立核验第三方断言。",
                    Map.of("version",WebSearchResults.VERSION,"source",source.wire,"sources",results,"count",results.size(),
                            "coverage","SNIPPETS_AND_METADATA_ONLY","retrievedAt",System.currentTimeMillis(),
                            "queryDigest",com.matrix.agent.schedule.domain.ScheduleCodec.digest(query)),true,elapsed(start));
        } catch (SecurityException denied) { return ToolResult.rejected(WebCapabilities.SEARCH,"联网计划未授权或授权已撤回"); }
        catch (InterruptedException | java.util.concurrent.CancellationException cancelled) {
            if (cancelled instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ToolResult(ToolResult.Status.CANCELLED,WebCapabilities.SEARCH,"检索已取消",Map.of(),false,elapsed(start));
        } catch (java.io.InterruptedIOException timeout) {
            return new ToolResult(request.isCancelled()?ToolResult.Status.CANCELLED:ToolResult.Status.TIMED_OUT,
                    WebCapabilities.SEARCH,"检索超时或被取消",Map.of(),false,elapsed(start));
        } catch (Exception failed) {
            return new ToolResult(ToolResult.Status.EXECUTION_FAILED,WebCapabilities.SEARCH,
                    "检索未获得可核验的完整响应",Map.of(),false,elapsed(start));
        }
    }
    static void authorize(AgentRequest request) throws java.io.InterruptedIOException {
        if (request.isCancelled()) throw new java.util.concurrent.CancellationException();
        if (request.remainingMillis() <= 0) throw new java.io.InterruptedIOException("search deadline exhausted");
        var scope=request.getExecutionScope();
        if (!scope.automatic() || !scope.networkAllowed() || !scope.allows(WebCapabilities.SEARCH)
                || !scope.rejection().isEmpty()) throw new SecurityException("search authority");
    }
    private static long elapsed(long start) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start); }
}
