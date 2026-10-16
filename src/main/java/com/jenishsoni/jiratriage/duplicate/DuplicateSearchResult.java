package com.jenishsoni.jiratriage.duplicate;

import java.util.List;

public record DuplicateSearchResult(
        String issueKey, String method, String explanation, List<String> candidateKeys,
        List<Candidate> candidates, double minimumSimilarity, Diagnostics diagnostics) {

    public record Candidate(String issueKey, double similarity, int sharedShingles, int unionShingles,
                            int matchingBands, String reason) {}

    public record Diagnostics(int queryShingles, int retrievedCandidates, int largestBucketSize,
                              boolean candidatesTruncated) {}
}
