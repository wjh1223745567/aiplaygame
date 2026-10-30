package com.autogame.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * macOS 输入接管检测。
 *
 * <p>两条判据，覆盖「人」和「程序」两种接管：
 * <ol>
 *   <li>CGEventSourceSecondsSinceLastEventType：距上一条真实输入事件的秒数，用来发现有人正在操作；</li>
 *   <li>ps 里是否跑着远程控制类进程（TeamViewer、向日葵、AnyDesk、ToDesk 等），它们接管时本机收不到输入事件。</li>
 * </ol>
 */
@Service
public class MacInputActivity implements InputActivity {

    private static final Logger log = LoggerFactory.getLogger(MacInputActivity.class);

    /** CoreGraphics 里只需要这一个函数。 */
    interface CoreGraphics extends Library {

        CoreGraphics INSTANCE = Native.load("CoreGraphics", CoreGraphics.class);

        /**
         * @param stateId   kCGEventSourceStateCombinedSessionState = 0
         * @param eventType kCGAnyInputEventType = 0xFFFFFFFF（int 即 -1）
         * @return 距上次该类型事件的秒数
         */
        double CGEventSourceSecondsSinceLastEventType(int stateId, int eventType);
    }

    private static final int COMBINED_SESSION_STATE = 0;
    private static final int ANY_INPUT_EVENT = -1;

    /** 会把鼠标键盘从本机接走的工具；命中即认为输入可能已被接管。 */
    private static final List<String> HIJACKER_KEYWORDS = List.of(
            "teamviewer", "sunloginclient", "anydesk", "todesk", "rustdesk",
            "splashtop", "awesun", "vncserver", "tigervnc", "nomachine");

    /** 驱动/服务/代理类进程名不算接管：它们常驻后台，实际并没有在操作鼠标。 */
    private static final List<String> NOISE_KEYWORDS = List.of(
            "driver", "helper", "audio", "service", "daemon", "agent", "update", "launcher");

    @Override
    public long idleMillis() {
        try {
            double seconds = CoreGraphics.INSTANCE
                    .CGEventSourceSecondsSinceLastEventType(COMBINED_SESSION_STATE, ANY_INPUT_EVENT);
            if (Double.isNaN(seconds) || seconds < 0) {
                return -1;
            }
            return Math.round(seconds * 1000);
        } catch (RuntimeException | LinkageError e) {
            log.warn("查询输入空闲时间失败：{}", e.toString());
            return -1;
        }
    }

    @Override
    public List<String> hijackers() {
        try {
            Process process = new ProcessBuilder("ps", "-Ao", "comm=").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            return output.lines()
                    .map(line -> line.strip().toLowerCase(Locale.ROOT))
                    .filter(line -> !line.isEmpty())
                    .filter(line -> NOISE_KEYWORDS.stream().noneMatch(line::contains))
                    .filter(line -> HIJACKER_KEYWORDS.stream().anyMatch(line::contains))
                    .map(line -> line.substring(line.lastIndexOf('/') + 1))
                    .distinct()
                    .toList();
        } catch (IOException e) {
            log.warn("扫描接管类进程失败：{}", e.toString());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
