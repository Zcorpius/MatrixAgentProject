package com.matrix.agent.host.rpc;

import static org.junit.Assert.assertEquals;

import com.matrix.agent.api.common.MatrixErrorCode;

import org.junit.Test;

/** Public error projection must preserve the actionable TTS provisioning diagnosis. */
public final class VoiceServiceStubTest {
    @Test public void ttsInitializationFailureIsNotFlattenedIntoGenericTaskFailure() {
        assertEquals(MatrixErrorCode.VOICE_OUTPUT_UNAVAILABLE,
                VoiceServiceStub.publicVoiceFailureCode("TTS_INIT_FAILED"));
        assertEquals(MatrixErrorCode.TASK_FAILED,
                VoiceServiceStub.publicVoiceFailureCode("AGENT_RUNNER_FAILED"));
    }
}
