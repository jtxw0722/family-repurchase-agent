package com.jtxw.familyagent.application.command;

import java.nio.file.Path;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 15:58:00
 * @Description: 本地订单文件导入命令，用于承载 import-file 用例的输入参数。
 *
 * @param file          本地订单文件路径，不允许为空
 * @param ownerOverride 导入时指定的订单归属人；为空时由导入器从文件字段或文件名识别
 */
public record ImportFileCommand(
        Path file,
        String ownerOverride
) {
}
