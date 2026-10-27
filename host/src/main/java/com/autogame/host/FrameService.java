package com.autogame.host;

import com.autogame.client.McpClientFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 当前画面快照：转发 mcpserver 的 {@code GET /frame.jpg}。
 *
 * <p>特意不走 {@link GameService}——它整类是 {@code synchronized} 的，一次单步推进能占着锁
 * 十几秒，而「等太久，看一眼画面」恰恰就发生在这段时间里，排在同一条会话后面等于没有。
 * 这里用一条独立的 HTTP 短连接直接问 mcpserver，与 MCP 会话无关，也不抢宿主机侧的写锁
 * （mcpserver 的抓帧端点是只读的），所以正在跑的那一步不受影响。
 *
 * <p>只有 Streamable HTTP（{@code AUTOGAME_MCP_URL}）模式才有这个端点：stdio 模式下
 * mcpserver 是 host 自己拉起的子进程，没有对外端口可问。
 */
@Service
public class FrameService {

    /** 单帧超时：抓一帧正常也就几百毫秒，超过这个数说明宿主机那边不对劲，别让客户端干等。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** 抓一帧 JPEG；不可用时抛 IOException，消息直接给客户端看。 */
    public byte[] jpeg() throws IOException, InterruptedException {
        String mcpUrl = McpClientFactory.configuredUrl();
        if (mcpUrl == null) {
            throw new IOException("stdio 模式没有抓帧端点：host 与 mcpserver 同机时用 capture 工具，"
                    + "远程部署请让 host 走 Streamable HTTP（AUTOGAME_MCP_URL）");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(frameUrl(mcpUrl)))
                .timeout(TIMEOUT)
                .header("Accept", "image/jpeg")
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + oneLine(response.body()));
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new IOException("抓帧返回空内容");
        }
        return body;
    }

    /**
     * 由 MCP 端点推导抓帧地址：{@code http://host:18090/mcp} -> {@code http://host:18090/frame.jpg}。
     *
     * <p>只砍最后一段路径，不碰端口和 scheme；没写路径的端点原样补上。
     */
    static String frameUrl(String mcpUrl) {
        String url = mcpUrl.trim();
        int slash = url.lastIndexOf('/');
        // slash > scheme 的长度（"http://" 是 7，加至少 1 个字符的主机名）才算路径分隔符
        String base = slash > 8 ? url.substring(0, slash) : url;
        return (base.endsWith("/") ? base : base + "/") + "frame.jpg";
    }

    private static String oneLine(byte[] body) {
        if (body == null || body.length == 0) {
            return "(空)";
        }
        String text = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
