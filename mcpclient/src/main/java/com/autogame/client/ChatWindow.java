package com.autogame.client;

import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
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
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Windows 图形客户端：填 host 的 IP/端口 -> 连接 -> 自然语言驱动。
 *
 * <p>和 {@link ChatConsole} 一样只做「发一句话、看 AI 调了哪些工具」，决策与工具执行都在
 * host / mcpserver 侧。地址、会话、截图开关会写进 client.properties，下次打开自动带出。
 *
 * <pre>
 * java -jar client.jar                        # 读 client.properties，默认 192.168.0.31:18080
 * java -jar client.jar --host=192.168.0.31 --port=18080
 * java -jar client.jar --selftest             # 不开窗口，只验证能不能连上
 * </pre>
 */
public final class ChatWindow extends JFrame {

    private static final Color INK = new Color(0x1F2328);
    private static final Color USER = new Color(0x1F4E79);
    private static final Color AI = new Color(0x146C43);
    private static final Color ERROR = new Color(0xB42318);
    private static final Color MUTED = new Color(0x6E7781);

    private final ClientConfig config;
    private final JTextField hostField;
    private final JTextField portField;
    private final JTextField sessionField;
    private final JLabel statusLabel = new JLabel("未连接");
    private final JTextPane chatPane = new JTextPane();
    private final JTextArea traceArea = new JTextArea(9, 80);
    private final JTextArea inputArea = new JTextArea(3, 60);
    private final JCheckBox visionCheck;
    private final JButton sendBtn = new JButton("发送 (Ctrl+Enter)");
    private final JButton connectBtn = new JButton("连接");

    private HostClient client;
    private boolean busy;

    public ChatWindow(ClientConfig config) {
        super("Autogame 客户端");
        this.config = config;
        this.hostField = new JTextField(config.host(), 14);
        this.portField = new JTextField(String.valueOf(config.port()), 6);
        this.sessionField = new JTextField(config.session(), 8);
        this.visionCheck = new JCheckBox("随屏截图", config.vision() == null || config.vision());

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 560));
        setSize(980, 720);
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
        left.add(new JLabel("  会话"));
        left.add(sessionField);
        bar.add(left, BorderLayout.WEST);
        bar.add(statusLabel, BorderLayout.EAST);

        connectBtn.addActionListener(e -> connect());
        hostField.addActionListener(e -> connect());
        portField.addActionListener(e -> connect());
        return bar;
    }

    private JSplitPane buildCenter() {
        chatPane.setEditable(false);
        chatPane.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        chatPane.setBackground(Color.WHITE);
        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setBorder(new EmptyBorder(0, 0, 0, 0));

        traceArea.setEditable(false);
        traceArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        traceArea.setForeground(INK);
        JScrollPane traceScroll = new JScrollPane(traceArea);

        JPanel tracePanel = new JPanel(new BorderLayout(0, 4));
        tracePanel.add(new JLabel("工具调用明细"), BorderLayout.NORTH);
        tracePanel.add(traceScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chatScroll, tracePanel);
        split.setResizeWeight(0.72);
        split.setDividerSize(6);
        return split;
    }

    private JPanel buildInputBar() {
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        // Ctrl+Enter 发送；Enter 留给换行，方便贴多行文字
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "send");
        inputArea.getActionMap().put("send", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                send();
            }
        });

        JPanel more = new JPanel(new GridLayout(1, 2, 6, 0));
        more.add(button("历史", this::showHistory));
        more.add(button("清空", this::resetSession));

        JPanel buttons = new JPanel(new GridLayout(4, 1, 0, 6));
        buttons.add(sendBtn);
        buttons.add(visionCheck);
        buttons.add(button("工具列表", this::showTools));
        buttons.add(more);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        bar.add(buttons, BorderLayout.EAST);
        sendBtn.addActionListener(e -> send());

        append("[提示] 先确认 host 已启动（macOS 侧 18080），再点「连接」。地址可随时改，改完回车即重连。\n\n", MUTED, false);
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
                    int count = out.path("count").asInt(0);
                    setStatus("已连接 " + base + " · 工具 " + count + " 个", AI);
                    append("[已连接] " + base + "，可用工具 " + count + " 个。\n\n", MUTED, false);
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

    private void send() {
        if (busy) {
            return;
        }
        String text = inputArea.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (client == null) {
            append("[提示] 还没连上 host，先点「连接」。\n\n", ERROR, false);
            return;
        }
        applyFields();
        config.save();

        inputArea.setText("");
        append("你> " + text + "\n", USER, true);
        append("[思考中…]\n", MUTED, false);
        traceArea.append("—— 本轮开始：" + text + System.lineSeparator());

        String session = config.session();
        Boolean vision = visionCheck.isSelected();
        setBusy(true);
        new SwingWorker<JsonNode, Void>() {
            @Override
            protected JsonNode doInBackground() throws Exception {
                return client.chat(session, text, vision);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    renderReply(get());
                } catch (Exception e) {
                    append("[失败] " + HostClient.rootMessage(unwrap(e)) + "\n\n", ERROR, false);
                    traceArea.append("—— 本轮失败" + System.lineSeparator());
                }
            }
        }.execute();
    }

    /** 每一步工具调用都进下方明细，回答进上方对话区。 */
    private void renderReply(JsonNode out) {
        StringBuilder trace = new StringBuilder();
        JsonNode steps = out.path("steps");
        int index = 0;
        for (JsonNode step : steps) {
            index++;
            trace.append("  ↳ [").append(index).append("] ")
                    .append(step.path("tool").asText())
                    .append(' ').append(step.path("args"))
                    .append("  ").append(step.path("ms").asLong()).append("ms")
                    .append(step.path("error").asBoolean() ? " [出错]" : "")
                    .append(System.lineSeparator());
            String result = step.path("result").asText("");
            if (!result.isEmpty()) {
                trace.append("      ").append(HostClient.oneLine(result, 600)).append(System.lineSeparator());
            }
        }
        trace.append("—— 本轮 ").append(index).append(" 次工具调用 / ")
                .append(out.path("rounds").asInt(0)).append(" 轮模型请求")
                .append(System.lineSeparator()).append(System.lineSeparator());
        traceArea.append(trace.toString());

        String reply = out.path("reply").asText("");
        if (out.path("ok").asBoolean(false)) {
            append("AI> " + reply + "\n\n", AI, false);
        } else {
            append("AI(未完成)> " + reply + "\n\n", ERROR, false);
        }
    }

    private void showTools() {
        HostClient target = client;
        if (target == null) {
            append("[提示] 先连接。\n\n", ERROR, false);
            return;
        }
        runQuietly("工具列表", () -> {
            JsonNode out = target.tools();
            StringBuilder sb = new StringBuilder("工具 " + out.path("count").asInt(0) + " 个：")
                    .append(System.lineSeparator());
            for (JsonNode tool : out.path("tools")) {
                sb.append("  - ").append(tool.path("name").asText()).append("  ")
                        .append(HostClient.oneLine(tool.path("description").asText(""), 80))
                        .append(System.lineSeparator());
            }
            traceArea.append(sb.toString());
        });
    }

    private void showHistory() {
        HostClient target = client;
        if (target == null) {
            append("[提示] 先连接。\n\n", ERROR, false);
            return;
        }
        String session = config.session();
        runQuietly("历史", () -> {
            JsonNode out = target.history(session);
            JsonNode messages = out.path("messages");
            StringBuilder sb = new StringBuilder("会话 " + out.path("session").asText(session)
                    + " 共 " + messages.size() + " 条消息：").append(System.lineSeparator());
            int i = 0;
            for (JsonNode message : messages) {
                i++;
                String content = message.path("content").isTextual()
                        ? message.path("content").asText()
                        : message.path("content").toString();
                sb.append("  ").append(i).append(". [").append(message.path("role").asText()).append("] ")
                        .append(HostClient.oneLine(content, 300)).append(System.lineSeparator());
            }
            traceArea.append(sb.toString());
        });
    }

    private void resetSession() {
        HostClient target = client;
        if (target == null) {
            append("[提示] 先连接。\n\n", ERROR, false);
            return;
        }
        String session = config.session();
        runQuietly("清空", () -> {
            target.reset(session);
            append("[已清空] 会话 " + session + " 的上下文已重置。\n\n", MUTED, false);
        });
    }

    /** 只读操作统一走这里：后台线程跑，失败写进对话区，不弹窗打断。 */
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
        config.session(sessionField.getText());
        config.vision(visionCheck.isSelected());
        // host() 可能从 "http://ip:port" 里拆出端口，回填让用户看到真实生效值
        hostField.setText(config.host());
        portField.setText(String.valueOf(config.port()));
        sessionField.setText(config.session());
    }

    private void setBusy(boolean value) {
        busy = value;
        sendBtn.setEnabled(!value);
        connectBtn.setEnabled(!value);
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void append(String text, Color color, boolean bold) {
        StyledDocument doc = chatPane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, color);
        StyleConstants.setBold(attrs, bold);
        try {
            doc.insertString(doc.getLength(), text, attrs);
        } catch (BadLocationException ignored) {
            return;
        }
        chatPane.setCaretPosition(doc.getLength());
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
                    + " [--session=win] [--selftest] [--message=一句话]");
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
        if (opts.containsKey("session")) {
            config.session(opts.get("session"));
        }

        if (opts.containsKey("selftest")) {
            System.exit(selftest(config, opts.get("message")));
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认 L&F 也能跑
        }
        SwingUtilities.invokeLater(() -> {
            ChatWindow window = new ChatWindow(config);
            window.setVisible(true);
            window.autoConnect();
        });
    }

    /** 无界面自检：只验证配置解析 + 连通性，方便在服务器/CI 上排障。 */
    private static int selftest(ClientConfig config, String message) {
        System.out.println("[selftest] " + config);
        HostClient host = new HostClient(config.baseUrl());
        try {
            JsonNode tools = host.tools();
            System.out.println("[selftest] 工具 " + tools.path("count").asInt(0) + " 个");
            if (message != null && !message.isBlank()) {
                JsonNode out = host.chat(config.session(), message, config.vision());
                System.out.println("[selftest] ok=" + out.path("ok").asBoolean(false)
                        + " steps=" + out.path("steps").size()
                        + " reply=" + HostClient.oneLine(out.path("reply").asText(""), 200));
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
