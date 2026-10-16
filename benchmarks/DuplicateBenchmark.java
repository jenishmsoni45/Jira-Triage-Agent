import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.domain.IssuePriority;
import com.jenishsoni.jiratriage.domain.IssueStatus;
import com.jenishsoni.jiratriage.duplicate.DuplicateSettings;
import com.jenishsoni.jiratriage.duplicate.LshDuplicateIndex;
import com.jenishsoni.jiratriage.duplicate.TokenShingler;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class DuplicateBenchmark {
    private static DuplicateSettings settings;
    private static volatile long consumed;

    public static void main(String[] args) throws Exception {
        settings = new DuplicateSettings(2, Integer.parseInt(args[4]), Integer.parseInt(args[5]), 20261007L, 0.30);
        int count = Integer.parseInt(args[0]);
        String mode = args[1];
        long seed = Long.parseLong(args[2]);
        int queries = Math.min(Integer.parseInt(args[3]), count);
        if (count < 1 || queries < 1 || !Set.of("mixed", "boilerplate", "quality", "quality-v2").contains(mode)) {
            throw new IllegalArgumentException("Positive sizes/queries and mixed, boilerplate, or quality mode required");
        }
        warmup(seed);
        List<Issue> issues = corpus(count, seed, mode.equals("boilerplate"), mode.equals("quality-v2"));
        emit("corpusSha256", fingerprint(issues));
        emit("issues", count);
        emit("seed", seed);
        emit("mode", mode);
        emit("signatureSize", settings.signatureSize());
        emit("bands", settings.bands());
        if (mode.startsWith("quality")) { quality(issues); return; }

        System.gc();
        long beforeHeap = usedHeap();
        long start = System.nanoTime();
        var index = new LshDuplicateIndex(settings);
        issues.forEach(index::upsert);
        double buildMillis = millisSince(start);
        System.gc();
        long afterHeap = usedHeap();
        emit("buildMillis", buildMillis);
        emit("heapBeforeIndexBytes", beforeHeap);
        emit("heapAfterIndexBytes", afterHeap);
        emit("approximateIndexHeapDeltaBytes", afterHeap - beforeHeap);
        emit("maxHeapBytes", Runtime.getRuntime().maxMemory());

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < count; i++) { order.add(i); }
        Collections.shuffle(order, new Random(seed ^ 0x7175657279L));

        for (int i = 0; i < Math.min(100, count); i++) {
            consumed += index.findDuplicates(issues.get(order.get(i)).key()).candidates().size();
        }
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcMillis();
        double[] latency = new double[queries];
        long candidateSum = 0;
        long resultSum = 0;
        int maxCandidates = 0;
        int largestBucket = 0;
        for (int i = 0; i < queries; i++) {
            start = System.nanoTime();
            var result = index.findDuplicates(issues.get(order.get(i)).key());
            latency[i] = millisSince(start);
            int candidates = result.diagnostics().retrievedCandidates();
            candidateSum += candidates;
            resultSum += result.candidates().size();
            maxCandidates = Math.max(maxCandidates, candidates);
            largestBucket = Math.max(largestBucket, result.diagnostics().largestBucketSize());
            if (result.diagnostics().candidatesTruncated()) { throw new AssertionError("Unexpected candidate truncation"); }
        }
        Arrays.sort(latency);
        emit("queries", queries);
        emit("queryP50Millis", percentile(latency, 0.50));
        emit("queryP95Millis", percentile(latency, 0.95));
        emit("queryMaxMillis", latency[latency.length - 1]);
        emit("meanRetrievedCandidates", (double) candidateSum / queries);
        emit("maxRetrievedCandidates", maxCandidates);
        emit("meanReturnedMatches", (double) resultSum / queries);
        emit("largestVisitedBucket", largestBucket);
        emit("queryGcCollections", gcCount() - gcCountBefore);
        emit("queryGcMillis", gcMillis() - gcTimeBefore);
        consumed += candidateSum + resultSum;
    }

    private static List<Issue> corpus(int count, long seed, boolean boilerplate, boolean boundaryCases) {
        Random random = new Random(seed);
        List<Issue> result = new ArrayList<>(count);
        String[] components = {"login", "board", "export", "notification", "search", "billing", "mobile", "upload"};
        for (int group = 0; result.size() < count; group++) {
            String component = components[random.nextInt(components.length)];
            List<String> base = new ArrayList<>();
            for (int token = 0; token < 40; token++) { base.add("token" + random.nextInt(2000)); }
            for (int variant = 0; variant < 5 && result.size() < count; variant++) {
                List<String> words = new ArrayList<>(base);

                int edits = switch (variant) { case 2 -> 2; case 3 -> 8; case 4 -> boundaryCases ? 12 : 25; default -> 0; };
                for (int edit = 0; edit < edits; edit++) {
                    words.set((edit * 7) % words.size(), "replacement" + random.nextInt(2000));
                }
                String summary = boilerplate ? "Login fails after password reset"
                        : component + " fails after refresh in component group" + (boundaryCases ? seed + "g" + group : group);
                String description = boilerplate ? "Opening the password reset link shows an expired token error"
                        : "Steps to reproduce " + String.join(" ", words);
                result.add(new Issue("BENCH-" + (result.size() + 1), summary, description,
                        IssueStatus.OPEN, IssuePriority.MEDIUM, List.of("synthetic")));
            }
        }
        return List.copyOf(result);
    }

    private static void warmup(long seed) {
        var warm = new LshDuplicateIndex(settings);
        var issues = corpus(1000, seed ^ 0x7761726dL, false, false);
        issues.forEach(warm::upsert);
        for (int i = 0; i < 200; i++) { consumed += warm.findDuplicates(issues.get(i).key()).candidates().size(); }
    }

    private static void quality(List<Issue> issues) {
        var shingler = new TokenShingler(settings.shingleSize());
        var shingles = issues.stream().map(shingler::shingles).toList();
        Set<String> oracle = new HashSet<>();
        Set<String> boundaryOracle = new HashSet<>();
        long start = System.nanoTime();
        for (int left = 0; left < issues.size(); left++) {
            for (int right = left + 1; right < issues.size(); right++) {
                double score = jaccard(shingles.get(left), shingles.get(right));
                if (score >= settings.minimumSimilarity()) {
                    oracle.add(pair(left, right));
                    if (score < 0.40) { boundaryOracle.add(pair(left, right)); }
                }
            }
        }
        emit("bruteForceScoringMillis", millisSince(start));
        start = System.nanoTime();
        var index = new LshDuplicateIndex(settings);
        issues.forEach(index::upsert);
        emit("indexBuildMillis", millisSince(start));
        Set<String> retrieved = new HashSet<>();
        long directedCandidates = 0;
        start = System.nanoTime();
        for (int left = 0; left < issues.size(); left++) {
            var found = index.findDuplicates(issues.get(left).key());
            directedCandidates += found.diagnostics().retrievedCandidates();
            for (var candidate : found.candidates()) {
                int right = Integer.parseInt(candidate.issueKey().substring("BENCH-".length())) - 1;
                if (Math.abs(candidate.similarity() - jaccard(shingles.get(left), shingles.get(right))) > 1e-12) {
                    throw new AssertionError("Production score disagrees with independent exact Jaccard");
                }
                retrieved.add(pair(left, right));
            }
        }
        emit("allIndexQueriesAndScoreChecksMillis", millisSince(start));
        if (!oracle.containsAll(retrieved)) { throw new AssertionError("Reranked result below oracle threshold"); }
        emit("boundaryOraclePairs", boundaryOracle.size());
        long recoveredBoundary = boundaryOracle.stream().filter(retrieved::contains).count();
        emit("recoveredBoundaryPairs", recoveredBoundary);
        emit("boundaryRecall", boundaryOracle.isEmpty() ? "undefined" : (double) recoveredBoundary / boundaryOracle.size());
        emit("possiblePairs", (long) issues.size() * (issues.size() - 1) / 2);
        emit("oraclePairs", oracle.size());
        emit("retrievedOraclePairs", retrieved.size());
        emit("missedOraclePairs", oracle.size() - retrieved.size());
        emit("rawCandidatePairs", directedCandidates / 2);
        emit("lexicalOracleRecall", oracle.isEmpty() ? "undefined" : (double) retrieved.size() / oracle.size());
        oracle.removeAll(retrieved);
        emit("missedPairIndices", String.join(",", oracle.stream().sorted().toList()));
    }

    private static double jaccard(Set<String> left, Set<String> right) {
        long shared = left.stream().filter(right::contains).count();
        long union = left.size() + right.size() - shared;
        return union == 0 ? 0 : (double) shared / union;
    }

    private static String pair(int left, int right) { return Math.min(left, right) + ":" + Math.max(left, right); }
    private static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum(); }
    private static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum(); }
    private static double millisSince(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static double percentile(double[] sorted, double percentile) { return sorted[(int) Math.ceil(sorted.length * percentile) - 1]; }
    private static void emit(String key, Object value) { System.out.println(key + "\t" + value); }
    private static String fingerprint(List<Issue> issues) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (Issue issue : issues) {
            for (String field : List.of(issue.key(), issue.summary(), issue.description())) {
                digest.update(field.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
