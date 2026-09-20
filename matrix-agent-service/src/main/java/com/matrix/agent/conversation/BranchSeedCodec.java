package com.matrix.agent.conversation;

import com.matrix.agent.task.conversation.ConversationHistorySource.HistoryEntry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 分支种子快照编解码（评估 v1.0 §4.5）：{@code HistoryEntry} 列表 ↔ JSON 列表。
 *
 * <p>快照是装配器的<b>输入形态</b>（已完成回合 fromUser/text），不是另立的上下文
 * 格式——子会话历史 = 快照 + 子自身消息，同一 {@code ConversationContextAssembler}
 * 统一按预算装配，绝不另写裁剪规则。版本 1；向后演进按 seedVersion 分支解析。</p>
 */
public final class BranchSeedCodec {

    static final int VERSION = 1;

    private BranchSeedCodec() {
    }

    /** 编码：[{u:1,t:"..."}, ...]（紧凑字段名，快照体积是车机存储预算的一部分）。 */
    public static String encode(List<HistoryEntry> entries) {
        JSONArray array = new JSONArray();
        for (HistoryEntry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("u", entry.fromUser() ? 1 : 0);
                item.put("t", entry.text());
                array.put(item);
            } catch (JSONException impossible) {
                throw new IllegalStateException("seed encode failed", impossible);
            }
        }
        return array.toString();
    }

    /** 解码：畸形输入抛 IllegalArgumentException（fail-closed，不静默截断上下文）。 */
    public static List<HistoryEntry> decode(String json) {
        List<HistoryEntry> entries = new ArrayList<>();
        JSONArray array;
        try {
            array = new JSONArray(json);
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("seed snapshot 畸形", invalid);
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException("seed snapshot 条目畸形 @" + i);
            }
            String text = item.optString("t", null);
            if (text == null) {
                throw new IllegalArgumentException("seed snapshot 缺文本 @" + i);
            }
            entries.add(new HistoryEntry(item.optInt("u", 0) == 1, text));
        }
        return entries;
    }
}
