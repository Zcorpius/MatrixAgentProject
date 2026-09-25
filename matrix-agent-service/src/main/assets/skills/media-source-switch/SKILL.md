# 在哔哩哔哩与 QQ 音乐之间切换

首期仅支持这两个应用。不要声称能发现或控制第三方播放器。用户指定其它来源，或两款应用均未播放/均在播放时，停止并解释 SOURCE_NOT_CONTROLLABLE。

1. 分别调用两款应用的 get_state。权限、超时或会话歧义使任一来源状态不可判定时停止。仅当一个来源为 PLAYING、另一个是用户指定目标且有可播放会话时继续。目标没有会话时请用户先手动准备内容；不要在原来源播放期间打开可能自动播放的目标页面。
2. 调用原来源 pause。只有 ToolResult 为 SUCCESS、verified=true 且回读 PAUSED，才调用目标 play/resume。否则停止，说明为了避免两路同时播放，没有启动目标播放。
3. 目标命令明确未下发（EXECUTION_FAILED 且 media.dispatch_state=NOT_SENT）时，可重查原来源并尝试一次恢复。目标为 VERIFICATION_FAILED 或 EXECUTION_UNKNOWN 时不自动恢复，因为目标可能稍后播放。
4. 写动作不要自动重试。正常切换控制在四次 Tool Call，失败恢复总计不超过六次。
