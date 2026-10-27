package com.autogame.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * host 的 HTTP 门面：{@code /ai/tools}、{@code /ai/invoke}、{@code /ai/frame}。
 *
 * <p>只负责「拼 URL + 收发 JSON」，不做任何决策；控制台与图形客户端共用。
 * 客户端可以跑在别的机器上（Windows/局域网），所以地址是构造参数而不是常量。
 */
public class HostClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 一轮完整 OCR 可能要几秒，本地大图检测更久，给足时间。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);
    /** 抓帧要快：慢就说明不在能截图的状态，宁可早点失败也别阻塞界面。 */
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(15);

    private final String base;
    private final HttpClient http;

    public HostClient(String base) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("host 地址不能为空");
        }
        String trimmed = base.trim();
        this.base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 形如 http://192.168.0.31:18080。 */
    public String base() {
        return base;
    }

    public JsonNode tools() throws IOException, InterruptedException {
        return request("GET", "/ai/tools", null);
    }

    /** 执行一次工具调用；body 就是该工具的参数 JSON（无参传空 Map）。 */
    public JsonNode invoke(String tool, Map<String, Object> args) throws IOException, InterruptedException {
        return request("POST", "/ai/invoke?tool=" + encode(tool), args == null ? Map.of() : args);
    }

    /**
     * 当前画面快照（JPEG 字节）。
     *
     * <p>带一个时间戳参数纯粹为了绕过任何中间缓存；host 与 mcpserver 两端也都声明了
     * no-store。失败时 host 会返 503 + 一句中文原因，这里原样抛给界面显示。
     */
    public byte[] frame() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(base + "/ai/frame?t=" + System.currentTimeMillis()))
                .timeout(FRAME_TIMEOUT)
                .header("Accept", "image/jpeg")
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 300) {
            byte[] body = response.body();
            String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);
            throw new IOException("HTTP " + response.statusCode() + ": " + oneLine(text, 300));
        }
        return response.body();
    }

    public JsonNode request(String method, String path, Map<String, Object> body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String text = response.body();
        if (response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + oneLine(String.valueOf(text), 300));
        }
        if (text == null || text.isBlank()) {
            return JSON.createObjectNode();
        }
        return JSON.readTree(text);
    }

    // ---------- 小工具 ----------

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 只保留最内层异常信息，避免把堆栈糊到界面上。 */
    public static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    public static String oneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace("\r", "").replace("\n", " ");
        if (flat.length() <= max) {
            return flat;
        }
        return flat.substring(0, max) + "…(截断)";
    }
}
