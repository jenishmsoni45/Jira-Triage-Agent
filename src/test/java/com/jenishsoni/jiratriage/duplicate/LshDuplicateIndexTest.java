package com.jenishsoni.jiratriage.duplicate;

import com.jenishsoni.jiratriage.support.TestDuplicateSettings;
import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.domain.IssuePriority;
import com.jenishsoni.jiratriage.domain.IssueStatus;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LshDuplicateIndexTest {

    static final DuplicateSettings SETTINGS = TestDuplicateSettings.defaults();

    @Test
    void shinglesNormalizeUnicodeAndPunctuationWithoutCrossingFieldBoundaries() {
        var shingler = new TokenShingler(2);
        assertEquals(Set.of("alpha beta", "beta gamma", "delta epsilon"),
                shingler.shingles(issue("T-1", "ＡLPHA, beta\u00a0GAMMA", "delta\n epsilon")));
        assertEquals(Set.of("crash"), shingler.shingles(issue("T-2", "ＣＲＡＳＨ!", null)));
        assertEquals(Set.of(), shingler.shingles(issue("T-3", "?!", "...")));
        assertEquals(Set.of("alpha beta"), new TokenShingler(3).shingles(issue("T-4", "alpha beta", "")));
    }

    @Test
    void signaturesAreReproducibleAcrossSetOrderAndRespectSeedAndSize() {
        var shingles = new LinkedHashSet<>(List.of("reset email", "email link", "link expires"));
        var reversed = new LinkedHashSet<>(List.of("link expires", "email link", "reset email"));
        int[] first = new MinHash(32, 42).signature(shingles);
        assertEquals(32, first.length);
        assertArrayEquals(first, new MinHash(32, 42).signature(reversed));
        assertFalse(Arrays.equals(first, new MinHash(32, 43).signature(shingles)));
        assertArrayEquals(Arrays.copyOf(first, 16), new MinHash(16, 42).signature(shingles));
    }

    @Test
    void retrievesNearDuplicatesAndReranksByExactJaccardRegardlessOfInsertionOrder() {
        List<Issue> issues = List.of(
                issue("T-1", "alpha beta gamma delta epsilon", ""),
                issue("T-2", "alpha beta gamma delta zeta", ""),
                issue("T-3", "ALPHA beta gamma delta epsilon!", ""),
                issue("T-4", "warehouse inventory delivery schedule", ""),
                issue("T-5", "alpha beta gamma delta epsilon", ""));
        var forward = index(issues);
        var reversed = index(issues.reversed());
        var result = forward.findDuplicates("T-1");
        assertEquals(result, reversed.findDuplicates("T-1"));
        assertEquals(List.of("T-3", "T-5", "T-2"), result.candidateKeys());
        assertEquals(1.0, result.candidates().get(0).similarity());
        var near = result.candidates().get(2);
        assertEquals(0.6, near.similarity(), 1e-12);
        assertEquals(3, near.sharedShingles());
        assertEquals(5, near.unionShingles());
        assertTrue(near.reason().contains("3 of 5"));
        assertTrue(near.reason().contains("not a probability"));
        assertTrue(forward.findDuplicates("T-4").candidates().isEmpty());

        var stricter = new LshDuplicateIndex(new DuplicateSettings(SETTINGS.shingleSize(), SETTINGS.signatureSize(), SETTINGS.bands(), SETTINGS.seed(), 0.9));
        issues.forEach(stricter::upsert);
        assertEquals(result.diagnostics().retrievedCandidates(), stricter.findDuplicates("T-1").diagnostics().retrievedCandidates());
        assertEquals(List.of("T-3", "T-5"), stricter.findDuplicates("T-1").candidateKeys());
    }

    @Test
    void shortIssuesWorkButTokenlessIssuesNeverShareEmptySignatureBuckets() {
        var index = index(List.of(
                issue("T-1", "Crash", ""), issue("T-2", "ＣＲＡＳＨ!", null),
                issue("T-3", "...", ""), issue("T-4", "!!!", "???")));
        assertEquals(List.of("T-2"), index.findDuplicates("T-1").candidateKeys());
        assertEquals(1.0, index.findDuplicates("T-1").candidates().get(0).similarity());
        var empty = index.findDuplicates("T-3");
        assertTrue(empty.candidates().isEmpty());
        assertEquals(0, empty.diagnostics().queryShingles());
        assertEquals(0, empty.diagnostics().retrievedCandidates());
        assertEquals(0, empty.diagnostics().largestBucketSize());
    }

    @Test
    void largeBucketsDoNotTruncateOrRepeatCandidatesAcrossBands() {
        var index = new LshDuplicateIndex(SETTINGS);
        for (int key = 1; key <= 150; key++) {
            index.upsert(issue("T-" + key, "identical issue summary", "identical description"));
        }
        var result = index.findDuplicates("T-1");
        assertEquals(149, result.candidateKeys().size());
        assertEquals(149, Set.copyOf(result.candidateKeys()).size());
        assertFalse(result.candidateKeys().contains("T-1"));
        assertEquals(150, result.diagnostics().largestBucketSize());
        assertEquals(149, result.diagnostics().retrievedCandidates());
        assertFalse(result.diagnostics().candidatesTruncated());
        assertTrue(result.candidates().stream().allMatch(candidate -> candidate.matchingBands() == SETTINGS.bands()));
    }

    @Test
    void replacingAndRemovingEntriesCleansOldBucketsIncludingEmptyTextTransitions() {
        var index = index(List.of(issue("T-1", "alpha beta gamma", ""), issue("T-2", "alpha beta gamma", "")));
        assertEquals(List.of("T-2"), index.findDuplicates("T-1").candidateKeys());
        index.upsert(issue("T-2", "warehouse inventory delivery", ""));
        assertTrue(index.findDuplicates("T-1").candidates().isEmpty());
        assertEquals(0, index.findDuplicates("T-1").diagnostics().retrievedCandidates());
        index.upsert(issue("T-2", "alpha beta gamma", ""));
        assertEquals(List.of("T-2"), index.findDuplicates("T-1").candidateKeys());
        index.upsert(issue("T-2", "...", ""));
        assertEquals(0, index.findDuplicates("T-1").diagnostics().retrievedCandidates());
        index.upsert(issue("T-2", "alpha beta gamma", ""));
        index.remove("T-2");
        index.remove("T-2");
        assertEquals(0, index.findDuplicates("T-1").diagnostics().retrievedCandidates());
        assertEquals(1, index.findDuplicates("T-1").diagnostics().largestBucketSize());
        assertThrows(IllegalStateException.class, () -> index.findDuplicates("T-2"));
    }

    @Test
    void invalidConfigurationsFailInsteadOfSilentlyDroppingSignatureRows() {
        assertThrows(IllegalArgumentException.class, () -> new DuplicateSettings(2, 127, 64, 1, 0.3));
        assertThrows(IllegalArgumentException.class, () -> new DuplicateSettings(2, 128, 0, 1, 0.3));
        assertThrows(IllegalArgumentException.class, () -> new DuplicateSettings(0, 128, 64, 1, 0.3));
        assertThrows(IllegalArgumentException.class, () -> new DuplicateSettings(2, 0, 1, 1, 0.3));
        assertThrows(IllegalArgumentException.class, () -> new DuplicateSettings(2, 128, 64, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new DuplicateSettings(2, 128, 64, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DuplicateSettings(2, 128, 64, 1, 1.01));
    }

    static Issue issue(String key, String summary, String description) {
        return new Issue(key, summary, description, IssueStatus.OPEN, IssuePriority.MEDIUM, List.of());
    }

    private static LshDuplicateIndex index(List<Issue> issues) {
        var index = new LshDuplicateIndex(SETTINGS);
        issues.forEach(index::upsert);
        return index;
    }
}
