package com.greensamcli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GreensamCli} 启动参数解析测试：--yolo 全放行开关。
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
class GreensamCliTest {

    @Test
    void hasYolo_命中() {
        assertTrue(GreensamCli.hasYolo(new String[]{"--yolo"}));
        assertTrue(GreensamCli.hasYolo(new String[]{"--verbose", "--yolo"}));
    }

    @Test
    void hasYolo_未命中或无参数() {
        assertFalse(GreensamCli.hasYolo(null));
        assertFalse(GreensamCli.hasYolo(new String[0]));
        assertFalse(GreensamCli.hasYolo(new String[]{"--verbose"}));
        assertFalse(GreensamCli.hasYolo(new String[]{"--YOLO"}), "区分大小写，避免误碰");
    }
}
