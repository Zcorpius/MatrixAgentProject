package com.matrix.agent.attachment.retrieval;

import java.util.*;

/** BM25 with term frequency, document frequency and length normalization; bounded CJK bigrams. */
public final class Bm25Retriever {
    private static final double K1 = 1.2, B = .75;
    private static final Set<String> STOP = Set.of("请问", "请帮", "帮我", "一下", "这个", "那个", "文档", "附件",
            "资料", "里面", "哪些", "什么", "多少", "如何", "请根", "根据", "回答", "the", "and", "for", "what", "which", "please");
    private static final Set<Long> STOP_HAN_PAIRS = hanPairs(STOP);
    public record Hit(DocumentChunk chunk, double score) { }
    private record Features(DocumentChunk chunk, int length, Map<String,Integer> matches) { }
    public List<Hit> rank(List<DocumentChunk> chunks, String question, int limit) {
        if (chunks.size() > 4 * DocumentChunker.MAX_CHUNKS || limit < 1 || limit > 64) {
            throw new IllegalArgumentException("retrieval bound");
        }
        Set<String> query = new LinkedHashSet<>(terms(question));
        query.removeAll(STOP);
        if (query.size() > 64) query = new LinkedHashSet<>(new ArrayList<>(query).subList(0,64));
        if (query.isEmpty() || chunks.isEmpty()) return List.of();
        QueryMatcher matcher = new QueryMatcher(query);
        Map<String,Integer> documentFrequency = new HashMap<>();
        List<Features> features = new ArrayList<>();
        long totalLength = 0;
        for (DocumentChunk chunk : chunks) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            Features row = matcher.scan(chunk);
            for (String term : row.matches().keySet()) documentFrequency.merge(term, 1, Integer::sum);
            features.add(row);
            totalLength += row.length();
        }
        double averageLength = Math.max(1, (double) totalLength / chunks.size());
        List<Hit> hits = new ArrayList<>();
        for (Features row : features) {
            double score = 0;
            for (var term : row.matches().entrySet()) {
                int df = documentFrequency.get(term.getKey()), tf = term.getValue();
                double idf = Math.log1p((chunks.size() - df + .5) / (df + .5));
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * row.length() / averageLength));
            }
            if (score > 0) hits.add(new Hit(row.chunk(), score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(hit -> hit.chunk().attachmentId()).thenComparingInt(hit -> hit.chunk().ordinal()));
        return List.copyOf(hits.subList(0, Math.min(limit, hits.size())));
    }
    static List<String> terms(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> terms = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int priorHan = -1, hanCount = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                flush(word, terms);
                if (priorHan >= 0) terms.add(new String(Character.toChars(priorHan)) + new String(Character.toChars(cp)));
                priorHan = cp; hanCount++;
            } else {
                if (hanCount == 1) terms.add(new String(Character.toChars(priorHan)));
                priorHan = -1; hanCount = 0;
                if (Character.isLetterOrDigit(cp)) {
                    if (word.length() < 64) word.appendCodePoint(Character.toLowerCase(cp));
                } else flush(word, terms);
            }
        }
        if (hanCount == 1) terms.add(new String(Character.toChars(priorHan)));
        flush(word, terms);
        terms.removeIf(STOP::contains);
        return terms;
    }
    private static void flush(StringBuilder word, List<String> terms) {
        if (word.length() > 0) { terms.add(word.toString()); word.setLength(0); }
    }

    private static long pair(int first, int second) { return ((long) first << 21) | second; }
    private static boolean han(int cp) { return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN; }
    private static Set<Long> hanPairs(Set<String> words) {
        Set<Long> result = new HashSet<>();
        for (String word : words) {
            int[] codepoints = word.codePoints().toArray();
            if (codepoints.length == 2 && han(codepoints[0]) && han(codepoints[1])) {
                result.add(pair(codepoints[0], codepoints[1]));
            }
        }
        return Set.copyOf(result);
    }

    /** Count every non-stop term for BM25 length, but materialize only query matches. */
    private static final class QueryMatcher {
        private final Map<Long, String> pairs = new HashMap<>();
        private final Map<Integer, String> singles = new HashMap<>();
        private final Set<String> words = new HashSet<>();

        QueryMatcher(Set<String> query) {
            for (String term : query) {
                int[] codepoints = term.codePoints().toArray();
                if (codepoints.length == 2 && han(codepoints[0]) && han(codepoints[1])) {
                    pairs.put(pair(codepoints[0], codepoints[1]), term);
                } else if (codepoints.length == 1 && han(codepoints[0])) {
                    singles.put(codepoints[0], term);
                } else words.add(term);
            }
        }

        Features scan(DocumentChunk chunk) {
            String text = chunk.text();
            Map<String, Integer> matches = new HashMap<>();
            StringBuilder word = new StringBuilder();
            int previousHan = -1, hanCount = 0, length = 0;
            for (int offset = 0; offset < text.length();) {
                int cp = text.codePointAt(offset);
                offset += Character.charCount(cp);
                if (han(cp)) {
                    length += flushWord(word, matches);
                    if (previousHan >= 0) {
                        long key = pair(previousHan, cp);
                        if (!STOP_HAN_PAIRS.contains(key)) {
                            length++;
                            String match = pairs.get(key);
                            if (match != null) matches.merge(match, 1, Integer::sum);
                        }
                    }
                    previousHan = cp;
                    hanCount++;
                } else {
                    if (hanCount == 1) length += single(previousHan, matches);
                    previousHan = -1;
                    hanCount = 0;
                    if (Character.isLetterOrDigit(cp)) {
                        if (word.length() < 64) word.appendCodePoint(Character.toLowerCase(cp));
                    } else length += flushWord(word, matches);
                }
            }
            if (hanCount == 1) length += single(previousHan, matches);
            length += flushWord(word, matches);
            return new Features(chunk, length, matches);
        }

        private int single(int codepoint, Map<String, Integer> matches) {
            String term = singles.get(codepoint);
            if (term != null) matches.merge(term, 1, Integer::sum);
            return 1;
        }

        private int flushWord(StringBuilder word, Map<String, Integer> matches) {
            if (word.length() == 0) return 0;
            String term = word.toString();
            word.setLength(0);
            if (STOP.contains(term)) return 0;
            if (words.contains(term)) matches.merge(term, 1, Integer::sum);
            return 1;
        }
    }
}
