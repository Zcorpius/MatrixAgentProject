package com.matrix.agent.api.debug;

import com.matrix.agent.api.debug.DebugTraceWireEvent;

/** 调试轨迹推送（oneway；评估 v1.0 §4.3 契约 3）。 */
oneway interface IDebugTraceCallback {
    void onDebugTraceEvent(in DebugTraceWireEvent event);
}
