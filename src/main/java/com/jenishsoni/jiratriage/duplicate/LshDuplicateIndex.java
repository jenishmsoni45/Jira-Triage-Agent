package com.jenishsoni.jiratriage.duplicate;

import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.duplicate.DuplicateSearchResult.Candidate;
import com.jenishsoni.jiratriage.duplicate.DuplicateSearchResult.Diagnostics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LshDuplicateIndex {

    private final DuplicateSettings settings;
    private final TokenShingler shingler;
    private final MinHash minHash;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<BandKey, Set<String>> buckets = new HashMap<>();

    public LshDuplicateIndex(DuplicateSettings settings) {
        this.settings = settings;
        shingler = new TokenShingler(settings.shingleSize());
        minHash = new MinHash(settings.signatureSize(), settings.seed());
    }

    public synchronized void upsert(Issue issue) {
        Set<String> shingles = shingler.shingles(issue);
        Entry previous = entries.get(issue.key());
        if (previous != null && previous.shingles().equals(shingles)) {
            return;
        }

        List<BandKey> keys = shingles.isEmpty() ? List.of() : bandKeys(minHash.signature(shingles));
        remove(issue.key());
        entries.put(issue.key(), new Entry(shingles, keys));
        for (BandKey key : keys) {
            buckets.computeIfAbsent(key, ignored -> new HashSet<>()).add(issue.key());
        }
    }

    public synchronized void remove(String issueKey) {
        Entry previous = entries.remove(issueKey);
        if (previous == null) {
            return;
        }
        for (BandKey key : previous.bandKeys()) {
            Set<String> members = buckets.get(key);
            members.remove(issueKey);
            if (members.isEmpty()) {
                buckets.remove(key);
            }
        }
    }

    public synchronized DuplicateSearchResult findDuplicates(String issueKey) {
        Entry query = entries.get(issueKey);
        if (query == null) {
            throw new IllegalStateException("Issue is missing from duplicate index: " + issueKey);
        }
        Map<String, Integer> matchingBands = new HashMap<>();
        int largestBucket = 0;
        for (BandKey key : query.bandKeys()) {
            Set<String> members = buckets.get(key);
            largestBucket = Math.max(largestBucket, members.size());
            for (String member : members) {
                if (!member.equals(issueKey)) {
                    matchingBands.merge(member, 1, Integer::sum);
                }
            }
        }

        List<Candidate> matches = new ArrayList<>();
        for (var candidate : matchingBands.entrySet()) {
            Set<String> other = entries.get(candidate.getKey()).shingles();
            Set<String> smaller = query.shingles().size() <= other.size() ? query.shingles() : other;
            Set<String> larger = smaller == other ? query.shingles() : other;
            int shared = 0;
            for (String shingle : smaller) {
                if (larger.contains(shingle)) {
                    shared++;
                }
            }
            int union = query.shingles().size() + other.size() - shared;
            double similarity = (double) shared / union;
            if (similarity >= settings.minimumSimilarity()) {
                String reason = "Shares " + shared + " of " + union + " distinct token shingles; retrieved through "
                        + candidate.getValue() + " matching LSH bands. Score is exact Jaccard similarity, not a probability.";
                matches.add(new Candidate(candidate.getKey(), similarity, shared, union, candidate.getValue(), reason));
            }
        }
        matches.sort(Comparator.comparingDouble(Candidate::similarity).reversed().thenComparing(Candidate::issueKey));
        String explanation = query.shingles().isEmpty()
                ? "No letter/number tokens in the query; empty shingle sets are not indexed and produce no matches."
                : "MinHash/LSH retrieves approximate candidates; exact shingle Jaccard filters and ranks them. Scores are lexical similarity, not calibrated probabilities. LSH may miss similar issues.";
        return new DuplicateSearchResult(issueKey, "MINHASH_LSH_JACCARD", explanation,
                matches.stream().map(Candidate::issueKey).toList(), List.copyOf(matches), settings.minimumSimilarity(),
                new Diagnostics(query.shingles().size(), matchingBands.size(), largestBucket, false));
    }

    private List<BandKey> bandKeys(int[] signature) {
        List<BandKey> keys = new ArrayList<>(settings.bands());
        int rows = settings.rowsPerBand();
        for (int band = 0; band < settings.bands(); band++) {
            keys.add(new BandKey(band, Arrays.copyOfRange(signature, band * rows, (band + 1) * rows)));
        }
        return List.copyOf(keys);
    }

    private record Entry(Set<String> shingles, List<BandKey> bandKeys) {}

    private record BandKey(int band, int[] rows) {
        @Override
        public boolean equals(Object object) {
            return object instanceof BandKey other && band == other.band && Arrays.equals(rows, other.rows);
        }

        @Override
        public int hashCode() {
            return 31 * band + Arrays.hashCode(rows);
        }
    }
}
