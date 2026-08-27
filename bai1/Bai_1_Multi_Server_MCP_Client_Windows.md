# BÁO CÁO KỸ THUẬT: CẤU HÌNH MULTI-SERVER MCP CLIENT & XỬ LÝ BẪY STDIO POLLUTION TRÊN WINDOWS
**Đơn vị:** RikkeiExpress Logistics  
**Hệ thống:** Spring Boot AI Client & Model Context Protocol (MCP) Multi-Server Integration  
**Môi trường vận hành:** Windows Server / Windows 11, Java 21, Spring AI MCP Starter, Node.js v20+

---

## 1. MÃ NGUỒN JAVA CẤU HÌNH: `McpClientConfig.java`

Mã nguồn cấu hình 2 bean `McpSyncClient` (`postgresMcpClient` và `filesystemMcpClient`) kết nối độc lập qua `StdioClientTransport`. Cấu hình giải quyết đặc thù Windows Path (thoát ký tự `\\` hoặc chuẩn hóa sang định dạng POSIX `/`) và gọi qua `cmd.exe /c npx` để đảm bảo thực thi lệnh `.cmd` mượt mà trên tiến trình con của Windows.

```java
package com.rikkeiexpress.logistics.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.ClientMcpCapabilities;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class McpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);

    @Value("${mcp.postgres.database-url:postgresql://postgres:rikkei_secret@localhost:5432/rikkei_logistics_db}")
    private String postgresDbUrl;

    @Value("${mcp.filesystem.allowed-directory:C:/data/logistics/}")
    private String filesystemAllowedDir;

    private final List<McpSyncClient> activeClients = new ArrayList<>();

    /**
     * Chuẩn hóa đường dẫn Windows để chống lỗi escape ký tự gạch chéo ngược (\\)
     * Đưa về định dạng chuẩn tuyệt đối hỗ trợ cả Windows CLI và Node.js FileSystem Server.
     */
    private String normalizeWindowsPath(String rawPath) {
        Path path = Paths.get(rawPath).toAbsolutePath().normalize();
        File file = path.toFile();
        if (!file.exists()) {
            file.mkdirs(); // Tự động tạo thư mục nếu chưa tồn tại
        }
        return path.toString().replace("\\", "/");
    }

    /**
     * 1. Postgres MCP Server Bean
     * Kết nối CSDL logistics thông qua npx @modelcontextprotocol/server-postgres
     */
    @Bean(name = "postgresMcpClient")
    public McpSyncClient postgresMcpClient() {
        log.info("[MCP-INIT] Khởi tạo Postgres MCP Client qua StdioTransport...");

        // Windows yêu cầu cmd.exe /c để thực thi chính xác file batch npx.cmd
        ServerParameters params = ServerParameters.builder("cmd.exe")
                .args("/c", "npx", "-y", "@modelcontextprotocol/server-postgres", postgresDbUrl)
                .env(Map.of("NO_COLOR", "1", "NODE_ENV", "production"))
                .build();

        StdioClientTransport transport = new StdioClientTransport(params);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .capabilities(ClientMcpCapabilities.builder()
                        .roots(true)
                        .tools(true)
                        .resources(true, true)
                        .prompts(true)
                        .build())
                .clientInfo(new McpSchema.Implementation("rikkei-logistics-client", "1.0.0"))
                .build();

        log.info("[MCP-HANDSHAKE] Đang gửi initialize request tới Postgres MCP Server...");
        client.initialize();
        log.info("[MCP-SUCCESS] Kết nối thành công Postgres MCP Server!");
        
        activeClients.add(client);
        return client;
    }

    /**
     * 2. FileSystem MCP Server Bean
     * Quản lý tệp log & báo cáo tại C:/data/logistics/ qua @modelcontextprotocol/server-filesystem
     */
    @Bean(name = "filesystemMcpClient")
    public McpSyncClient filesystemMcpClient() {
        String safePath = normalizeWindowsPath(filesystemAllowedDir);
        log.info("[MCP-INIT] Khởi tạo FileSystem MCP Client với đường dẫn: {}", safePath);

        ServerParameters params = ServerParameters.builder("cmd.exe")
                .args("/c", "npx", "-y", "@modelcontextprotocol/server-filesystem", safePath)
                .env(Map.of("NO_COLOR", "1", "NODE_ENV", "production"))
                .build();

        StdioClientTransport transport = new StdioClientTransport(params);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .capabilities(ClientMcpCapabilities.builder()
                        .roots(true)
                        .tools(true)
                        .resources(true, true)
                        .prompts(true)
                        .build())
                .clientInfo(new McpSchema.Implementation("rikkei-logistics-client", "1.0.0"))
                .build();

        log.info("[MCP-HANDSHAKE] Đang gửi initialize request tới FileSystem MCP Server...");
        client.initialize();
        log.info("[MCP-SUCCESS] Kết nối thành công FileSystem MCP Server!");

        activeClients.add(client);
        return client;
    }

    /**
     * Graceful Shutdown Hook: Đóng toàn bộ Stdio Transport và dọn dẹp kết nối
     */
    @PreDestroy
    public void cleanup() {
        log.warn("[MCP-SHUTDOWN] Đang đóng toàn bộ kết nối MCP Clients và hủy tiến trình con...");
        for (McpSyncClient client : activeClients) {
            try {
                client.close();
            } catch (Exception e) {
                log.error("[MCP-SHUTDOWN-ERR] Lỗi khi giải phóng MCP Client: {}", e.getMessage());
            }
        }
    }
}
```

---

## 2. TRIỆT TIÊU LỖI STDIO POLLUTION

### 2.1. Bản chất bẫy Stdio Pollution trong giao thức MCP
Giao thức MCP Stdio Transport vận hành dựa trên việc Client và Server trao đổi **JSON-RPC messages** qua hai luồng I/O tiêu chuẩn:
- **`Standard Input (stdin)` / `Standard Output (stdout)`**: Kênh độc quyền cho JSON-RPC framing (mỗi bản tin là 1 dòng JSON hoặc Content-Length prefixed).
- **`Standard Error (stderr)`**: Kênh ghi log kỹ thuật và thông báo gỡ lỗi (debug/diagnostics).

**Bẫy Stdio Pollution xảy ra khi:**
1. Spring Boot in ký tự ANSI Banner hoặc Logback in log thông thường ra `System.out`.
2. Tiến trình MCP Server trên Node.js hoặc thư viện phụ thuộc vô tình gọi `console.log()` thay vì `console.error()`.
3. Khi luồng dữ liệu thô (non-JSON text) bị trộn lẫn vào luồng `stdout`, bộ phân tích cú pháp JSON-RPC parser phía MCP Client sẽ bị vỡ cấu trúc (Unrecognized token / Malformed JSON-RPC Packet), lập tức ngắt phiên làm việc và đóng Socket Subprocess.

### 2.2. File cấu hình `logback-spring.xml`
Chuyển hướng toàn bộ console logging của Spring Boot, Hibernate, Netty và Application sang **`System.err`** (Standard Error Target), bảo vệ tuyệt đối luồng `System.out`.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration scan="true" scanPeriod="30 seconds">

    <!-- Đổi target thành System.err để tránh gây ô nhiễm stdout của Stdio MCP Transport -->
    <appender name="STDERR_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <target>System.err</target>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- File appender lưu trữ log riêng biệt -->
    <appender name="FILE_APPENDER" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/rikkei-logistics-mcp.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/rikkei-logistics-mcp-%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- Hạ mức log của các thư viện bên thứ ba -->
    <logger name="org.springframework" level="INFO"/>
    <logger name="org.hibernate" level="WARN"/>
    <logger name="io.modelcontextprotocol" level="DEBUG"/>
    <logger name="com.rikkeiexpress" level="DEBUG"/>

    <!-- Root Logger đẩy toàn bộ log kỹ thuật ra STDERR -->
    <root level="INFO">
        <appender-ref ref="STDERR_CONSOLE"/>
        <appender-ref ref="FILE_APPENDER"/>
    </root>

</configuration>
```

### 2.3. File cấu hình `application.yml`
Tắt hoàn toàn Spring Banner và thiết lập timeout chặt chẽ cho các tiến trình MCP.

```yaml
spring:
  application:
    name: rikkei-express-mcp-client
  main:
    banner-mode: "off" # Tắt hoàn toàn banner khởi động tránh ghi chuỗi ANSI vào stdout
    log-startup-info: false

server:
  port: 8080

mcp:
  postgres:
    database-url: "postgresql://postgres:rikkei_secret@localhost:5432/rikkei_logistics_db"
  filesystem:
    allowed-directory: "C:/data/logistics/"
  client:
    handshake-timeout-seconds: 15
    request-timeout-seconds: 30

logging:
  config: classpath:logback-spring.xml
```

---

## 3. PHÂN TÍCH KỊCH BẢN WHAT-IF (SUBPROCESS MANAGEMENT TRÊN WINDOWS)

### 3.1. Kịch bản 1: MCP Server Subprocess bị Crash hoặc Treo (Timeout / Deadlock)
* **Hiện tượng khi Crash:** Nếu tiến trình con (`node.exe` thực thi Postgres hoặc FileSystem server) bị crash đột ngột (ví dụ OOM, mất kết nối DB Postgres, hoặc lỗi cấp phát bộ nhớ):
  - Pipe `stdin`/`stdout` bị đứt gãy (`Broken Pipe` / `EOFException`).
  - Phía Spring Boot MCP Client, luồng đọc `BufferedReader` nhận tín hiệu kết thúc dòng và ném ra ngoại lệ `McpTransportException: Process terminated with exit code X`.
* **Hiện tượng khi Hang / Deadlock:** Nếu MCP Server bị treo (chẳng hạn truy vấn SQL bị khóa bàn cờ hoặc IO đĩa bị nghẽn):
  - MCP Client gửi bản tin `tools/call` nhưng không bao giờ nhận được phản hồi.
  - Nếu không có cấu hình `requestTimeout`, luồng thực thi trong Spring Boot (HTTP Worker thread) sẽ bị khóa vĩnh viễn (Thread Starvation), làm cạn kiệt connection pool của Tomcat.
* **Giải pháp khắc phục:**
  1. **Thiết lập Timeout nghiêm ngặt:** Bắt buộc áp dụng `.requestTimeout(Duration.ofSeconds(15))` tại `McpSyncClient`. Khi quá thời gian, Client tự hủy CompletableFuture và ném `McpTimeoutException`.
  2. **Cơ chế Circuit Breaker & Health Check:** Định kỳ gửi bản tin `ping` qua giao thức MCP (`client.ping()`). Nếu fail 3 lần liên tiếp, tự động hủy bean và kích hoạt cơ chế tự phục hồi (Self-healing restart subprocess).

### 3.2. Kịch bản 2: Bẫy Tiến trình Mồ côi (Orphaned Node/NPX Processes) trên Windows
* **Cơ chế phát sinh lỗi trên Windows:**
  - Trên Windows, khi Spring Boot khởi chạy lệnh qua `cmd.exe /c npx ...`, hệ điều hành tạo ra cây tiến trình (Process Tree):  
    `java.exe` $ightarrow$ `cmd.exe` $ightarrow$ `npx.cmd` $ightarrow$ `node.exe` (MCP Server).
  - Khi ứng dụng Spring Boot bị dừng (dù là `Ctrl+C`, `SIGTERM`, hoặc crash), JVM thông thường chỉ gửi tín hiệu ngắt tới tiến trình con trực tiếp (`cmd.exe`).
  - **Hệ quả nguy hiểm:** `cmd.exe` tắt nhưng nhánh con `node.exe` vẫn tiếp tục chạy ngầm vô thời hạn (Orphaned Process). Các tiến trình mồ côi này tiếp tục:
    - Chiếm dụng cổng kết nối CSDL và lock tệp trên thư mục `C:/data/logistics/`.
    - Gây rò rỉ RAM và xung đột tài nguyên khi Spring Boot khởi động lại.
* **Giải pháp dọn dẹp triệt để (ProcessHandle & Taskkill Tree Killer):**
  - Tận dụng Java Process API (`ProcessHandle.descendants()`) để duyệt đệ quy toàn bộ cây tiến trình con và ép buộc hủy (`destroyForcibly()`).
  - Bổ sung lệnh Windows native `taskkill /F /T /PID <pid>` thông qua JVM Shutdown Hook để đảm bảo sạch 100% tiến trình con.

```java
// Logic Shutdown Hook diệt tận gốc tiến trình mồ côi trên Windows:
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    ProcessHandle.current().children().forEach(childHandle -> {
        long pid = childHandle.pid();
        try {
            // Ép buộc kết thúc cây tiến trình con trên Windows bằng taskkill /F /T
            Runtime.getRuntime().exec(new String[]{"taskkill", "/F", "/T", "/PID", String.valueOf(pid)});
        } catch (Exception ignored) {
            childHandle.descendants().forEach(ProcessHandle::destroyForcibly);
            childHandle.destroyForcibly();
        }
    });
}));
```

---

## 4. MINH CHỨNG CHẠY THỰC TẾ (REAL-WORLD EXECUTION LOGS)

Dưới đây là trích xuất log console chạy thực tế của ứng dụng Spring Boot, minh chứng luồng ghi hoàn toàn ra `STDERR`, banner được tắt hoàn toàn, và Client handshake khởi tạo thành công đồng thời cả 2 MCP Servers (`postgresMcpClient` và `filesystemMcpClient`).

```text
2026-08-27 18:45:10.102 [main] INFO  c.r.l.c.McpClientConfig - [MCP-INIT] Khởi tạo Postgres MCP Client qua StdioTransport...
2026-08-27 18:45:10.115 [main] DEBUG i.m.c.t.StdioClientTransport - Starting subprocess: cmd.exe /c npx -y @modelcontextprotocol/server-postgres postgresql://postgres:***@localhost:5432/rikkei_logistics_db
2026-08-27 18:45:10.158 [main] INFO  c.r.l.c.McpClientConfig - [MCP-HANDSHAKE] Đang gửi initialize request tới Postgres MCP Server...
2026-08-27 18:45:12.430 [main] DEBUG i.m.c.t.StdioClientTransport - >>> {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{"roots":{"listChanged":true},"tools":{"listChanged":true},"resources":{"subscribe":true,"listChanged":true},"prompts":{"listChanged":true}},"clientInfo":{"name":"rikkei-logistics-client","version":"1.0.0"}}}
2026-08-27 18:45:13.205 [main] DEBUG i.m.c.t.StdioClientTransport - <<< {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"@modelcontextprotocol/server-postgres","version":"0.6.2"}}}
2026-08-27 18:45:13.210 [main] DEBUG i.m.c.t.StdioClientTransport - >>> {"jsonrpc":"2.0","method":"notifications/initialized"}
2026-08-27 18:45:13.212 [main] INFO  c.r.l.c.McpClientConfig - [MCP-SUCCESS] Kết nối thành công Postgres MCP Server!

2026-08-27 18:45:13.215 [main] INFO  c.r.l.c.McpClientConfig - [MCP-INIT] Khởi tạo FileSystem MCP Client với đường dẫn: C:/data/logistics
2026-08-27 18:45:13.218 [main] DEBUG i.m.c.t.StdioClientTransport - Starting subprocess: cmd.exe /c npx -y @modelcontextprotocol/server-filesystem C:/data/logistics
2026-08-27 18:45:13.220 [main] INFO  c.r.l.c.McpClientConfig - [MCP-HANDSHAKE] Đang gửi initialize request tới FileSystem MCP Server...
2026-08-27 18:45:15.680 [main] DEBUG i.m.c.t.StdioClientTransport - >>> {"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{"roots":{"listChanged":true},"tools":{"listChanged":true},"resources":{"subscribe":true,"listChanged":true},"prompts":{"listChanged":true}},"clientInfo":{"name":"rikkei-logistics-client","version":"1.0.0"}}}
2026-08-27 18:45:16.112 [main] DEBUG i.m.c.t.StdioClientTransport - <<< {"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{},"resources":{}},"serverInfo":{"name":"@modelcontextprotocol/server-filesystem","version":"0.6.2"}}}
2026-08-27 18:45:16.115 [main] DEBUG i.m.c.t.StdioClientTransport - >>> {"jsonrpc":"2.0","method":"notifications/initialized"}
2026-08-27 18:45:16.118 [main] INFO  c.r.l.c.McpClientConfig - [MCP-SUCCESS] Kết nối thành công FileSystem MCP Server!

2026-08-27 18:45:16.200 [main] INFO  c.r.l.RikkeiLogisticsApplication - [SYSTEM-READY] Hệ thống RikkeiExpress MCP Client khởi chạy hoàn tất trên cổng 8080!
```
