package com.autogame.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 大模型判断日志：每次模型返回后，把「原始输出 + 解析出的决策 + 工具执行结果 + 本轮结论」
 * 追加到项目根的 {@code ai-decisions.log}（即启动时的当前目录），可直接 {@code tail -f} 看。
 *
 * <p>mcpserver 侧的 {@code core/decision_log.py} 写同一份文件，用 {@code source=host} /
 * {@code source=mcpserver} 区分，便于把「对话决策」和「游戏内决策」放在一条时间线上对照。
 *
 * <p>日志属旁路观察：写失败只打 WARN，绝不影响对话主流程；
 * 超过 {@link #MAX_BYTES} 时轮转为 {@code ai-decisions.log.1}（覆盖上一份）。
 */
@Component
public class DecisionLog {

    private static final Logger log = LoggerFactory.getLogger(DecisionLog.class);

    /** 日志文件名（项目根下，与 Python 侧保持一致）。 */
    static final String FILE_NAME = "ai-decisions.log";
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final String RULE = "─".repeat(72);

    private final Path file;

    public DecisionLog(AiSettings settings) {
        this.file = settings.projectRoot().resolve(FILE_NAME);
    }

    /** 日志文件位置（排查时可直接打开）。 */
    public Path file() {
        return file;
    }

    /** 追加一块日志：块首补分隔线，block 内一行一个事件。 */
    public synchronized void append(String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        try {
            rotateIfNeeded();
            Files.writeString(file, RULE + "\n" + block.stripTrailing() + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            log.warn("写大模型判断日志失败（{}）: {}", file, e.toString());
        }
    }

    /** 毫秒时间戳 -> 日志里的时间文本。 */
    static String stamp(long epochMillis) {
        return TS.format(Instant.ofEpochMilli(epochMillis));
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(file) || Files.size(file) <= MAX_BYTES) {
            return;
        }
        Files.move(file, file.resolveSibling(FILE_NAME + ".1"),
                StandardCopyOption.REPLACE_EXISTING);
    }
}
