package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/** VAD 事件类型。 */
public enum VadEvent {
    /** 检测到语音。 */
    SPEECH,
    /** 静音。 */
    QUIET,
    /** 端点(用户停说,可取 final)。 */
    ENDPOINT
}
