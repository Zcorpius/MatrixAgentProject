package com.matrix.agent.api.agent;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 高风险确认卡决策。confirmationId 一次性消费；不允许携带自由文本或 capability 参数。 */
public final class ConfirmationDecision implements Parcelable {

    public final int schemaVersion;
    public final String confirmationId;
    public final boolean approve;

    public ConfirmationDecision(String confirmationId, boolean approve) {
        this(ParcelSchema.CURRENT, confirmationId, approve);
    }

    public ConfirmationDecision(int schemaVersion, String confirmationId, boolean approve) {
        this.schemaVersion = schemaVersion;
        this.confirmationId = confirmationId;
        this.approve = approve;
    }

    private ConfirmationDecision(Parcel in) {
        schemaVersion = in.readInt();
        confirmationId = in.readString();
        approve = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(confirmationId);
        dest.writeByte((byte) (approve ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ConfirmationDecision> CREATOR = new Creator<>() {
        @Override
        public ConfirmationDecision createFromParcel(Parcel in) {
            return new ConfirmationDecision(in);
        }

        @Override
        public ConfirmationDecision[] newArray(int size) {
            return new ConfirmationDecision[size];
        }
    };
}
