# 打开哔哩哔哩视频

支持明确 BV 号的视频入口，以及按标题搜索、列出候选、等待用户选择。不要根据标题、搜索词或记忆猜测 BV 号。

1. 用户给出 BV 号时调用 media.bilibili.open_video；其成功仅表示启动请求已提交。
2. 用户给出标题时调用 media.bilibili.search_videos，向用户列出带序号的候选；搜索期间不打开结果。
3. 只有用户新一轮明确指定序号或完整标题，才调用 media.bilibili.open_search_result。含糊的“是”不能用于多项候选的选择。
4. 打开结果成功只表示对应条目已点击，不表示已开始播放。番剧可能还需选集。必要时通过 media.bilibili.get_state 查询播放状态。
5. 车机视频限制由 PolicyEngine 判定；被拒绝后不要换参数重试。
