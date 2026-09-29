package com.matrix.agent.attachment.retrieval;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import org.junit.Test;

public final class Bm25RetrieverTest {
    @Test public void streamingQueryMatcherMatchesFullTokenizationScores() {
        Random random = new Random(17);
        String[] vocabulary = {"星", "河", "项", "目", "请", "问", "the", "sensor", "temperature", "42", "😀"};
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < 80; j++) {
                text.append(vocabulary[random.nextInt(vocabulary.length)]);
                if (random.nextBoolean()) text.append(' ');
            }
            chunks.addAll(DocumentChunker.split("attachment-" + i, text.toString()));
        }
        String question = "星河项目 sensor 42 temperature";
        List<Bm25Retriever.Hit> actual = new Bm25Retriever().rank(chunks, question, 64);
        List<Bm25Retriever.Hit> expected = reference(chunks, Set.copyOf(Bm25Retriever.terms(question)));
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).chunk(), actual.get(i).chunk());
            assertEquals(expected.get(i).score(), actual.get(i).score(), 1e-12);
        }
    }

    private static List<Bm25Retriever.Hit> reference(List<DocumentChunk> chunks, Set<String> query) {
        List<List<String>> tokens = chunks.stream().map(chunk -> Bm25Retriever.terms(chunk.text())).toList();
        Map<String, Integer> df = new HashMap<>();
        long total = 0;
        for (List<String> row : tokens) {
            total += row.size();
            Set<String> present = new HashSet<>(row);
            present.retainAll(query);
            for (String term : present) df.merge(term, 1, Integer::sum);
        }
        double average = Math.max(1, (double) total / chunks.size());
        List<Bm25Retriever.Hit> hits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            List<String> row = tokens.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for (String term : row) if (query.contains(term)) tf.merge(term, 1, Integer::sum);
            double score = 0;
            for (var term : tf.entrySet()) {
                int frequency = term.getValue(), documents = df.get(term.getKey());
                double idf = Math.log1p((chunks.size() - documents + .5) / (documents + .5));
                score += idf * (frequency * 2.2)
                        / (frequency + 1.2 * (.25 + .75 * row.size() / average));
            }
            if (score > 0) hits.add(new Bm25Retriever.Hit(chunks.get(i), score));
        }
        hits.sort(Comparator.comparingDouble(Bm25Retriever.Hit::score).reversed()
                .thenComparing(hit -> hit.chunk().attachmentId()).thenComparingInt(hit -> hit.chunk().ordinal()));
        return hits.subList(0, Math.min(64, hits.size()));
    }
}
