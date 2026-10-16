package com.jenishsoni.jiratriage.duplicate;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class NormalizedText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private NormalizedText() {}

    public static String normalize(String text) {
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }
}
