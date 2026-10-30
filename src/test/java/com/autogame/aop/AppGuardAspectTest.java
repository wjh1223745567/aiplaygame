package com.autogame.aop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.env.MockEnvironment;

import com.autogame.config.AutoGameProperties;
import com.autogame.platform.WindowInfo;
import com.autogame.service.AppPreflight;
import com.autogame.support.ToolException;

/**
 * 验证 @RequiresApp 切面：声明在方法上就自动跑前置保障，没声明就不打扰。
 */
class AppGuardAspectTest {

    private final RecordingPreflight preflight = new RecordingPreflight();

    /** 切面织入前的原始对象：方法体在它上面执行，计数也从它身上读。 */
    private Demo raw;

    @Test
    void 字面量应用名直接当目标() {
        target().literal();

        assertEquals(1, preflight.calls.size());
        assertEquals("Calculator", preflight.calls.getFirst()[0]);
        assertNull(preflight.calls.getFirst()[1]);
    }

    @Test
    void 表达式能从请求参数里取应用名和标题() {
        target().fromRequest(new Req("Calculator", "计算器"));

        assertEquals(1, preflight.calls.size());
        assertEquals("Calculator", preflight.calls.getFirst()[0]);
        assertEquals("计算器", preflight.calls.getFirst()[1]);
    }

    @Test
    void 请求为空时跳过保障而不是报错() {
        assertEquals("ok", target().fromRequest(null));
        assertTrue(preflight.calls.isEmpty());
        assertEquals(1, raw.executed);
    }

    @Test
    void 没标注解的方法不被拦截() {
        target().plain();
        assertTrue(preflight.calls.isEmpty());
        assertEquals(1, raw.executed);
    }

    @Test
    void 占位符先从配置解析成目标应用() {
        target().fromConfig();

        assertEquals(1, preflight.calls.size());
        assertEquals("问道", preflight.calls.getFirst()[0]);
        // 配置里 title 为空 → 不做标题限制
        assertNull(preflight.calls.getFirst()[1]);
    }

    @Test
    void 前置保障失败时方法体不执行() {
        preflight.failure = ToolException.locked("鼠标被接管");
        Demo proxy = target();

        ToolException error = assertThrows(ToolException.class, () -> proxy.fromRequest(new Req("A", null)));

        assertEquals(423, error.status());
        assertEquals(0, raw.executed);
    }

    @Test
    void 前置保障通过后才执行方法体() {
        target().fromRequest(new Req("A", null));
        assertEquals(1, raw.executed);
    }

    @Test
    void 表达式写错时返回400() {
        // 传入非空请求，让写错的方法名真的被求值到，而不是被 ?. 短路
        ToolException error = assertThrows(ToolException.class, () -> target().broken(new Req("A", null)));
        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("@RequiresApp"));
    }

    private Demo target() {
        raw = new Demo();
        AspectJProxyFactory factory = new AspectJProxyFactory(raw);
        factory.addAspect(new AppGuardAspect(preflight, environment()));
        return factory.getProxy();
    }

    /** 只放游戏那段配置，用来验证注解里的 ${...} 占位符。 */
    private static MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("autogame.game.wendao.app", "问道")
                .withProperty("autogame.game.wendao.title", "");
    }

    public record Req(String app, String title) {
    }

    /** 被增强的目标：字面量、从参数取、写错表达式、不标，四种情况。 */
    public static class Demo {

        int executed;

        @RequiresApp(app = "Calculator")
        public String literal() {
            executed++;
            return "ok";
        }

        @RequiresApp(app = "#request?.app()", title = "#request?.title()")
        public String fromRequest(Req request) {
            executed++;
            return "ok";
        }

        @RequiresApp(app = "${autogame.game.wendao.app}", title = "${autogame.game.wendao.title}")
        public String fromConfig() {
            executed++;
            return "ok";
        }

        @RequiresApp(app = "#request?.noSuchMethod()")
        public String broken(Req request) {
            executed++;
            return "ok";
        }

        public String plain() {
            executed++;
            return "ok";
        }
    }

    /** 替身：只记录切面来问过什么，不碰真实的桌面。 */
    private static final class RecordingPreflight extends AppPreflight {

        private final List<String[]> calls = new ArrayList<>();
        private RuntimeException failure;

        private RecordingPreflight() {
            super(null, null, new AutoGameProperties(null, null, null, null));
        }

        @Override
        public WindowInfo ensureReady(String app, String title) {
            return record(app, title);
        }

        @Override
        public WindowInfo ensureReady(String app, String title, Duration windowTimeout) {
            return record(app, title);
        }

        private WindowInfo record(String app, String title) {
            calls.add(new String[] { app, title });
            if (failure != null) {
                throw failure;
            }
            return new WindowInfo(app, "", 1L, 0, 0, 1, 1, true, 1L);
        }
    }
}
