package com.matrix.agent.launcher.presentation;

import android.content.Context;

import com.matrix.agent.api.debug.DebugTracePayloads;
import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.launcher.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Presentation-only compiler for the debug trace UI.
 *
 * <p>The Host records transport-level events, potentially split into multiple Binder-safe parts.
 * This class reassembles them and compiles the flat sequence into the same semantic hierarchy that
 * Operit renders from adjacent {@code think/tool/tool_result} XML nodes: thought, plan, then one
 * capability node containing policy/request/verification facts. Structured facts are read through
 * the shared {@link DebugTracePayloads} wire contract—the same keys the Host emitter writes—so a
 * payload format change is a compile-time break on both sides instead of a silent mismatch. This
 * class never creates facts and it receives only Host-redacted payloads.</p>
 */
final class DebugTraceTimeline {

    private DebugTraceTimeline() {}

    static List<Node> from(List<DebugTraceWireEvent> rawEvents) {
        LinkedHashMap<String, Event> reassembled = new LinkedHashMap<>();
        for (DebugTraceWireEvent event : rawEvents) {
            String key = event.traceId == null
                    ? event.eventSequence + ":" + event.phase : event.traceId;
            Event display = reassembled.get(key);
            if (display == null) {
                display = new Event(event.eventSequence, event.timestampMs, event.phase);
                reassembled.put(key, display);
            }
            display.parts.put(event.partIndex, event.payload == null ? "" : event.payload);
        }

        List<Node> nodes = new ArrayList<>();
        Map<String, Node> openTools = new LinkedHashMap<>();
        for (Event event : reassembled.values()) {
            String phase = event.phase == null ? "" : event.phase;
            if (DebugTraceWireEvent.PHASE_MODEL_REASONING.equals(phase)) {
                nodes.add(Node.thinking(event.payload()));
                continue;
            }
            String capability = DebugTracePayloads.wordValue(event.payload(),
                    DebugTracePayloads.KEY_CAPABILITY);
            if (capability != null) {
                Node tool = openTools.get(capability);
                if (tool == null || DebugTraceWireEvent.PHASE_POLICY_DECIDED.equals(phase)) {
                    tool = Node.tool(capability);
                    nodes.add(tool);
                    openTools.put(capability, tool);
                }
                tool.add(event);
                if (DebugTraceWireEvent.PHASE_DEVICE_VERIFIED.equals(phase)
                        || Boolean.FALSE.equals(DebugTracePayloads.flagValue(event.payload(),
                                DebugTracePayloads.KEY_ALLOWED))) {
                    openTools.remove(capability);
                }
                continue;
            }
            // ROUND_* is lifecycle plumbing, rather than an independently useful user-facing node.
            if (DebugTraceWireEvent.PHASE_MODEL_PROPOSED.equals(phase)) {
                nodes.add(Node.plan(event.payload()));
            }
        }
        return List.copyOf(nodes);
    }

    enum Kind {
        THINKING,
        TOOL,
        PLAN
    }

    static final class Node {
        final Kind kind;
        private final String capability;
        private final String text;
        private final List<Event> events = new ArrayList<>();

        private Node(Kind kind, String capability, String text) {
            this.kind = kind;
            this.capability = capability;
            this.text = text;
        }

        static Node thinking(String text) {
            return new Node(Kind.THINKING, null, text);
        }

        static Node tool(String capability) {
            return new Node(Kind.TOOL, capability, null);
        }

        static Node plan(String text) {
            return new Node(Kind.PLAN, null, text);
        }

        void add(Event event) {
            events.add(event);
        }

        String title(Context context) {
            if (kind == Kind.THINKING) {
                return context.getString(R.string.conversation_debug_trace_thinking);
            }
            if (kind == Kind.PLAN) {
                return context.getString(R.string.conversation_debug_trace_plan);
            }
            return capabilityDisplayName(capability) + " · " + toolStatus(context);
        }

        String preview(Context context) {
            if (kind == Kind.THINKING || kind == Kind.PLAN) return compact(text);
            String last = events.isEmpty() ? "" : events.get(events.size() - 1).payload();
            return toolStatus(context) + (last.isBlank() ? "" : " · " + compact(last));
        }

        String detail(Context context) {
            if (kind == Kind.THINKING || kind == Kind.PLAN) {
                return text == null ? context.getString(R.string.conversation_debug_trace_empty)
                        : text;
            }
            StringBuilder detail = new StringBuilder();
            for (Event event : events) {
                if (detail.length() > 0) detail.append('\n');
                detail.append(phaseLabel(event.phase)).append("  ").append(event.payload());
            }
            return detail.length() == 0 ? context.getString(R.string.conversation_debug_trace_empty)
                    : detail.toString();
        }

        /**
         * 状态色（资源引用）。策略拒绝恒为失败色；进入终态（DEVICE_VERIFIED）的节点
         * 只有核验通过/成功才是绿色，执行失败、读回不一致、超时与取消等一律失败色；
         * 琥珀色只表达"仍在进行"（已规划/正在调用），不得把终态失败渲染成进行中。
         */
        int statusColorRes() {
            if (hasFlag(DebugTracePayloads.KEY_ALLOWED, false)) {
                return R.color.matrix_trace_status_failed;
            }
            boolean succeeded = hasFlag(DebugTracePayloads.KEY_VERIFIED, true)
                    || hasWord(DebugTracePayloads.KEY_STATUS, "SUCCESS");
            if (succeeded) {
                return R.color.matrix_trace_status_verified;
            }
            if (hasPhase(DebugTraceWireEvent.PHASE_DEVICE_VERIFIED)) {
                return R.color.matrix_trace_status_failed;
            }
            return R.color.matrix_trace_status_pending;
        }

        private String toolStatus(Context context) {
            if (hasFlag(DebugTracePayloads.KEY_ALLOWED, false)) {
                return context.getString(R.string.conversation_debug_trace_policy_rejected);
            }
            if (hasFlag(DebugTracePayloads.KEY_VERIFIED, true)
                    || hasWord(DebugTracePayloads.KEY_STATUS, "SUCCESS")) {
                return context.getString(R.string.conversation_debug_trace_verified);
            }
            if (hasPhase(DebugTraceWireEvent.PHASE_DEVICE_VERIFIED)) {
                // 终态但未核验通过：执行失败、超时、取消、未知或读回不一致的统称，
                // 原始 status 记录在节点详情里，不在标题编造具体原因。
                return context.getString(R.string.conversation_debug_trace_failed);
            }
            if (hasFlag(DebugTracePayloads.KEY_VERIFIED, false)) {
                return context.getString(R.string.conversation_debug_trace_unverified);
            }
            if (hasPhase(DebugTraceWireEvent.PHASE_REQUEST_DELIVERED)) {
                return context.getString(R.string.conversation_debug_trace_requesting);
            }
            return context.getString(R.string.conversation_debug_trace_planned);
        }

        private boolean hasFlag(String key, boolean expected) {
            for (Event event : events) {
                Boolean flag = DebugTracePayloads.flagValue(event.payload(), key);
                if (flag != null && flag == expected) return true;
            }
            return false;
        }

        private boolean hasWord(String key, String word) {
            for (Event event : events) {
                if (word.equals(DebugTracePayloads.wordValue(event.payload(), key))) return true;
            }
            return false;
        }

        private boolean hasPhase(String phase) {
            for (Event event : events) {
                if (phase.equals(event.phase)) return true;
            }
            return false;
        }

        private static String compact(String value) {
            if (value == null) return "";
            return value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        }

        private static String capabilityDisplayName(String capability) {
            if (capability == null || capability.isBlank()) return "Capability";
            return switch (capability) {
                case "system.volume", "device.volume", "volume.set",
                        "system.media.set_volume" -> "媒体音量";
                case "system.brightness", "device.brightness", "brightness.set",
                        "system.display.set_brightness" -> "屏幕亮度";
                default -> capability;
            };
        }

        private static String phaseLabel(String phase) {
            if (DebugTraceWireEvent.PHASE_POLICY_DECIDED.equals(phase)) return "策略";
            if (DebugTraceWireEvent.PHASE_REQUEST_DELIVERED.equals(phase)) return "调用";
            if (DebugTraceWireEvent.PHASE_DEVICE_VERIFIED.equals(phase)) return "结果";
            return phase == null ? "事件" : phase;
        }
    }

    private static final class Event {
        final long sequence;
        final long timestampMs;
        final String phase;
        final TreeMap<Integer, String> parts = new TreeMap<>();

        Event(long sequence, long timestampMs, String phase) {
            this.sequence = sequence;
            this.timestampMs = timestampMs;
            this.phase = phase;
        }

        String payload() {
            StringBuilder joined = new StringBuilder();
            for (String part : parts.values()) joined.append(part);
            return joined.length() == 0 ? "(empty)" : joined.toString();
        }
    }
}
