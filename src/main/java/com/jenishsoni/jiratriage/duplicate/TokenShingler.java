package com.jenishsoni.jiratriage.duplicate;

import com.jenishsoni.jiratriage.domain.Issue;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class TokenShingler {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*");
    private final int size;

    public TokenShingler(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Shingle size must be positive");
        }
        this.size = size;
    }

    public Set<String> shingles(Issue issue) {
        Set<String> shingles = new HashSet<>();
        addField(issue.summary(), shingles);
        addField(issue.description(), shingles);
        return Set.copyOf(shingles);
    }

    private void addField(String text, Set<String> shingles) {
        List<String> tokens = TOKEN.matcher(NormalizedText.normalize(text)).results()
                .map(match -> match.group()).toList();
        if (tokens.isEmpty()) {
            return;
        }

        int width = Math.min(size, tokens.size());
        for (int start = 0; start <= tokens.size() - width; start++) {
            shingles.add(String.join(" ", tokens.subList(start, start + width)));
        }
    }
}
