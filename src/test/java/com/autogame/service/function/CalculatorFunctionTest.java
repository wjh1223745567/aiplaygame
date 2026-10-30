package com.autogame.service.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.autogame.support.ToolException;

class CalculatorFunctionTest {

    private final CalculatorFunction function = new CalculatorFunction();

    @Test
    void 五加五等于十() {
        CalculatorFunction.Response response = function
                .execute(new CalculatorFunction.Request(new BigDecimal("5"), new BigDecimal("5"), "add"));
        assertEquals(0, new BigDecimal("10").compareTo(response.result()));
        assertEquals("5 + 5 = 10", response.expression());
    }

    @Test
    void 其余三种运算与符号写法() {
        assertEquals(0, new BigDecimal("7").compareTo(calculate("10", "3", "-")));
        assertEquals(0, new BigDecimal("42").compareTo(calculate("6", "7", "*")));
        assertEquals(0, new BigDecimal("2.5").compareTo(calculate("10", "4", "/")));
        // op 省略时默认加法
        assertEquals(0, new BigDecimal("3").compareTo(calculate("1", "2", null)));
    }

    @Test
    void 除零报400() {
        ToolException error = assertThrows(ToolException.class, () -> calculate("1", "0", "div"));
        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("除数不能为 0"));
    }

    @Test
    void 不支持的运算报400() {
        ToolException error = assertThrows(ToolException.class, () -> calculate("1", "1", "pow"));
        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("add/sub/mul/div"));
    }

    @Test
    void 缺参数报400() {
        assertThrows(ToolException.class,
                () -> function.execute(new CalculatorFunction.Request(null, BigDecimal.ONE, "add")));
        assertThrows(ToolException.class,
                () -> function.execute(new CalculatorFunction.Request(BigDecimal.ONE, null, "add")));
    }

    @Test
    void 功能名与请求体类型符合约定() {
        assertEquals("calculator", function.name());
        assertEquals(CalculatorFunction.Request.class, function.requestType());
    }

    private BigDecimal calculate(String a, String b, String op) {
        return function.execute(new CalculatorFunction.Request(new BigDecimal(a), new BigDecimal(b), op)).result();
    }
}
