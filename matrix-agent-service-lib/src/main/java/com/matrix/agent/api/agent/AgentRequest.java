package com.matrix.agent.api.agent;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * Agent 任务请求（V1）。服务端按契约再校验，绝不只依赖客户端校验：
 * clientRequestId 小写 UUID；clientSessionId [A-Za-z0-9._-]{1,64}；
 * text 1..4096 UTF-16 chars；languageTag BCP-47，1..35。
 * 不允许携带 actor、user、zone、vehicle state、arbitration key 或 raw prompt。
 */
public final class AgentRequest implements Parcelable {

    public static final int TEXT_MIN = 1;
    public static final int TEXT_MAX = 4096;
    /** Frozen input-source enum. New source values are append-only. */
    public static final int INPUT_TEXT = 1;
    public static final int INPUT_VOICE = 2;

    public final int schemaVersion;
    public final String clientRequestId;
    public final String clientSessionId;
    public final String text;
    /** 输入来源（文本/语音等）；枚举值随输入源定稿追加。 */
    public final int inputSource;
    public final String languageTag;

    public AgentRequest(String clientRequestId, String clientSessionId,
            String text, int inputSource, String languageTag) {
        this(ParcelSchema.CURRENT, clientRequestId, clientSessionId, text, inputSource, languageTag);
    }

    public AgentRequest(int schemaVersion, String clientRequestId, String clientSessionId,
            String text, int inputSource, String languageTag) {
        this.schemaVersion = schemaVersion;
        this.clientRequestId = clientRequestId;
        this.clientSessionId = clientSessionId;
        this.text = text;
        this.inputSource = inputSource;
        this.languageTag = languageTag;
    }

    private AgentRequest(Parcel in) {
        schemaVersion = in.readInt();
        clientRequestId = in.readString();
        clientSessionId = in.readString();
        text = in.readString();
        inputSource = in.readInt();
        languageTag = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(clientRequestId);
        dest.writeString(clientSessionId);
        dest.writeString(text);
        dest.writeInt(inputSource);
        dest.writeString(languageTag);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AgentRequest> CREATOR = new Creator<>() {
        @Override
        public AgentRequest createFromParcel(Parcel in) {
            return new AgentRequest(in);
        }

        @Override
        public AgentRequest[] newArray(int size) {
            return new AgentRequest[size];
        }
    };
}
