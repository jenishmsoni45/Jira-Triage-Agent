package com.jenishsoni.jiratriage.support;

import com.jenishsoni.jiratriage.duplicate.DuplicateSettings;
import java.io.IOException;
import java.util.Properties;

public final class TestDuplicateSettings {
    private TestDuplicateSettings() {}

    public static DuplicateSettings defaults() {
        Properties properties = new Properties();
        try (var input = TestDuplicateSettings.class.getResourceAsStream("/application.properties")) {
            if (input == null) { throw new IllegalStateException("Missing application.properties"); }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read test configuration", exception);
        }
        String prefix = "triage.duplicates.";
        return new DuplicateSettings(
                Integer.parseInt(properties.getProperty(prefix + "shingle-size")),
                Integer.parseInt(properties.getProperty(prefix + "signature-size")),
                Integer.parseInt(properties.getProperty(prefix + "bands")),
                Long.parseLong(properties.getProperty(prefix + "seed")),
                Double.parseDouble(properties.getProperty(prefix + "minimum-similarity")));
    }
}
