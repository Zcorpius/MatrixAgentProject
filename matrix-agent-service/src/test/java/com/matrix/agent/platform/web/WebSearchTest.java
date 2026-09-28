package com.matrix.agent.platform.web;

import static org.junit.Assert.*;
import okhttp3.*;
import org.junit.Test;

public final class WebSearchTest {
    @Test public void queryCannotChangePinnedHostPathOrRedirectPolicy() {
        for(var source:WebSearchSource.values()) {
            var url=source.endpoint("https://evil.example/?x=1#@private/../");
            assertTrue(url.isHttps()); assertEquals(source.host,url.host()); assertEquals(443,url.port()); assertTrue(url.username().isEmpty());
            assertFalse(source.acceptsCitation(HttpUrl.get("https://evil.example/abs/test")));
            assertFalse(source.acceptsCitation(HttpUrl.get("https://arxiv.org@evil.example/abs/test")));
        }
        assertThrows(IllegalArgumentException.class,()->new PinnedWebSearchTransport(new OkHttpClient()));
        assertThrows(IllegalArgumentException.class,()->WebSearchSource.parse("http://localhost"));
    }
    @Test public void sourcesAreStableBoundedAndExplicitlyNotFullText() throws Exception {
        String body="{\"query\":{\"search\":[{\"title\":\"Synthetic paper\",\"snippet\":\"<b>evidence</b>\"},{\"title\":\"Synthetic paper\",\"snippet\":\"duplicate\"}]}}";
        var hits=WebSearchResults.parse(WebSearchSource.WIKIPEDIA,body);
        assertEquals(1,hits.size()); assertEquals(false,hits.get(0).get("fullTextRead")); assertEquals("SEARCH_SNIPPET",hits.get(0).get("evidenceKind")); assertEquals("evidence",hits.get(0).get("snippet"));
        assertEquals(hits,WebSearchResults.parse(WebSearchSource.WIKIPEDIA,body));
        var metadata=WebSearchResults.parse(WebSearchSource.CROSSREF,"{\"status\":\"ok\",\"message\":{\"items\":[{\"DOI\":\"10.1234/test\",\"title\":[\"Title\"]}]}}");
        assertEquals("BIBLIOGRAPHIC_METADATA",metadata.get(0).get("evidenceKind"));
    }
    @Test public void atomRejectsEntitiesOffDomainLinksAndIncompleteBodies() throws Exception {
        String entry="<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>http://arxiv.org/abs/1234.5678</id><title>Paper</title><summary>Abstract</summary></entry></feed>";
        assertEquals("https://arxiv.org/abs/1234.5678",WebSearchResults.parse(WebSearchSource.ARXIV,entry).get(0).get("url"));
        assertThrows(Exception.class,()->WebSearchResults.parse(WebSearchSource.ARXIV,"<!DOCTYPE feed [<!ENTITY test SYSTEM 'file:///private'>]>"+entry));
        assertThrows(Exception.class,()->WebSearchResults.parse(WebSearchSource.ARXIV,entry.replace("http://arxiv.org","https://evil.example")));
        assertThrows(Exception.class,()->WebSearchResults.parse(WebSearchSource.ARXIV,entry.substring(0,80)));
    }
    @Test public void modelArgumentsCannotGrantNetworkOrSurviveRevocation() {
        var fetched=new java.util.concurrent.atomic.AtomicInteger(); var revoked=new java.util.concurrent.atomic.AtomicBoolean();
        var call=new com.matrix.agent.contract.ToolCall("web.search",java.util.Map.of("query","synthetic","source","wikipedia"));
        var provider=new WebSearchProvider((source,query,request)->{fetched.incrementAndGet();revoked.set(true);return "{\"query\":{\"search\":[]}}";});
        var interactive=com.matrix.agent.identity.AgentRequest.builder("search",com.matrix.agent.identity.Actor.DRIVER).build();
        assertEquals(com.matrix.agent.task.tool.ToolResult.Status.POLICY_REJECTED,provider.execute(interactive,call).getStatus());assertEquals(0,fetched.get());
        var scope=com.matrix.agent.identity.ExecutionScope.automatic(java.util.Set.of("web.search"),true,new com.matrix.agent.identity.ExecutionScope.Guard(){
            public String rejection(){return revoked.get()?"REVOKED":"";} public long remainingMillis(){return 10000;}public boolean reserveTool(){return true;}});
        var automatic=com.matrix.agent.identity.AgentRequest.builder("search",com.matrix.agent.identity.Actor.DRIVER).executionScope(scope).build();
        var result=provider.execute(automatic,call);assertEquals(1,fetched.get());assertFalse(result.isVerified());assertEquals(com.matrix.agent.task.tool.ToolResult.Status.POLICY_REJECTED,result.getStatus());
    }
    @Test public void transportBoundsBytesUtf8AndRedirectsWithoutCredentialsOrRetries() {
        var call=new com.matrix.agent.contract.ToolCall("web.search",java.util.Map.of("query","synthetic","source","wikipedia"));
        for(String mode:java.util.List.of("oversize","invalidUtf8","redirect")) {
            var requests=new java.util.concurrent.atomic.AtomicInteger();
            var client=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).addInterceptor(chain->{
                requests.incrementAndGet();assertNull(chain.request().header("Authorization"));assertEquals("en.wikipedia.org",chain.request().url().host());
                byte[] bytes=mode.equals("oversize")?new byte[PinnedWebSearchTransport.MAX_BYTES+1]:new byte[]{(byte)0xff};
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(mode.equals("redirect")?302:200).message("synthetic")
                        .header("Location","https://evil.example").body(ResponseBody.create(bytes,MediaType.get("application/json"))).build();
            }).build();
            try {
                var scope=com.matrix.agent.identity.ExecutionScope.automatic(java.util.Set.of("web.search"),true,new com.matrix.agent.identity.ExecutionScope.Guard(){
                    public String rejection(){return "";}public long remainingMillis(){return 10000;}public boolean reserveTool(){return true;}});
                var request=com.matrix.agent.identity.AgentRequest.builder("search",com.matrix.agent.identity.Actor.DRIVER).executionScope(scope).build();
                var result=new WebSearchProvider(new PinnedWebSearchTransport(client)).execute(request,call);
                assertFalse(mode,result.isVerified());assertEquals(mode,1,requests.get());assertEquals(0,request.getCancellationToken().abortHookCount());
            } finally {client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();}
        }
    }

}
