package com.jenishsoni.jiratriage.repository;

import com.jenishsoni.jiratriage.support.TestDuplicateSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.domain.IssuePatch;
import com.jenishsoni.jiratriage.domain.IssuePriority;
import com.jenishsoni.jiratriage.duplicate.LshDuplicateIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureDuplicateIndexTest {

    @Test
    void indexTracksGatewayEditsButNotPreviewsFailuresOrMetadataChanges() {
        var gateway = gateway();
        Issue original = gateway.findByKey("DEMO-1").orElseThrow();
        Issue unrelated = gateway.findByKey("DEMO-5").orElseThrow();
        var initial = gateway.findDuplicates("DEMO-1");
        var copy = new IssuePatch(original.summary(), original.description(), null, null, null);
        gateway.update("DEMO-6", copy, true);
        assertEquals(initial, gateway.findDuplicates("DEMO-1"));
        gateway.update("DEMO-6", copy, false);
        assertTrue(gateway.findDuplicates("DEMO-1").candidateKeys().contains("DEMO-6"));
        var indexed = gateway.findDuplicates("DEMO-1");
        gateway.update("DEMO-6", new IssuePatch(null, null, null, IssuePriority.HIGH, null), false);
        assertEquals(indexed, gateway.findDuplicates("DEMO-1"));
        assertThrows(IllegalArgumentException.class,
                () -> gateway.update("DEMO-6", new IssuePatch(" ", null, null, null, null), false));
        assertEquals(indexed, gateway.findDuplicates("DEMO-1"));

        var replace = new IssuePatch(unrelated.summary(), unrelated.description(), null, null, null);
        gateway.update("DEMO-6", replace, true);
        assertEquals(indexed, gateway.findDuplicates("DEMO-1"));
        gateway.update("DEMO-6", replace, false);
        assertFalse(gateway.findDuplicates("DEMO-1").candidateKeys().contains("DEMO-6"));
        assertTrue(gateway.findDuplicates("DEMO-5").candidateKeys().contains("DEMO-6"));
        assertEquals(initial, gateway().findDuplicates("DEMO-1"));
    }

    private static FixtureJiraGateway gateway() {
        return new FixtureJiraGateway(new ObjectMapper(), "fixtures/issues.json",
                new LshDuplicateIndex(TestDuplicateSettings.defaults()));
    }
}
