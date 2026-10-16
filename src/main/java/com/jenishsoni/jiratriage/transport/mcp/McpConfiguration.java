package com.jenishsoni.jiratriage.transport.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "triage.mcp.enabled", havingValue = "true", matchIfMissing = true)
public class McpConfiguration {

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpServer(JiraTools tools) {
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        return McpServer.sync(transport)
                .serverInfo("jira-triage-agent", "0.1.0")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(true)
                .tools(tools.specifications())
                .build();
    }
}
