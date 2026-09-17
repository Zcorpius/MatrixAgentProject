package com.matrix.agent.api.agent;

import android.os.Parcel;
import android.os.Parcelable;

/** 版本协商载体：getServiceInfo() 返回。无版本交集时客户端不得继续业务调用。 */
public final class AgentServiceInfo implements Parcelable {

    /** AIDL/DTO 大版本：方法签名或语义破坏性变更时递增，客户端 major 必须完全一致。 */
    public final int contractMajor;
    public final int minClientMinor;
    public final int maxClientMinor;
    /** 特性位图（bit 定义随 feature 定稿逐位公布，未定义位必须忽略）。 */
    public final int featureFlags;
    public final String serviceVersion;
    public final String servicePackage;
    /** CI 由 AIDL/DTO 定义生成的摘要，用于阻止 major 相同、接口产物不一致的安装组合。 */
    public final String contractHash;

    public AgentServiceInfo(int contractMajor, int minClientMinor, int maxClientMinor,
            int featureFlags, String serviceVersion, String servicePackage, String contractHash) {
        this.contractMajor = contractMajor;
        this.minClientMinor = minClientMinor;
        this.maxClientMinor = maxClientMinor;
        this.featureFlags = featureFlags;
        this.serviceVersion = serviceVersion;
        this.servicePackage = servicePackage;
        this.contractHash = contractHash;
    }

    private AgentServiceInfo(Parcel in) {
        contractMajor = in.readInt();
        minClientMinor = in.readInt();
        maxClientMinor = in.readInt();
        featureFlags = in.readInt();
        serviceVersion = in.readString();
        servicePackage = in.readString();
        contractHash = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(contractMajor);
        dest.writeInt(minClientMinor);
        dest.writeInt(maxClientMinor);
        dest.writeInt(featureFlags);
        dest.writeString(serviceVersion);
        dest.writeString(servicePackage);
        dest.writeString(contractHash);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AgentServiceInfo> CREATOR = new Creator<>() {
        @Override
        public AgentServiceInfo createFromParcel(Parcel in) {
            return new AgentServiceInfo(in);
        }

        @Override
        public AgentServiceInfo[] newArray(int size) {
            return new AgentServiceInfo[size];
        }
    };
}
