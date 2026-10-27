package com.autogame.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Windows 图形客户端：填 host 的 IP/端口 -> 连接 -> 选工具、填参数、看结果。
 *
 * <p>和 {@link ChatConsole} 一样只做「发一次工具调用、看返回的 JSON」，识别在 mcpserver，
 * 转发在 host。地址会写进 client.properties，下次打开自动带出。
 *
 * <p>右侧是当前画面快照（host 的 {@code /ai/frame}）：不按秒轮询，只在你点「看当前画面」
 * 或刚连上时抓一帧。它走独立 HTTP，不占用工具会话，所以正在跑 OCR/检测时也能看。
 *
 * <pre>
 * java -jar client.jar                        # 读 client.properties，默认 192.168.0.31:18080
 * java -jar client.jar --host=192.168.0.31 --port=18080
 * java -jar client.jar --selftest             # 不开窗口，只验证能不能连上
 * </pre>
 */
public final class ChatWindow extends JFrame {

    private static final Color INK = new Color(0x1F2328);
    private static final Color REQUEST = new Color(0x1F4E79);
    private static final Color RESULT = new Color(0x146C43);
    private static final Color ERROR = new Color(0xB42318);
    private static final Color MUTED = new Color(0x6E7781);
    private static final Color VIDEO_BG = new Color(0x0D1117);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClientConfig config;
    private final JTextField hostField;
    private final JTextField portField;
    private final JLabel statusLabel = new JLabel("未连接");
    private final JTextPane logPane = new JTextPane();
    private final JComboBox<String> toolBox = new JComboBox<>();
    private final JTextField argsField = new JTextField(30);
    private final JLabel toolHint = new JLabel(" ");
    private final JButton callBtn = new JButton("调用 (Enter)");
    private final JButton connectBtn = new JButton("连 接");
    private final JLabel frameLabel = new JLabel("连上 host 后可见当前画面", SwingConstants.CENTER);
    private final JLabel frameHint = new JLabel("点「看当前画面」抓一帧");
    private final JButton frameBtn = new JButton("看当前画面");

    private HostClient client;
    /** 最近一次 /ai/tools 的结果：切换工具时用它显示必填参数与描述。 */
    private JsonNode tools;
    private boolean busy;
    /** 上一个抓帧请求还没回来（抓帧不能叠着发，否则宿主机排队越堆越长）。 */
    private boolean frameFetching;
    /** 最近一帧原图：窗口缩放时用它重缩放，不重新请求。 */
    private BufferedImage frameImage;
    /** 左右分栏，窗口出来后把分隔条摆到中间靠右。 */
    private JSplitPane mainSplit;

    public ChatWindow(ClientConfig config) {
        super("Autogame 识别控制台");
        this.config = config;
        this.hostField = new JTextField(config.host(), 14);
        this.portField = new JTextField(String.valueOf(config.port()), 6);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 520));
        setSize(1000, 700);
        setLocationRelativeTo(null);
        setContentPane(buildContent());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                applyFields();
                config.save();
                dispose();
                System.exit(0);
            }
        });
    }

    // ---------- 界面 ----------

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(10, 12, 10, 12));
        root.add(buildTopBar(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildInputBar(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(new JLabel("服务 IP"));
        left.add(hostField);
        left.add(new JLabel("端口"));
        left.add(portField);
        left.add(connectBtn);
        bar.add(left, BorderLayout.WEST);
        bar.add(statusLabel, BorderLayout.EAST);

        connectBtn.addActionListener(e -> connect());
        hostField.addActionListener(e -> connect());
        portField.addActionListener(e -> connect());
        return bar;
    }

    private JSplitPane buildCenter() {
        logPane.setEditable(false);
        logPane.setFont(monoFont(12));
        logPane.setBackground(Color.WHITE);
        logPane.setForeground(INK);
        JScrollPane logScroll = new JScrollPane(logPane);
        logScroll.setBorder(new EmptyBorder(0, 0, 0, 0));

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, logScroll, buildFramePanel());
        mainSplit.setResizeWeight(0.72);
        mainSplit.setDividerSize(6);
        return mainSplit;
    }

    /** 右侧画面面板：一眼看到游戏现在停在哪。只给人看，不参与任何识别。 */
    private JPanel buildFramePanel() {
        frameLabel.setOpaque(true);
        frameLabel.setBackground(VIDEO_BG);
        frameLabel.setForeground(MUTED);
        // 面板尺寸变化时用原图重缩放，而不是把拉伸后的那张再拉一次
        frameLabel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                renderFrame();
            }
        });

        JPanel head = new JPanel(new BorderLayout());
        head.add(new JLabel("当前画面"), BorderLayout.WEST);

        // 时间/分辨率单独占一行：和中英文混排的按钮挤在一行时会被截成「…」
        frameHint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        frameHint.setForeground(MUTED);
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttonRow.add(frameBtn);
        frameBtn.addActionListener(e -> refreshFrame(false));

        JPanel foot = new JPanel(new BorderLayout(0, 4));
        foot.add(frameHint, BorderLayout.NORTH);
        foot.add(buttonRow, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(head, BorderLayout.NORTH);
        panel.add(frameLabel, BorderLayout.CENTER);
        panel.add(foot, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(360, 0));
        panel.setMinimumSize(new Dimension(240, 0));
        return panel;
    }

    private JPanel buildInputBar() {
        toolBox.setEditable(true);
        toolBox.setPreferredSize(new Dimension(170, 26));
        toolBox.addActionListener(e -> updateHint());

        argsField.setFont(monoFont(13));
        argsField.setToolTipText("工具参数（JSON 对象，可留空）");
        // Enter 直接调用；参数里要换行时用 :args 不方便，所以参数框只收单行
        argsField.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "call");
        argsField.getActionMap().put("call", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                call();
            }
        });

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel("工具"));
        row.add(toolBox);
        row.add(new JLabel("参数"));
        row.add(argsField);
        row.add(callBtn);
        row.add(button("工具列表", this::showTools));

        toolHint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        toolHint.setForeground(MUTED);

        JPanel bar = new JPanel(new BorderLayout(0, 4));
        bar.add(row, BorderLayout.NORTH);
        bar.add(toolHint, BorderLayout.SOUTH);
        callBtn.addActionListener(e -> call());

        append("先确认 host 已启动（macOS 侧 18080），再点「连接」。", MUTED, false);
        append("连上后选工具、填 JSON 参数（可留空），回车即调用。\n\n", MUTED, false);
        return bar;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> action.run());
        return button;
    }

    // ---------- 动作 ----------

    /** 窗口可见后自动连一次，省得用户多点一下。 */
    void autoConnect() {
        connect();
    }

    private void connect() {
        if (busy) {
            return;
        }
        applyFields();
        config.save();
        String base = config.baseUrl();
        setBusy(true);
        setStatus("连接中 " + base + " …", MUTED);
        new SwingWorker<JsonNode, Void>() {
            @Override
            protected JsonNode doInBackground() throws Exception {
                return new HostClient(base).tools();
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    JsonNode out = get();
                    client = new HostClient(base);
                    tools = out;
                    populateTools(out);
                    int count = out.path("count").asInt(0);
                    setStatus("已连接 " + base + " · 工具 " + count + " 个", RESULT);
                    append("[已连接] " + base + "，可用工具 " + count + " 个。\n\n", MUTED, false);
                    refreshFrame(true);
                } catch (Exception e) {
                    client = null;
                    Throwable cause = unwrap(e);
                    setStatus("连接失败：" + HostClient.rootMessage(cause), ERROR);
                    append("[连接失败] " + HostClient.rootMessage(cause)
                            + "\n           检查：host 是否在跑、IP/端口是否正确、macOS 防火墙是否放行 "
                            + config.port() + "。\n\n", ERROR, false);
                }
            }
        }.execute();
    }

    /** 调一次工具：参数原样发给 host，返回的 JSON 原样贴到下方。 */
    private void call() {
        if (busy) {
            return;
        }
        if (client == null) {
            append("[提示] 还没连上 host，先点「连接」。\n\n", ERROR, false);
            return;
        }
        String tool = String.valueOf(toolBox.getEditor().getItem()).trim();
        if (tool.isEmpty()) {
            append("[提示] 先选一个工具（点「工具列表」看有哪些）。\n\n", ERROR, false);
            return;
        }
        Map<String, Object> args;
        try {
            args = parseArgs(argsField.getText());
        } catch (Exception e) {
            append("[参数错误] " + e.getMessage() + "\n           示例：{\"max_n\":20}\n\n", ERROR, false);
            return;
        }
        applyFields();
        config.save();

        append(stamp() + " → " + tool + " " + JSON.valueToTree(args) + "\n", REQUEST, true);
        setBusy(true);
        long started = System.currentTimeMillis();
        new SwingWorker<JsonNode, Void>() {
            @Override
            protected JsonNode doInBackground() throws Exception {
                return client.invoke(tool, args);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    renderResult(get(), started);
                } catch (Exception e) {
                    append("[失败] " + HostClient.rootMessage(unwrap(e)) + "\n\n", ERROR, false);
                }
            }
        }.execute();
    }

    private void renderResult(JsonNode out, long started) {
        long ms = System.currentTimeMillis() - started;
        if (out.path("ok").asBoolean(false)) {
            append(pretty(out.path("result")), INK, false);
            append("    （" + out.path("tool").asText("") + " 成功 · " + ms + "ms）\n\n", RESULT, false);
        } else {
            append("[失败] " + out.path("error").asText("") + "（" + ms + "ms）\n\n", ERROR, false);
        }
    }

    // ---------- 画面 ----------

    /** 抓一帧贴到右侧；silent=true 时失败只更新标题下那行小字，不刷记录。 */
    private void refreshFrame(boolean silent) {
        HostClient target = client;
        if (target == null) {
            if (!silent) {
                append("[提示] 还没连上 host，先点「连接」。\n\n", ERROR, false);
            }
            return;
        }
        if (frameFetching) {
            return;
        }
        frameFetching = true;
        frameHint.setText(stamp() + " 抓取中…");
        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() throws Exception {
                return target.frame();
            }

            @Override
            protected void done() {
                frameFetching = false;
                try {
                    showFrame(get());
                } catch (Exception e) {
                    String message = HostClient.rootMessage(unwrap(e));
                    frameHint.setText(stamp() + " 抓帧失败：" + HostClient.oneLine(message, 30));
                    frameHint.setToolTipText(message);
                    if (!silent) {
                        append("[抓帧失败] " + message + "\n\n", ERROR, false);
                    }
                }
            }
        }.execute();
    }

    private void showFrame(byte[] jpeg) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (image == null) {
            throw new IOException("返回内容不是 JPEG（" + jpeg.length + " 字节）");
        }
        frameImage = image;
        renderFrame();
        String text = stamp() + " " + image.getWidth() + "×" + image.getHeight()
                + " · " + Math.max(1, jpeg.length / 1024) + "KB";
        frameHint.setText(text);
        frameHint.setToolTipText(text);
    }

    /** 按面板当前尺寸等比缩放；每次都从原图重画，避免多轮缩放糊掉。 */
    private void renderFrame() {
        BufferedImage source = frameImage;
        if (source == null) {
            return;
        }
        int maxWidth = Math.max(80, frameLabel.getWidth() - 4);
        int maxHeight = Math.max(80, frameLabel.getHeight() - 4);
        double scale = Math.min(maxWidth / (double) source.getWidth(),
                maxHeight / (double) source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        frameLabel.setText(null);
        frameLabel.setIcon(new ImageIcon(scaled));
    }

    private static String stamp() {
        return LocalTime.now().format(CLOCK);
    }

    // ---------- 工具清单 ----------

    private void populateTools(JsonNode out) {
        String previous = String.valueOf(toolBox.getEditor().getItem()).trim();
        toolBox.removeAllItems();
        for (JsonNode tool : out.path("tools")) {
            toolBox.addItem(tool.path("name").asText());
        }
        if (!previous.isEmpty() && exists(previous)) {
            toolBox.setSelectedItem(previous);
        } else if (toolBox.getItemCount() > 0) {
            toolBox.setSelectedIndex(0);
        }
        updateHint();
    }

    private boolean exists(String name) {
        for (JsonNode tool : tools.path("tools")) {
            if (name.equals(tool.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 提示行跟着当前选中的工具走：必填参数 + 一句描述。 */
    private void updateHint() {
        updateHint(String.valueOf(toolBox.getEditor().getItem()).trim());
    }

    private void updateHint(String name) {
        if (name.isEmpty()) {
            toolHint.setText(" ");
            toolHint.setToolTipText(null);
            return;
        }
        JsonNode spec = null;
        if (tools != null) {
            for (JsonNode tool : tools.path("tools")) {
                if (name.equals(tool.path("name").asText())) {
                    spec = tool;
                    break;
                }
            }
        }
        if (spec == null) {
            toolHint.setText("（清单里没有这个工具，点「工具列表」确认名字）");
            toolHint.setToolTipText(null);
            return;
        }
        JsonNode required = spec.path("required");
        String need = required.isArray() && !required.isEmpty() ? required.toString() : "无";
        String desc = spec.path("description").asText("");
        toolHint.setText("必填参数: " + need + " · " + HostClient.oneLine(desc, 70));
        StringBuilder tip = new StringBuilder(desc);
        JsonNode parameters = spec.path("parameters");
        if (parameters.isObject() && !parameters.isEmpty()) {
            tip.append("\n\n参数：");
            parameters.fieldNames().forEachRemaining(field -> tip.append("\n  ").append(field));
        }
        toolHint.setToolTipText(tip.toString().isBlank() ? null : tip.toString());
    }

    private void showTools() {
        HostClient target = client;
        if (target == null) {
            append("[提示] 先连接。\n\n", ERROR, false);
            return;
        }
        runQuietly("工具列表", () -> {
            JsonNode out = target.tools();
            tools = out;
            populateTools(out);
            StringBuilder sb = new StringBuilder("工具 " + out.path("count").asInt(0) + " 个（全部只读）：")
                    .append(System.lineSeparator());
            for (JsonNode tool : out.path("tools")) {
                sb.append("  - ").append(tool.path("name").asText()).append("  ")
                        .append(HostClient.oneLine(tool.path("description").asText(""), 80))
                        .append(System.lineSeparator());
            }
            append(sb + "\n", MUTED, false);
        });
    }

    /** 只读操作统一走这里：后台线程跑，失败写进记录区，不弹窗打断。 */
    private void runQuietly(String what, ThrowingAction action) {
        if (busy) {
            return;
        }
        setBusy(true);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                action.run();
                return null;
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    get();
                } catch (Exception e) {
                    append("[" + what + "失败] " + HostClient.rootMessage(unwrap(e)) + "\n\n", ERROR, false);
                }
            }
        }.execute();
    }

    // ---------- 状态 ----------

    private void applyFields() {
        config.host(hostField.getText());
        try {
            config.port(Integer.parseInt(portField.getText().trim()));
        } catch (NumberFormatException ignored) {
            // 输错就保留上一次的端口，避免把配置写成脏值
        }
        // host() 可能从 "http://ip:port" 里拆出端口，回填让用户看到真实生效值
        hostField.setText(config.host());
        portField.setText(String.valueOf(config.port()));
    }

    private void setBusy(boolean value) {
        busy = value;
        callBtn.setEnabled(!value);
        connectBtn.setEnabled(!value);
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void append(String text, Color color, boolean bold) {
        StyledDocument doc = logPane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, color);
        StyleConstants.setBold(attrs, bold);
        try {
            doc.insertString(doc.getLength(), text, attrs);
        } catch (BadLocationException ignored) {
            return;
        }
        logPane.setCaretPosition(doc.getLength());
    }

    /** 结果按缩进 JSON 贴进记录区，和人读的一行摘要区分开。 */
    private static String pretty(JsonNode node) {
        String text;
        try {
            text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            text = String.valueOf(node);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, Object> parseArgs(String raw) throws IOException {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Map.of();
        }
        if (!text.startsWith("{")) {
            throw new IllegalArgumentException("参数必须是 JSON 对象，当前是: " + text);
        }
        return JSON.readValue(text, JSON.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    private static Throwable unwrap(Exception e) {
        return e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    // ---------- 入口 ----------

    public static void main(String[] args) {
        Map<String, String> opts = parseOptions(args);
        if (opts.containsKey("help")) {
            System.out.println("用法: java -jar client.jar [--host=192.168.0.31] [--port=18080]"
                    + " [--selftest] [--tool=detect] [--args='{\"max_n\":20}']");
            return;
        }
        ClientConfig config = ClientConfig.load();
        if (opts.containsKey("base")) {
            config.host(opts.get("base"));
        }
        if (opts.containsKey("host")) {
            config.host(opts.get("host"));
        }
        if (opts.containsKey("port")) {
            try {
                config.port(Integer.parseInt(opts.get("port").trim()));
            } catch (NumberFormatException ignored) {
                // 保持配置文件里的值
            }
        }

        if (opts.containsKey("selftest")) {
            System.exit(selftest(config, opts.get("tool"), opts.getOrDefault("args", "")));
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认 L&F 也能跑
        }
        SwingUtilities.invokeLater(() -> {
            ChatWindow window = new ChatWindow(config);
            window.setVisible(true);
            // 分隔条按比例落位：这时面板已有真实尺寸，不会被 preferred size 顶偏
            window.mainSplit.setDividerLocation(0.72);
            window.autoConnect();
        });
    }

    /**
     * 记录区要用等宽字体，但必须能显示中文。
     *
     * <p>Consolas 这类西文等宽字体没有中文字形，Windows 上会画成方块（识别结果里的中文全废）。
     * 逻辑字体 {@code Monospaced}/{@code SansSerif} 由 JDK 组合出中文回退，物理字体没有，
     * 所以这里按「系统真装了 + 确实能显示中文」两个条件挑，都没有才退回逻辑等宽字体。
     */
    private static Font monoFont(int size) {
        String[] preferred = {
                "Sarasa Mono SC", "Microsoft YaHei Mono", "Microsoft YaHei UI", "Microsoft YaHei",
                "PingFang SC", "Hiragino Sans GB", "Menlo",
        };
        for (String name : preferred) {
            Font candidate = new Font(name, Font.PLAIN, size);
            // 字体不存在时 Java 静默换成 Dialog，getFamily() 就不再是原名，借此排除
            if (candidate.getFamily().equalsIgnoreCase(name) && candidate.canDisplayUpTo("识别工具") < 0) {
                return candidate;
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    /** 无界面自检：只验证配置解析 + 连通性，方便在服务器/CI 上排障。 */
    private static int selftest(ClientConfig config, String tool, String args) {
        System.out.println("[selftest] " + config);
        System.out.println("[selftest] 记录字体=" + monoFont(12).getFamily());
        HostClient host = new HostClient(config.baseUrl());
        try {
            JsonNode tools = host.tools();
            System.out.println("[selftest] 工具 " + tools.path("count").asInt(0) + " 个");
            try {
                System.out.println("[selftest] 抓帧 " + (host.frame().length / 1024) + "KB");
            } catch (Exception e) {
                // 窗口没开也会走到这里，不影响自检结论
                System.out.println("[selftest] 抓帧不可用：" + HostClient.rootMessage(e));
            }
            if (tool != null && !tool.isBlank()) {
                JsonNode out = host.invoke(tool, parseArgs(args));
                System.out.println("[selftest] " + tool + " ok=" + out.path("ok").asBoolean(false)
                        + " " + HostClient.oneLine(out.toString(), 300));
            }
            return 0;
        } catch (Exception e) {
            System.err.println("[selftest] 失败：" + HostClient.rootMessage(e));
            return 1;
        }
    }

    /** 支持 --key=value 与 --key value 两种写法（与控制台一致）。 */
    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String item = args[i];
            if (!item.startsWith("--")) {
                continue;
            }
            String body = item.substring(2);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                out.put(body.substring(0, eq), body.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(body, args[i + 1]);
                i++;
            } else {
                out.put(body, "true");
            }
        }
        return out;
    }
}
