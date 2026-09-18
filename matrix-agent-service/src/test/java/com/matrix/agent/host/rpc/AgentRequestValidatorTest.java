package com.matrix.agent.host.rpc;
import com.matrix.agent.host.rpc.*;
import com.matrix.agent.task.steer.*;


import static org.junit.Assert.assertThrows;

import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.ConfirmationDecision;
import com.matrix.agent.api.agent.SteerRequest;
import com.matrix.agent.api.common.ParcelSchema;

import org.junit.Test;

import java.util.UUID;

public final class AgentRequestValidatorTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";

    @Test public void acceptsContractBoundaries() {
        AgentRequestValidator.validateSubmit(new AgentRequest(ID, "launcher-1", "x",
                AgentRequest.INPUT_TEXT, "zh-CN"));
        AgentRequestValidator.validateSteer(new SteerRequest(
                SteerRequest.TYPE_REPROMPT, "补充"));
        AgentRequestValidator.validateSteer(new SteerRequest(SteerRequest.TYPE_DEFER, null));
        AgentRequestValidator.validateConfirmation(new ConfirmationDecision(ID, true));
    }

    @Test public void rejectsUnknownSchemaAndEnums() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequestValidator.validateSubmit(new AgentRequest(ParcelSchema.CURRENT + 1,
                        ID, "session", "x", 99, "zh-CN")));
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequestValidator.validateSteer(new SteerRequest(
                        ParcelSchema.CURRENT + 1, SteerRequest.TYPE_DEFER, null)));
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequestValidator.validateSteer(new SteerRequest(99, null)));
    }

    @Test public void rejectsMalformedIdentityAndLanguage() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequestValidator.validateSubmit(new AgentRequest(
                        UUID.randomUUID().toString().toUpperCase(), "session", "x",
                        AgentRequest.INPUT_TEXT, "zh-CN")));
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequestValidator.validateSubmit(new AgentRequest(ID, "bad session", "x",
                        AgentRequest.INPUT_TEXT, "not_a_language")));
    }

    @Test public void rejectsUtf8OverflowEvenWithinUtf16Limit() {
        String fourByteCodePoint = new String(Character.toChars(0x1F680));
        String oversized = fourByteCodePoint.repeat(4096);
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequestValidator.validateSubmit(new AgentRequest(ID, "session", oversized,
                        AgentRequest.INPUT_TEXT, "zh-CN")));
    }

    @Test public void rejectsSteerShapeViolations() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequestValidator.validateSteer(new SteerRequest(
                        SteerRequest.TYPE_REPROMPT, "")));
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequestValidator.validateSteer(new SteerRequest(
                        SteerRequest.TYPE_DEFER, "must-be-null")));
        assertThrows(IllegalArgumentException.class, () ->
                AgentRequestValidator.validateConfirmation(new ConfirmationDecision("bad", true)));
    }

    @Test public void crossDomainIdsAreCanonicalLowercaseUuids() {
        HostInputValidator.requireOperationId(ID);
        HostInputValidator.requireSessionId(ID);
        assertThrows(IllegalArgumentException.class,
                () -> HostInputValidator.requireOperationId(ID.toUpperCase()));
        assertThrows(IllegalArgumentException.class,
                () -> HostInputValidator.requireOperationId("x".repeat(10_000)));
        assertThrows(IllegalArgumentException.class,
                () -> HostInputValidator.requireSessionId("not-a-session"));
    }
}
