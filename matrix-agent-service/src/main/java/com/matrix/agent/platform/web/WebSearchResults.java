package com.matrix.agent.platform.web;

import okhttp3.HttpUrl;
import org.json.*;
import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

/** Parsing verifies source identity and structure, not the truth of third-party assertions. */
public final class WebSearchResults {
    public static final int VERSION = 1, MAX_RESULTS = 5;
    private WebSearchResults() { }
    public static List<Map<String,Object>> parse(WebSearchSource source, String body) throws Exception {
        List<Map<String,Object>> result = new ArrayList<>();
        switch (source) {
            case WIKIPEDIA -> {
                var json = new JSONObject(body);
                if (json.has("error")) throw new IllegalArgumentException("search provider error");
                var hits = json.getJSONObject("query").getJSONArray("search");
                for (int i=0; i<Math.min(MAX_RESULTS,hits.length()); i++) {
                    var hit = hits.getJSONObject(i);
                    String title = hit.getString("title");
                    HttpUrl url = new HttpUrl.Builder().scheme("https").host("en.wikipedia.org")
                            .addPathSegment("wiki").addPathSegment(title.replace(' ','_')).build();
                    add(result, source, title, hit.optString("snippet"), url.toString(), "SEARCH_SNIPPET");
                }
            }
            case CROSSREF -> {
                var json = new JSONObject(body);
                if (!"ok".equals(json.getString("status"))) throw new IllegalArgumentException("search provider error");
                var hits = json.getJSONObject("message").getJSONArray("items");
                for (int i=0; i<Math.min(MAX_RESULTS,hits.length()); i++) {
                    var hit = hits.getJSONObject(i);
                    String doi = hit.getString("DOI");
                    if (!doi.matches("10\\.[0-9]{4,9}/[^\\s]{1,250}")) continue;
                    String title = hit.getJSONArray("title").getString(0);
                    HttpUrl url = new HttpUrl.Builder().scheme("https").host("doi.org").addPathSegments(doi).build();
                    add(result, source, title, hit.optString("abstract", "仅有书目信息；未提供摘要，不能据此推断论文结论。"), url.toString(),
                            hit.has("abstract") ? "DEPOSITED_ABSTRACT" : "BIBLIOGRAPHIC_METADATA");
                }
            }
            case ARXIV -> {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                // Android's parser does not consistently implement Xerces feature names. Reject declarations
                // before parsing, and reject every external resolution independently.
                if (java.util.regex.Pattern.compile("<!\\s*(DOCTYPE|ENTITY)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(body).find()) throw new IllegalArgumentException("XML declarations forbidden");
                factory.setExpandEntityReferences(false);
                var builder = factory.newDocumentBuilder();
                builder.setEntityResolver((publicId,systemId) -> { throw new org.xml.sax.SAXException("external entities forbidden"); });
                var document = builder.parse(new InputSource(new StringReader(body)));
                var entries = document.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry");
                for (int i=0; i<Math.min(MAX_RESULTS,entries.getLength()); i++) {
                    var entry = (org.w3c.dom.Element) entries.item(i);
                    String id = atom(entry,"id").replaceFirst("^http://arxiv\\.org/", "https://arxiv.org/");
                    add(result, source, atom(entry,"title"), atom(entry,"summary"), id, "AUTHOR_ABSTRACT");
                }
            }
        }
        return List.copyOf(result);
    }
    private static String atom(org.w3c.dom.Element entry,String name) {
        var elements = entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom",name);
        if (elements.getLength()!=1) throw new IllegalArgumentException("invalid Atom entry");
        return elements.item(0).getTextContent();
    }
    private static void add(List<Map<String,Object>> result, WebSearchSource source, String title, String snippet, String address, String kind) {
        HttpUrl url = HttpUrl.parse(address);
        if (!source.acceptsCitation(url) || url.toString().length()>2048) throw new IllegalArgumentException("untrusted citation URL");
        String id = "src_" + com.matrix.agent.schedule.domain.ScheduleCodec.digest(url.toString()).substring(0,16);
        if (result.stream().anyMatch(row -> row.get("id").equals(id))) return;
        result.add(Map.of("id",id,"title",plain(title,180),"url",url.toString(),"snippet",plain(snippet,700),
                "evidenceKind",kind,"fullTextRead",false));
    }
    private static String plain(String value,int limit) {
        if (value.length()>20_000) value=value.substring(0,20_000);
        value = value.replaceAll("<[^>]{0,1000}>"," ").replaceAll("\\s+"," ").strip();
        return new com.matrix.agent.task.redact.ModelSanitizer(limit).sanitize(value);
    }
}
