package com.matrix.agent.api.agent;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 运行中任务控制。仅允许 REPROMPT（补充用户输入，1..512 chars）与 DEFER（不得携带文本）。
 * 不开放 FORCE_TOOL（仅进程内 debug/test），不允许 capability name、arguments 或任意 JSON。
 */
public final class SteerRequest implements Parcelable {

    public static final int TYPE_REPROMPT = 1;
    public static final int TYPE_DEFER = 2;
    public static final int REPROMPT_MIN = 1;
    public static final int REPROMPT_MAX = 512;

    public final int schemaVersion;
    public final int type;
    /** REPROMPT 必填；DEFER 必须为 null。 */
    public final String text;

    public SteerRequest(int type, String text) {
        this(ParcelSchema.CURRENT, type, text);
    }

    public SteerRequest(int schemaVersion, int type, String text) {
        this.schemaVersion = schemaVersion;
        this.type = type;
        this.text = text;
    }

    private SteerRequest(Parcel in) {
        schemaVersion = in.readInt();
        type = in.readInt();
        text = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(type);
        dest.writeString(text);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<SteerRequest> CREATOR = new Creator<>() {
        @Override
        public SteerRequest createFromParcel(Parcel in) {
            return new SteerRequest(in);
        }

        @Override
        public SteerRequest[] newArray(int size) {
            return new SteerRequest[size];
        }
    };
}
