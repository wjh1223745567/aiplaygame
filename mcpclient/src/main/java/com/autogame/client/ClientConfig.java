package com.autogame.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 客户端本地配置：服务地址（IP + 端口）、会话 id、截图开关。
 *
 * <p>存在 client.properties：优先 jar 同目录（便携包解压即用，双击 bat 就能带着上次填的 IP 打开），
 * 目录不可写时退回 {@code ~/.autogame/client.properties}。
 */
public final class ClientConfig {

    /** 当前 host 所在机器的局域网地址；界面上随时可改。 */
    public static final String DEFAULT_HOST = "192.168.0.31";
    public static final int DEFAULT_PORT = 18080;

    private static final String FILE = "client.properties";

    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private String session = "win";
    /** null 表示不指定，交给 host 的 llm.chat_vision 默认值。 */
    private Boolean vision = null;

    private Path path;

    public static ClientConfig load() {
        ClientConfig config = new ClientConfig();
        config.path = resolvePath();
        if (!Files.isRegularFile(config.path)) {
            return config;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(config.path)) {
            props.load(in);
        } catch (IOException e) {
            // 配置坏了不该挡住启动，用默认值继续
            System.err.println("[config] 读取失败，使用默认值：" + e.getMessage());
            return config;
        }
        String h = props.getProperty("host", "").trim();
        if (!h.isEmpty()) {
            config.host = h;
        }
        config.port = parsePort(props.getProperty("port"), config.port);
        String s = props.getProperty("session", "").trim();
        if (!s.isEmpty()) {
            config.session = s;
        }
        String v = props.getProperty("vision", "").trim();
        if (!v.isEmpty()) {
            config.vision = Boolean.valueOf(v);
        }
        return config;
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("host", host);
        props.setProperty("port", String.valueOf(port));
        props.setProperty("session", session);
        if (vision != null) {
            props.setProperty("vision", String.valueOf(vision));
        }
        try {
            Path dir = path.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "autogame 客户端配置：host/port 指向 host 的 IP 与端口");
            }
        } catch (IOException e) {
            System.err.println("[config] 保存失败（" + path + "）：" + e.getMessage());
        }
    }

    /** http://host:port */
    public String baseUrl() {
        return "http://" + host + ":" + port;
    }

    public String host() {
        return host;
    }

    public void host(String value) {
        String trimmed = value == null ? "" : value.trim();
        // 允许直接粘 http://ip:port，顺手拆开，省得用户在三个框里来回改
        if (trimmed.contains("://")) {
            String rest = trimmed.substring(trimmed.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                rest = rest.substring(0, slash);
            }
            int colon = rest.lastIndexOf(':');
            if (colon > 0) {
                port = parsePort(rest.substring(colon + 1), port);
                rest = rest.substring(0, colon);
            }
            trimmed = rest;
        }
        if (!trimmed.isEmpty()) {
            host = trimmed;
        }
    }

    public int port() {
        return port;
    }

    public void port(int value) {
        if (value > 0 && value < 65536) {
            port = value;
        }
    }

    public String session() {
        return session;
    }

    public void session(String value) {
        if (value != null && !value.isBlank()) {
            session = value.trim();
        }
    }

    public Boolean vision() {
        return vision;
    }

    public void vision(Boolean value) {
        vision = value;
    }

    public Path path() {
        return path;
    }

    // ---------- 内部 ----------

    private static int parsePort(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 && value < 65536 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** jar 同目录可写就用它，否则 ~/.autogame/。 */
    private static Path resolvePath() {
        Path dir = jarDir();
        if (dir != null && Files.isDirectory(dir) && Files.isWritable(dir)) {
            return dir.resolve(FILE);
        }
        return Path.of(System.getProperty("user.home"), ".autogame", FILE);
    }

    private static Path jarDir() {
        try {
            var source = ClientConfig.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path resolved = Path.of(source.getLocation().toURI()).toAbsolutePath();
            return Files.isDirectory(resolved) ? resolved : resolved.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return baseUrl() + " 会话=" + session + " 截图=" + (vision == null ? "跟随 host" : vision)
                + " 配置=" + path;
    }
}
