package com.autogame.service.function;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.autogame.support.ToolException;

/**
 * 示例功能：四则运算（如 5 + 5 = 10），用来示范功能接口怎么落地。
 *
 * <pre>
 * POST /api/function/calculator
 * {"a":5,"b":5,"op":"add"}  →  {"a":5,"op":"add","b":5,"result":10,"expression":"5 + 5 = 10"}
 * </pre>
 */
@Component
public class CalculatorFunction implements AppFunction<CalculatorFunction.Request, CalculatorFunction.Response> {

    /** 除法保留位数，避免 1/3 抛 ArithmeticException。 */
    private static final int DIVIDE_SCALE = 10;

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public Class<Request> requestType() {
        return Request.class;
    }

    @Override
    public Response execute(Request request) {
        if (request == null) {
            throw ToolException.badRequest("请求体不能为空");
        }
        BigDecimal left = requireNumber(request.a(), "a");
        BigDecimal right = requireNumber(request.b(), "b");
        String op = normalizeOp(request.op());
        BigDecimal result = switch (op) {
            case "add" -> left.add(right);
            case "sub" -> left.subtract(right);
            case "mul" -> left.multiply(right);
            case "div" -> {
                if (right.signum() == 0) {
                    throw ToolException.badRequest("除数不能为 0");
                }
                yield left.divide(right, DIVIDE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            default -> throw ToolException.badRequest("不支持的运算：" + op + "，可用：add/sub/mul/div");
        };
        String expression = plain(left) + " " + symbol(op) + " " + plain(right) + " = " + plain(result);
        return new Response(left, op, right, result, expression);
    }

    private static BigDecimal requireNumber(BigDecimal value, String field) {
        if (value == null) {
            throw ToolException.badRequest(field + " 不能为空");
        }
        return value;
    }

    /** 允许 add/+、sub/-、mul/*、div// 这类写法，统一成单词。 */
    private static String normalizeOp(String raw) {
        String op = raw == null || raw.isBlank() ? "add" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (op) {
            case "+" -> "add";
            case "-" -> "sub";
            case "*", "x", "×" -> "mul";
            case "/", "÷" -> "div";
            default -> op;
        };
    }

    private static String symbol(String op) {
        return switch (op) {
            case "add" -> "+";
            case "sub" -> "-";
            case "mul" -> "*";
            case "div" -> "/";
            default -> op;
        };
    }

    /** 去掉 BigDecimal 的 .0 尾巴，让 10 显示成 10 而不是 1E+1。 */
    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /**
     * @param a  左操作数
     * @param b  右操作数
     * @param op 运算：add/sub/mul/div，也接受 + - * /，默认 add
     */
    public record Request(BigDecimal a, BigDecimal b, String op) {
    }

    /**
     * @param result     计算结果
     * @param expression 可读算式，如 "5 + 5 = 10"
     */
    public record Response(BigDecimal a, String op, BigDecimal b, BigDecimal result, String expression) {
    }
}
