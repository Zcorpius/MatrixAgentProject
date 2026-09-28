package com.matrix.agent.platform.web;

import okhttp3.HttpUrl;
import java.util.Locale;

/** Only Host-pinned HTTPS endpoints are fetched. Result URLs are citations, never navigation commands. */
public enum WebSearchSource {
    WIKIPEDIA("wikipedia", "en.wikipedia.org"), CROSSREF("crossref", "api.crossref.org"), ARXIV("arxiv", "export.arxiv.org");
    public final String wire, host;
    WebSearchSource(String wire, String host) { this.wire = wire; this.host = host; }
    public static WebSearchSource parse(String wire) {
        for (var source : values()) if (source.wire.equals(wire)) return source;
        throw new IllegalArgumentException("unsupported search source");
    }
    public HttpUrl endpoint(String query) {
        if (query == null || query.isBlank() || query.length() > 300) throw new IllegalArgumentException("search query bound");
        var url = new HttpUrl.Builder().scheme("https").host(host);
        switch (this) {
            case WIKIPEDIA -> url.addPathSegments("w/api.php").addQueryParameter("action", "query")
                    .addQueryParameter("format", "json").addQueryParameter("list", "search")
                    .addQueryParameter("srsearch", query).addQueryParameter("srlimit", "5");
            case CROSSREF -> url.addPathSegment("works").addQueryParameter("query", query).addQueryParameter("rows", "5")
                    .addQueryParameter("select", "DOI,title,abstract,published");
            case ARXIV -> url.addPathSegments("api/query").addQueryParameter("search_query", arxivQuery(query))
                    .addQueryParameter("start", "0").addQueryParameter("max_results", "5");
        }
        return url.build();
    }
    private static String arxivQuery(String query) {
        String[] tokens = query.replaceAll("[^\\p{L}\\p{N}\\s-]", " ").strip().split("\\s+");
        return java.util.Arrays.stream(tokens).filter(value -> !value.isBlank()).limit(12)
                .map(value -> "all:\"" + value + "\"").collect(java.util.stream.Collectors.joining(" AND "));
    }
    public boolean acceptsCitation(HttpUrl url) {
        if (url == null || !url.scheme().equals("https") || !url.username().isEmpty()
                || !url.password().isEmpty() || url.port() != 443) return false;
        return switch (this) {
            case WIKIPEDIA -> url.host().equals("en.wikipedia.org") && url.encodedPath().startsWith("/wiki/");
            case CROSSREF -> url.host().equals("doi.org") && url.encodedPath().startsWith("/10.");
            case ARXIV -> url.host().equals("arxiv.org") && url.encodedPath().startsWith("/abs/");
        };
    }
}
