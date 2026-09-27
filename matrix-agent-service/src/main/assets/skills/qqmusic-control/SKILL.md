# 控制 QQ 音乐

1. 先用 media.qqmusic.get_state 确认唯一活动会话及支持的动作。
2. 根据用户明确请求调用 play、pause、next、previous 或 seek。写 Tool 已包含回读，不必为每个动作额外调用 get_state。
3. 没有活动会话时可以打开 QQ 音乐，但不得向其他播放器发送全局媒体键，也不要随机选择歌曲。
4. next/previous 不可自动重发。VERIFICATION_FAILED 表示未能证明曲目已变化；EXECUTION_UNKNOWN 表示命令可能稍后生效。
5. 如果用户指定歌手和具体歌曲，用 media.qqmusic.search_songs 查询。若观察值含 media.confirmable_index，说明歌名和歌手在候选列表中唯一匹配：向用户说明该编号、歌名和歌手/专辑，直接询问“是否播放这首？”，然后停止本轮。用户下一轮回复“是”“确认”“播放吧”等肯定答复时，调用 media.qqmusic.play_search_result，index 必须等于 media.confirmable_index；否定答复时不调用播放。若无唯一匹配，则逐项展示编号、歌名及歌手/专辑，请用户选歌名或编号。搜索不会播放，用户未确认前不得调用播放能力。
6. media.qqmusic.play 只能恢复已有队列，不能代替按条件选曲。搜索上下文过期、界面结构变化、候选项变化或辅助功能未启用时，要明确说明并停止；不可猜测或改播当前队列。
