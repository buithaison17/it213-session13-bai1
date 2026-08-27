package com.example.bai1.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
public class McpClientConfig {

    @Value("${mcp.servers.postgres.connection-url}")
    private String postgresDbUrl;

    @Value("${mcp.servers.filesystem.allowed-dir}")
    private String fileSystemDir;

    @Bean(destroyMethod = "close")
    public McpSyncClient postgresMcpClient() {
        log.info("Starting Postgres MCP Client...");

        ServerParameters params = ServerParameters.builder("cmd.exe")
                .args(List.of("/c", "npx", "-y", "@modelcontextprotocol/server-postgres", postgresDbUrl))
                .env(Map.of("NO_COLOR", "true"))
                .build();

        // Truyen JsonMapper vao JacksonMcpJsonMapper
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);

        // Truyen 2 tham so (name, version) cho Implementation record
        Implementation clientInfo = new Implementation("RikkeiPostgresClient", "1.0.0");

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .clientInfo(clientInfo)
                .capabilities(ClientCapabilities.builder().build())
                .build();

        client.initialize();
        log.info("Postgres MCP Client initialized successfully!");
        return client;
    }

    @Bean(destroyMethod = "close")
    public McpSyncClient filesystemMcpClient() {
        log.info("Starting FileSystem MCP Client...");

        // Chuan hoa duong dan Windows Path sang Absolute Path
        String normalizedPath = Paths.get(fileSystemDir).toFile().getAbsolutePath();
        File dir = new File(normalizedPath);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                log.warn("Directory could not be created or already exists: {}", normalizedPath);
            }
        }

        ServerParameters params = ServerParameters.builder("cmd.exe")
                .args(List.of("/c", "npx", "-y", "@modelcontextprotocol/server-filesystem", normalizedPath))
                .env(Map.of("NO_COLOR", "true"))
                .build();

        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);

        Implementation clientInfo = new Implementation("RikkeiFileSystemClient", "1.0.0");

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .clientInfo(clientInfo)
                .capabilities(ClientCapabilities.builder().build())
                .build();

        client.initialize();
        log.info("FileSystem MCP Client initialized successfully!");
        return client;
    }
}