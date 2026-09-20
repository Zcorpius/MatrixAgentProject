package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 能力事实轨迹条目（v5 追加，评估 v1.0 §4.3）：Host 写时净化的 capability 执行投影。
 * 两段式核验固定分离——UI 渲染“请求 X → 核验为 Y”，不一致以 Y（readback）为准。
 */
public final class CapabilityTraceEntry implements Parcelable {

    public final String capabilityId;
    public final String friendlyName;
    /** SUCCESS / FAILED / REJECTED / UNKNOWN。 */
    public final String outcome;
    /** 可空：白名单裁剪后的请求参数摘要。 */
    public final String requestedDisplay;
    /** 可空：白名单裁剪后的核验值（readback）。 */
    public final String verifiedDisplay;
    /** VERIFIED / MISMATCH / UNAVAILABLE / UNKNOWN。 */
    public final String verificationState;

    public CapabilityTraceEntry(String capabilityId, String friendlyName, String outcome,
            String requestedDisplay, String verifiedDisplay, String verificationState) {
        this.capabilityId = capabilityId;
        this.friendlyName = friendlyName;
        this.outcome = outcome;
        this.requestedDisplay = requestedDisplay;
        this.verifiedDisplay = verifiedDisplay;
        this.verificationState = verificationState;
    }

    private CapabilityTraceEntry(Parcel in) {
        capabilityId = in.readString();
        friendlyName = in.readString();
        outcome = in.readString();
        requestedDisplay = in.readString();
        verifiedDisplay = in.readString();
        verificationState = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(capabilityId);
        dest.writeString(friendlyName);
        dest.writeString(outcome);
        dest.writeString(requestedDisplay);
        dest.writeString(verifiedDisplay);
        dest.writeString(verificationState);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<CapabilityTraceEntry> CREATOR = new Creator<>() {
        @Override public CapabilityTraceEntry createFromParcel(Parcel in) {
            return new CapabilityTraceEntry(in);
        }
        @Override public CapabilityTraceEntry[] newArray(int size) {
            return new CapabilityTraceEntry[size];
        }
    };
}
