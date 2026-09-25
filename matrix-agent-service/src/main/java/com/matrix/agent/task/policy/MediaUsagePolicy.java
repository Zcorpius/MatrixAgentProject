package com.matrix.agent.task.policy;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.RuntimeProfile;
import com.matrix.agent.task.capability.CapabilityDefinition;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.vehicle.VehicleStatePredicate;

import java.util.Map;

/** Explicit classification of video writes, independent of static vehicle predicates. */
public final class MediaUsagePolicy {
    private enum MediaClass { VIDEO_RESTRICTED, SAFE_WHILE_MOVING }

    private static final Map<String, MediaClass> VIDEO_WRITES = Map.of(
            "media.bilibili.open_video", MediaClass.VIDEO_RESTRICTED,
            "media.bilibili.search_videos", MediaClass.VIDEO_RESTRICTED,
            "media.bilibili.open_search_result", MediaClass.VIDEO_RESTRICTED,
            "media.bilibili.resume", MediaClass.VIDEO_RESTRICTED,
            "media.bilibili.pause", MediaClass.SAFE_WHILE_MOVING);

    public MediaUsagePolicy(CapabilityRegistry registry) {
        for (CapabilityDefinition definition : registry.snapshot().values()) {
            if (definition.getName().startsWith("media.bilibili.")
                    && definition.isWriteOperation()
                    && !VIDEO_WRITES.containsKey(definition.getName())) {
                throw new IllegalStateException("未分类的视频写能力: " + definition.getName());
            }
        }
    }

    /** Returns null when this capability has no additional media restriction. */
    public PolicyDecision evaluate(AgentRequest request, String capability) {
        if (VIDEO_WRITES.get(capability) != MediaClass.VIDEO_RESTRICTED) return null;
        RuntimeProfile profile = request.getRuntimeProfile();
        if (profile == RuntimeProfile.PHONE) return null;
        if (profile == RuntimeProfile.UNKNOWN) {
            return PolicyDecision.denyCapability("运行配置未知，禁止打开或恢复视频");
        }
        return VehicleStatePredicate.PARKED_ONLY.matches(request.getCurrentVehicleState())
                ? null : PolicyDecision.denyCapability("车机未确认停车，禁止打开或恢复视频");
    }
}
