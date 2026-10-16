package com.jenishsoni.jiratriage.integration.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenishsoni.jiratriage.duplicate.DuplicateSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "triage.gateway", havingValue = "jira")
public class JiraRestConfiguration {

    @Bean(destroyMethod = "close")
    JiraCloudGateway jiraCloudGateway(ObjectMapper mapper, DuplicateSettings duplicates, Environment environment) {
        return new JiraCloudGateway(mapper, JiraConnectionSettings.from(environment::getProperty), duplicates);
    }
}
