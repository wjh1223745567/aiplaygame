package com.autogame.service;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.config.AutoGameProperties;
import com.autogame.platform.CapturedImage;
import com.autogame.platform.DesktopPlatform;
import com.autogame.platform.DesktopRobot;
import com.autogame.platform.WindowInfo;
import com.autogame.support.ToolException;

/**
 * 截图服务：整屏或指定窗口。坐标统一用逻辑像素，直接喂 AWT Robot，不自己做 Retina 换算。
 */
@Service
public class ScreenService {

    private static final Logger log = LoggerFactory.getLogger(ScreenService.class);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final DesktopPlatform platform;
    private final DesktopRobot desktopRobot;
    private final AutoGameProperties.Capture captureConfig;

    public ScreenService(DesktopPlatform platform, DesktopRobot desktopRobot, AutoGameProperties properties) {
        this.platform = platform;
        this.desktopRobot = desktopRobot;
        this.captureConfig = properties.capture();
    }

    /** 整屏截图（多显示器取并集）。 */
    public CapturedImage captureScreen() {
        Rectangle bounds = screenBounds();
        return capture(bounds, "screen");
    }

    /** 按进程名/标题定位窗口后只截该窗口。优先按窗口号抓（可抓到被遮挡的内容），失败再裁剪整屏。 */
    public CapturedImage captureWindow(String app, String title) {
        WindowInfo window = findWindow(app, title);
        CapturedImage byWindowId = captureWindowById(window);
        if (byWindowId != null) {
            return byWindowId;
        }
        Rectangle bounds = new Rectangle(window.x(), window.y(), window.width(), window.height());
        Rectangle visible = bounds.intersection(screenBounds());
        if (visible.isEmpty()) {
            throw ToolException.badRequest("窗口不在可见区域内：" + window.app() + " / " + window.title());
        }
        return capture(visible, "window:" + window.app() + " - " + window.title());
    }

    /** target 取值：screen（默认）或 window。base64 直传时不截图。 */
    public CapturedImage capture(String target, String app, String title) {
        String normalized = target == null || target.isBlank() ? "screen" : target.trim().toLowerCase();
        return switch (normalized) {
            case "screen" -> captureScreen();
            case "window" -> captureWindow(app, title);
            default -> throw ToolException.badRequest("target 只支持 screen 或 window，收到：" + target);
        };
    }

    /** 落盘到 autogame.capture.debug-dir，返回文件路径。 */
    public Path save(CapturedImage image) {
        try {
            Path dir = Path.of(captureConfig.debugDir());
            Files.createDirectories(dir);
            Path file = dir.resolve("shot_" + LocalDateTime.now().format(FILE_STAMP) + ".png");
            Files.write(file, image.png());
            log.info("截图已保存：{}（{}x{}）", file.toAbsolutePath(), image.pixelWidth(), image.pixelHeight());
            return file.toAbsolutePath();
        } catch (IOException e) {
            throw ToolException.internal("保存截图失败：" + e.getMessage(), e);
        }
    }

    public WindowInfo findWindow(String app, String title) {
        List<WindowInfo> windows = platform.windows();
        return windows.stream()
                .filter(w -> w.matches(app, title))
                .max((left, right) -> Integer.compare(left.area(), right.area()))
                .orElseThrow(() -> ToolException.notFound("没有找到匹配的窗口，app=" + app + " title=" + title
                        + "；当前可见窗口：" + summary(windows)));
    }

    /** 用系统的 screencapture 按窗口号抓图（含被遮挡部分）；不可用时返回 null 交给调用方回退。 */
    private CapturedImage captureWindowById(WindowInfo window) {
        Path temp = null;
        try {
            temp = Files.createTempFile("autogame-window-", ".png");
            List<String> command = List.of("screencapture", "-x", "-o", "-l", String.valueOf(window.windowId()),
                    temp.toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                log.debug("screencapture -l {} 失败，改用整屏裁剪：{}", window.windowId(), output.strip());
                return null;
            }
            byte[] png = Files.readAllBytes(temp);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (png.length == 0 || image == null) {
                return null;
            }
            Rectangle bounds = new Rectangle(window.x(), window.y(), window.width(), window.height());
            return new CapturedImage(png, image.getWidth(), image.getHeight(), bounds,
                    "window:" + window.app() + " - " + window.title());
        } catch (IOException | RuntimeException e) {
            log.debug("screencapture -l 异常，改用整屏裁剪：{}", e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响业务
                }
            }
        }
    }

    private CapturedImage capture(Rectangle bounds, String source) {
        BufferedImage image = desktopRobot.robot().createScreenCapture(bounds);
        if (image == null) {
            throw ToolException.internal("截图失败：请确认已给运行本服务的终端/IDE 授予「屏幕录制」权限");
        }
        byte[] png = toPng(image);
        return new CapturedImage(png, image.getWidth(), image.getHeight(), bounds, source);
    }

    private byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw ToolException.internal("PNG 编码失败：" + e.getMessage(), e);
        }
    }

    private Rectangle screenBounds() {
        Rectangle union = null;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            union = union == null ? bounds : union.union(bounds);
        }
        return union == null ? new Rectangle(1920, 1080) : union;
    }

    private String summary(List<WindowInfo> windows) {
        return windows.stream()
                .limit(10)
                .map(w -> w.app() + " / " + w.title())
                .reduce((left, right) -> left + "；" + right)
                .orElse("无");
    }
}
