package com.autogame.aop;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import com.autogame.platform.WindowInfo;
import com.autogame.service.AppPreflight;
import com.autogame.support.ToolException;

/**
 * 把 {@link RequiresApp} 变成「执行前保障」：切面在方法体之前跑完
 * 启动 → 置顶 → 接管检测，任一步不满足就抛异常，方法体根本不会执行。
 *
 * <p>为什么放切面而不是写在每个实现里：这三步是横切关注点，写在实现里既重复，
 * 又容易被漏掉；声明一行注解就够了，实现类回到「只关心自己做什么」。
 */
@Aspect
@Component
public class AppGuardAspect {

    private static final Logger log = LoggerFactory.getLogger(AppGuardAspect.class);

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();
    private final AppPreflight appPreflight;

    public AppGuardAspect(AppPreflight appPreflight) {
        this.appPreflight = appPreflight;
    }

    @Around("@annotation(com.autogame.aop.RequiresApp)")
    public Object ensureAppReady(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = targetMethod(joinPoint);
        RequiresApp annotation = AnnotatedElementUtils.findMergedAnnotation(method, RequiresApp.class);
        if (annotation == null) {
            return joinPoint.proceed();
        }
        String app = resolve(joinPoint, method, annotation.app());
        if (app == null || app.isBlank()) {
            // 没给出目标应用，说明与桌面无关（如纯计算），不做前置保障
            log.debug("{} 未指定目标应用，跳过前置保障", method.getName());
            return joinPoint.proceed();
        }
        String title = resolve(joinPoint, method, annotation.title());
        WindowInfo window = appPreflight.ensureReady(app, title);
        log.debug("{} 前置保障通过：{}", method.getName(), window);
        return joinPoint.proceed();
    }

    /** 注解可能写在接口方法上，要落到真正被调用的实现方法。 */
    private static Method targetMethod(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Class<?> targetClass = joinPoint.getTarget() == null
                ? signature.getDeclaringType()
                : joinPoint.getTarget().getClass();
        return AopUtils.getMostSpecificMethod(signature.getMethod(), targetClass);
    }

    /**
     * 求值：不以 {@code #} 开头就当字面量，否则按 SpEL 解析，可引用方法参数名（如 {@code #request}）。
     *
     * @return 求值结果；表达式为空白时返回 null，表示不限制
     */
    private String resolve(ProceedingJoinPoint joinPoint, Method method, String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        String trimmed = expression.trim();
        if (!trimmed.startsWith("#")) {
            return trimmed;
        }
        StandardEvaluationContext context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(), method, joinPoint.getArgs(), parameterNames);
        try {
            Object value = parser.parseExpression(trimmed).getValue(context);
            return value == null ? null : value.toString().trim();
        } catch (RuntimeException e) {
            throw ToolException.badRequest("@RequiresApp 表达式求值失败：" + trimmed + "（" + firstLine(e) + "）");
        }
    }

    private static String firstLine(RuntimeException e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.lines().findFirst().orElse("");
    }
}
