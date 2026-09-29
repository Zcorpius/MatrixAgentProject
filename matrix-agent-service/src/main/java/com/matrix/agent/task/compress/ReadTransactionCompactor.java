package com.matrix.agent.task.compress;

import com.matrix.agent.contract.AgentMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compact complete verified web reads atomically; unknown, rejected, partial and write transactions remain exact. */
final class ReadTransactionCompactor {
    private ReadTransactionCompactor() { }
    static List<AgentMessage> compact(List<AgentMessage> messages) {
        List<AgentMessage> retained = new ArrayList<>();
        List<AgentMessage.ReadReceipt> receipts = new ArrayList<>();
        for (int start = 0; start < messages.size();) {
            int end = start + 1;
            while (end < messages.size() && !ConversationCompressor.isTransactionBoundary(messages.get(end))) end++;
            List<AgentMessage> group = messages.subList(start, end);
            List<AgentMessage.ReadReceipt> candidate = receipts(group);
            if (candidate != null && receipts.size() + candidate.size() <= 40) receipts.addAll(candidate);
            else retained.addAll(group);
            start = end;
        }
        if (!receipts.isEmpty()) retained.add(0, AgentMessage.readReceipts(receipts));
        return List.copyOf(retained);
    }
    private static List<AgentMessage.ReadReceipt> receipts(List<AgentMessage> group) {
        var first = group.get(0);
        if (group.stream().allMatch(message -> !message.getReadReceipts().isEmpty())) {
            return group.stream().flatMap(message -> message.getReadReceipts().stream()).collect(java.util.stream.Collectors.toList());
        }
        if (first.getRole() != AgentMessage.Role.ASSISTANT || first.getToolCalls().isEmpty()
                || group.size() != first.getToolCalls().size() + 1) return null;
        Set<String> matched = new HashSet<>(); List<AgentMessage.ReadReceipt> result = new ArrayList<>();
        for (var call : first.getToolCalls()) {
            if (!"web.search".equals(call.getCapabilityName()) || !matched.add(call.getStepId())) return null;
            long observations = group.stream().skip(1).filter(message -> message.getRole() == AgentMessage.Role.TOOL
                    && message.isVerifiedRead() && call.getStepId().equals(message.getToolCallId())
                    && call.getCapabilityName().equals(message.getToolName())).count();
            if (observations != 1) return null;
            result.add(new AgentMessage.ReadReceipt(call.getStepId(), call.getCapabilityName(), digest(call.getArguments().toString())));
        }
        return result;
    }
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
