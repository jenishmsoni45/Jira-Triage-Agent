package com.jenishsoni.jiratriage.duplicate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenishsoni.jiratriage.domain.Issue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.jenishsoni.jiratriage.duplicate.LshDuplicateIndexTest.SETTINGS;
import static com.jenishsoni.jiratriage.duplicate.LshDuplicateIndexTest.issue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateRetrievalQualityTest {

    @Test
    void comparesLshRetrievalWithExhaustiveJaccardOnASmallFixedCorpus() throws Exception {
        List<Issue> corpus = List.of(
                issue("Q-1", "Password reset email link expires", "Opening the password reset email link shows an expired token error."),
                issue("Q-2", "Password reset email link expires immediately", "Opening the password reset email link shows an expired token error. Users cannot sign in."),
                issue("Q-3", "Password reset email link expires", "Opening the password reset email link shows an expired token error after refreshing."),
                issue("Q-4", "Board assignee filter resets on refresh", "Refreshing the project board clears the selected assignee filter."),
                issue("Q-5", "Board assignee filter resets on refresh unexpectedly", "Refreshing the project board clears the selected assignee filter. The board displays all issues."),
                issue("Q-6", "Board assignee filter resets after refresh", "Refreshing the project board clears the selected assignee filter."),
                issue("Q-7", "CSV export drops accented characters", "Exporting the report as CSV removes accented characters from customer names."),
                issue("Q-8", "CSV export drops accented characters unexpectedly", "Exporting the report as CSV removes accented characters from customer names. This affects downloaded files."),
                issue("Q-9", "CSV export removes accented characters", "Exporting the report as CSV removes accented characters from customer names."),
                issue("Q-10", "Keyboard shortcuts for sprint navigation", "Add shortcuts to move between sprint planning views."),
                issue("Q-11", "Rotate service account certificates", "Renew the staging certificate before its scheduled expiry."),
                issue("Q-12", "Document mobile notification settings", "Explain which push alerts can be disabled on mobile devices."));
        var shingler = new TokenShingler(SETTINGS.shingleSize());
        var index = new LshDuplicateIndex(SETTINGS);
        corpus.forEach(index::upsert);
        Set<Set<String>> oraclePairs = new HashSet<>();
        for (int left = 0; left < corpus.size(); left++) {
            for (int right = left + 1; right < corpus.size(); right++) {
                double score = bruteForceJaccard(shingler.shingles(corpus.get(left)), shingler.shingles(corpus.get(right)));
                if (score >= SETTINGS.minimumSimilarity()) {
                    oraclePairs.add(Set.of(corpus.get(left).key(), corpus.get(right).key()));
                }
            }
        }

        Set<Set<String>> retrievedPairs = new HashSet<>();
        int directedCandidateCount = 0;
        for (Issue query : corpus) {
            var result = index.findDuplicates(query.key());
            directedCandidateCount += result.diagnostics().retrievedCandidates();
            for (var match : result.candidates()) {
                Issue candidate = corpus.stream().filter(issue -> issue.key().equals(match.issueKey())).findFirst().orElseThrow();
                double exact = bruteForceJaccard(shingler.shingles(query), shingler.shingles(candidate));
                assertEquals(exact, match.similarity(), 1e-12);
                retrievedPairs.add(Set.of(query.key(), match.issueKey()));
            }
        }
        Set<Set<String>> missed = new HashSet<>(oraclePairs);
        missed.removeAll(retrievedPairs);
        double recall = (double) (oraclePairs.size() - missed.size()) / oraclePairs.size();
        Path report = Path.of("target", "duplicate-retrieval-quality.json");
        Files.createDirectories(report.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                "corpusIssues", corpus.size(), "possiblePairs", corpus.size() * (corpus.size() - 1) / 2,
                "settings", SETTINGS, "jaccardOraclePairs", oraclePairs.size(),
                "lshCandidatePairsBeforeThreshold", directedCandidateCount / 2,
                "retrievedOraclePairs", oraclePairs.size() - missed.size(), "candidateRecall", recall,
                "missedPairs", missed.stream().map(pair -> pair.stream().sorted().toList()).sorted((a, b) -> a.toString().compareTo(b.toString())).toList(),
                "scope", "Fixed synthetic corpus; oracle is a Jaccard threshold, not human duplicate labels. No measured precision or scale claim."));
        assertTrue(oraclePairs.size() >= 6, "Corpus must contain several useful near-duplicate pairs");
        assertTrue(oraclePairs.containsAll(retrievedPairs), "Exact reranking must reject below-threshold candidates");
        assertTrue(recall >= 0.9, () -> "Unexpected retrieval regression; inspect " + report);
        assertTrue(directedCandidateCount / 2 < corpus.size() * (corpus.size() - 1) / 2,
                "This corpus should not require scoring every pair");
    }

    private static double bruteForceJaccard(Set<String> left, Set<String> right) {
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        if (union.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return (double) intersection.size() / union.size();
    }
}
