# matrix-agent-service-lib consumer ProGuard 规则
# SDK 的公开 ABI（AIDL 生成类、Parcelable DTO、门面与 Manager、listener）不得被混淆或裁剪。

# AIDL 生成类：Stub/Proxy/接口方法签名跨进程反射使用
-keep class com.matrix.agent.api.** { *; }

# Parcelable DTO：CREATOR 反射构造，字段被 Parcel 序列化
-keepclassmembers class com.matrix.agent.api.** {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 客户端门面与 Manager：应用直接引用的公开面
-keep class com.matrix.agent.client.** { *; }

# listener/callback 由 SDK 内部经接口调用，防裁剪
-keep interface com.matrix.agent.api.agent.IAgentTaskCallback { *; }
-keep interface com.matrix.agent.api.model.IModelCallback { *; }
-keep interface com.matrix.agent.api.voice.IVoiceCallback { *; }
-keep interface com.matrix.agent.api.voice.IVoiceSessionCallback { *; }
-keep interface com.matrix.agent.client.ServiceLifecycleListener { *; }
