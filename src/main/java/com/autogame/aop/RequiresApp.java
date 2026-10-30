package com.autogame.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明「执行这个方法之前，先把目标应用准备好」：没启动就启动、不在顶层就置顶、
 * 鼠标被接管就暂停（423）。真正的检查由 {@link AppGuardAspect} 在切面里做，
 * 实现类只管写自己的能力，不用关心前置条件。
 *
 * <p>两种写法：
 * <ul>
 *   <li>字面量：{@code @RequiresApp(app = "Calculator")}</li>
 *   <li>表达式（以 # 开头按 SpEL 解析，可引用方法参数名）：{@code @RequiresApp(app = "#request?.app()")}</li>
 * </ul>
 * 取不到值时（返回 null 或空串）跳过前置保障，视为与桌面无关的纯计算类方法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresApp {

    /** 目标应用名或 .app 绝对路径；以 {@code #} 开头时按 SpEL 解析。 */
    String app();

    /** 目标窗口标题关键字，规则同 {@link #app()}；留空表示不限制。 */
    String title() default "";
}
