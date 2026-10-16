package com.jenishsoni.jiratriage;

import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.domain.IssuePriority;
import com.jenishsoni.jiratriage.domain.IssueStatus;
import com.jenishsoni.jiratriage.repository.FixtureJiraGateway;
import com.jenishsoni.jiratriage.repository.JiraGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiraTriageApplicationSmokeTest {

    @Test
    void applicationStartsWithLocalFixtures() {
        try (var context = SpringApplication.run(JiraTriageApplication.class, "--triage.mcp.enabled=false", "--triage.gateway=fixture")) {
            assertTrue(context.isActive());
            assertEquals("none", context.getEnvironment().getProperty("spring.main.web-application-type"));
            assertEquals(1, context.getBeansOfType(JiraGateway.class).size());

            JiraGateway gateway = context.getBean(JiraGateway.class);
            assertInstanceOf(FixtureJiraGateway.class, gateway);
            assertEquals(
                    List.of("DEMO-1", "DEMO-2", "DEMO-3", "DEMO-4", "DEMO-5", "DEMO-6"),
                    gateway.findAll().stream().map(Issue::key).toList());

            Issue issue = gateway.findByKey("DEMO-1").orElseThrow();
            assertEquals("Password reset link expires immediately", issue.summary());
            assertTrue(issue.description().contains("within one minute"));
            assertEquals(IssueStatus.OPEN, issue.status());
            assertEquals(IssuePriority.HIGH, issue.priority());
            assertEquals(List.of("account", "bug"), issue.labels());
            assertTrue(gateway.findByKey("DEMO-999").isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> gateway.findAll().clear());
            assertThrows(UnsupportedOperationException.class, () -> issue.labels().clear());
        }
    }
}
