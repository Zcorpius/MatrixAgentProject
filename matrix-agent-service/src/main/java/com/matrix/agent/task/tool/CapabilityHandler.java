package com.matrix.agent.task.tool;

import com.matrix.agent.demo.MockCapabilityProvider;

/**
 * 单个 capability 的执行器。
 *
 * <p>替换 MockCapabilityProvider 既有 7 个 if-else 分支——每个 capability 一个 handler 实现,
 * 在 Provider 构造期注册到 {@code Map<String, CapabilityHandler>} 路由表,
 * Provider.execute 通过 capability name 路由。
 *
 * <p>handler 全权负责:解析 args、命令下发(commandAndApply)、verify 计算、message 拼装、
 * 返回 {@link ToolResult}。
 */
public interface CapabilityHandler {
    ToolResult execute(ProviderContext ctx);
}
