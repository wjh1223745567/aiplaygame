package com.autogame.game.wendao;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 「问道」专属配置，全部来自 application.yml 的 autogame.game.wendao 前缀。
 *
 * <p>坐标一律用「相对窗口的比例」（0~1），窗口大小、位置变了不用重新标定：
 * {@code x = 0.5} 指窗口宽度的中间，{@code y = 0.62} 指高度的 62% 处。
 */
@ConfigurationProperties(prefix = "autogame.game.wendao")
public record WendaoProperties(String app, String title, Account account, Login login, Shimen shimen) {

    /**
     * 游戏客户端从双击到出现登录窗口的最长等待：比桌面应用的默认 8s 宽得多，
     * 因为启动器还要拉资源、过登录器更新。注解放这里当常量用，注解参数必须是编译期常量。
     */
    public static final long LAUNCH_WINDOW_TIMEOUT_MS = 90_000;

    public WendaoProperties {
        app = blankTo(app, "问道");
        title = title == null ? "" : title.trim();
        account = account == null ? Account.defaults() : account;
        login = login == null ? Login.defaults() : login;
        shimen = shimen == null ? Shimen.defaults() : shimen;
    }

    /** 账号密码：password 建议用环境变量注入，不要写进仓库。 */
    public record Account(String user, String password) {

        public Account {
            user = user == null ? "" : user.trim();
            password = password == null ? "" : password;
        }

        static Account defaults() {
            return new Account("", "");
        }

        public boolean configured() {
            return !user.isEmpty() && !password.isEmpty();
        }
    }

    /**
     * 登录界面各控件的位置与等待时长。
     *
     * @param accountBox   账号输入框
     * @param passwordBox  密码输入框
     * @param loginButton  登录按钮
     * @param enterButton  登录后「进入游戏 / 选服」按钮；有的客户端没有，可留默认
     * @param noticeButton 登录后弹出的公告 / 协议「确定」按钮
     * @param readyTimeout 点完登录后等主界面的最长时间
     * @param pollInterval 轮询判断界面的间隔
     */
    public record Login(Spot accountBox, Spot passwordBox, Spot loginButton, Spot enterButton, Spot noticeButton,
            Duration readyTimeout, Duration pollInterval) {

        private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofMinutes(3);
        private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(3);

        public Login {
            accountBox = accountBox == null ? new Spot(0.40, 0.44) : accountBox;
            passwordBox = passwordBox == null ? new Spot(0.40, 0.50) : passwordBox;
            loginButton = loginButton == null ? new Spot(0.40, 0.58) : loginButton;
            enterButton = enterButton == null ? new Spot(0.50, 0.78) : enterButton;
            noticeButton = noticeButton == null ? new Spot(0.50, 0.66) : noticeButton;
            readyTimeout = readyTimeout == null ? DEFAULT_READY_TIMEOUT : readyTimeout;
            pollInterval = pollInterval == null ? DEFAULT_POLL_INTERVAL : pollInterval;
        }

        static Login defaults() {
            return new Login(null, null, null, null, null, DEFAULT_READY_TIMEOUT, DEFAULT_POLL_INTERVAL);
        }
    }

    /**
     * 师门任务流程参数。
     *
     * @param taskTracker   右侧任务追踪栏里的师门任务条目，点它会自动寻路
     * @param confirmButton 对话框里的「确定 / 接受 / 交任务」按钮
     * @param rounds        要跑的环数；做到这个数就停
     * @param roundInterval 每次点击后的喘息时间
     * @param battleWait    检测到战斗时只等不点，这里是单次等待时长
     * @param totalTimeout  整轮的上限，超时结束
     * @param reloginLimit  掉线时最多自动重登几次
     */
    public record Shimen(Spot taskTracker, Spot confirmButton, Integer rounds, Duration roundInterval,
            Duration battleWait, Duration totalTimeout, Integer reloginLimit) {

        private static final int DEFAULT_ROUNDS = 20;
        private static final Duration DEFAULT_ROUND_INTERVAL = Duration.ofSeconds(1);
        private static final Duration DEFAULT_BATTLE_WAIT = Duration.ofSeconds(5);
        private static final Duration DEFAULT_TOTAL_TIMEOUT = Duration.ofMinutes(45);
        private static final int DEFAULT_RELOGIN_LIMIT = 1;

        public Shimen {
            taskTracker = taskTracker == null ? new Spot(0.86, 0.18) : taskTracker;
            confirmButton = confirmButton == null ? new Spot(0.50, 0.68) : confirmButton;
            rounds = rounds == null || rounds <= 0 ? DEFAULT_ROUNDS : rounds;
            roundInterval = roundInterval == null ? DEFAULT_ROUND_INTERVAL : roundInterval;
            battleWait = battleWait == null ? DEFAULT_BATTLE_WAIT : battleWait;
            totalTimeout = totalTimeout == null ? DEFAULT_TOTAL_TIMEOUT : totalTimeout;
            reloginLimit = reloginLimit == null || reloginLimit < 0 ? DEFAULT_RELOGIN_LIMIT : reloginLimit;
        }

        static Shimen defaults() {
            return new Shimen(null, null, DEFAULT_ROUNDS, DEFAULT_ROUND_INTERVAL, DEFAULT_BATTLE_WAIT,
                    DEFAULT_TOTAL_TIMEOUT, DEFAULT_RELOGIN_LIMIT);
        }
    }

    /**
     * 相对窗口的比例坐标。
     *
     * @param x 横向比例，0 是窗口左边缘，1 是右边缘
     * @param y 纵向比例，0 是窗口上边缘，1 是下边缘
     */
    public record Spot(double x, double y) {

        public Spot {
            if (x < 0 || x > 1 || y < 0 || y > 1) {
                throw new IllegalArgumentException("坐标必须是 0~1 的窗口比例，收到 x=" + x + " y=" + y);
            }
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
