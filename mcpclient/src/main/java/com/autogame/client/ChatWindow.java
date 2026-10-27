package com.autogame.client;

import com.fasterxml.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
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
import java.awt.GridLayout;
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
 * Windows 图形客户端：填 host 的 IP/端口 -> 连接 -> 自然语言驱动。
 *
 * <p>和 {@link ChatConsole} 一样只做「发一句话、看 AI 调了哪些工具」，决策与工具执行都在
 * host / mcpserver 侧。地址、会话、截图开关会写进 client.properties，下次打开自动带出。
 *
 * <p>右侧是当前画面快照（host 的 {@code /ai/frame}）：不按秒轮询，只有手动点「看当前画面」、
 * 或本轮以「绕圈/卡住」收场时才抓一帧。这一步走的是独立 HTTP，不等候正在执行的那一步，
 * 所以“看着像卡住了”的时候至少能看到游戏停在哪里。
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
    private static final Color VIDEO_BG = new Color(0x0D1117);
    /** 收尾文案里出现这些字样就算「绕圈/卡住」：只有这种结局才自动抓一帧，其余等用户动手。 */
    private static final String[] STUCK_HINTS = {
            "疑似卡住", "没有产生任何动作", "轮数已达上限", "同一操作重复"
    };
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

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
    private final JButton connectBtn = new JButton("连 接");
    private final JLabel frameLabel = new JLabel("连上 host 后可见当前画面", SwingConstants.CENTER);
    private final JLabel frameHint = new JLabel("点「看当前画面」抓一帧（卡住时自动抓一次）");
    private final JButton frameBtn = new JButton("看当前画面");

    private HostClient client;
    private boolean busy;
    /** 上一个抓帧请求还没回来（抓帧不能叠着发，否则宿主机排队越堆越长）。 */
    private boolean frameFetching;
    /** 最近一帧原图：窗口缩放时用它重缩放，不重新请求。 */
    private BufferedImage frameImage;
    /** 左右分栏，窗口出来后把分隔条摆到中间靠右。 */
    private JSplitPane mainSplit;

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
        traceArea.setFont(monoFont(12));
        traceArea.setForeground(INK);
        JScrollPane traceScroll = new JScrollPane(traceArea);

        JPanel tracePanel = new JPanel(new BorderLayout(0, 4));
        tracePanel.add(new JLabel("工具调用明细"), BorderLayout.NORTH);
        tracePanel.add(traceScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chatScroll, tracePanel);
        split.setResizeWeight(0.72);
        split.setDividerSize(6);

        mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, split, buildFramePanel());
        mainSplit.setResizeWeight(0.74);
        mainSplit.setDividerSize(6);
        return mainSplit;
    }

    /** 右侧画面面板：一眼看到游戏现在停在哪。只给人看，不参与任何决策。 */
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
        panel.setPreferredSize(new Dimension(340, 0));
        panel.setMinimumSize(new Dimension(240, 0));
        return panel;
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
                    JsonNode out = get();
                    // 平时不自动抓帧：只有这一轮以「绕圈/卡住」收场时才补一张，看它停在哪
                    String stuck = stuckReason(out);
                    if (!stuck.isEmpty()) {
                        refreshFrame(true, stuck);
                    }
                    renderReply(out);
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
            trace.append("  - [").append(index).append("] ")
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

    // ---------- 画面 ----------

    /**
     * 本轮是不是「绕圈/卡住」收场：是的话返回一个短标签，否则空串。
     *
     * <p>两条来源：mcpserver 侧的画面指纹绕圈（写在 step 的 result 里），和 host 侧的硬限制
     * （连续几步无动作 / 同一操作重复 / 轮数用完）。这些结局最需要一张图——看它到底停在哪。
     */
    private static String stuckReason(JsonNode out) {
        for (JsonNode step : out.path("steps")) {
            if (step.path("result").asText("").contains("绕圈")) {
                return "绕圈";
            }
        }
        if (out.path("ok").asBoolean(true)) {
            return "";
        }
        String reply = out.path("reply").asText("");
        for (String hint : STUCK_HINTS) {
            if (reply.contains(hint)) {
                return "疑似卡住";
            }
        }
        return "";
    }

    /** 手动/连接时抓一帧（没有额外标签）。 */
    private void refreshFrame(boolean silent) {
        refreshFrame(silent, "");
    }

    /**
     * 拉一帧贴到右侧。
     *
     * <p>silent=true 是不带错误刷屏的抓帧：失败只更新画面标题下那行小字
     * ——抓帧失败往往就是窗口没了/游戏关了，用户此刻在意的不是这个。
     *
     * <p>note 非空时写进标题下的小字（如「疑似卡住」），用来区分这张图是怎么来的。
     *
     * <p>走的是 host 的 /ai/frame，与发消息的那条请求互不影响，所以一轮对话跑着的时候一样能刷。
     */
    private void refreshFrame(boolean silent, String note) {
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
        frameHint.setText(stamp() + " 抓取中…" + (note.isEmpty() ? "" : "（" + note + "）"));
        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() throws Exception {
                return target.frame();
            }

            @Override
            protected void done() {
                frameFetching = false;
                try {
                    showFrame(get(), note);
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

    private void showFrame(byte[] jpeg, String note) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (image == null) {
            throw new IOException("返回内容不是 JPEG（" + jpeg.length + " 字节）");
        }
        frameImage = image;
        renderFrame();
        // 标签放最前：面板窄，放末尾会被截成「…」，而这张图怎么来的比分辨率重要
        String text = (note.isEmpty() ? "" : note + " · ") + stamp() + " "
                + image.getWidth() + "×" + image.getHeight()
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
            // 分隔条按比例落位：这时面板已有真实尺寸，不会被 preferred size 顶偏
            window.mainSplit.setDividerLocation(0.72);
            window.autoConnect();
        });
    }

    /**
     * 明细区要用等宽字体，但必须能显示中文。
     *
     * <p>Consolas 这类西文等宽字体没有中文字形，Windows 上会画成方块（工具返回里的中文全废）。
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
            if (candidate.getFamily().equalsIgnoreCase(name) && candidate.canDisplayUpTo("工具调用") < 0) {
                return candidate;
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    /** 无界面自检：只验证配置解析 + 连通性，方便在服务器/CI 上排障。 */
    private static int selftest(ClientConfig config, String message) {
        System.out.println("[selftest] " + config);
        System.out.println("[selftest] 明细字体=" + monoFont(12).getFamily());
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
