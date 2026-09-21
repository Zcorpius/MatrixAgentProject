package com.matrix.agent.launcher.presentation;

import android.content.Context;

import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.launcher.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Presentation-only compiler for the debug trace UI.
 *
 * <p>The Host records transport-level events, potentially split into multiple Binder-safe parts.
 * This class reassembles them and compiles the flat sequence into the same semantic hierarchy that
 * Operit renders from adjacent {@code think/tool/tool_result} XML nodes: thought, plan, then one
 * capability node containing policy/request/verification facts. It never creates facts and it
 * receives only Host-redacted payloads.</p>
 */
final class DebugTraceTimeline {

    private static final Pattern CAPABILITY_PATTERN =
            Pattern.compile("(?:^|\\s)cap=([^\\s]+)");

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
            if ("MODEL_REASONING".equals(phase)) {
                nodes.add(Node.thinking(event.payload()));
                continue;
            }
            String capability = capabilityFrom(event.payload());
            if (capability != null) {
                Node tool = openTools.get(capability);
                if (tool == null || "POLICY_DECIDED".equals(phase)) {
                    tool = Node.tool(capability);
                    nodes.add(tool);
                    openTools.put(capability, tool);
                }
                tool.add(event);
                if ("DEVICE_VERIFIED".equals(phase) || isPolicyRejected(event.payload())) {
                    openTools.remove(capability);
                }
                continue;
            }
            // ROUND_* is lifecycle plumbing, rather than an independently useful user-facing node.
            if ("MODEL_PROPOSED".equals(phase)) {
                nodes.add(Node.plan(event.payload()));
            }
        }
        return List.copyOf(nodes);
    }

    private static String capabilityFrom(String payload) {
        if (payload == null) return null;
        Matcher matcher = CAPABILITY_PATTERN.matcher(payload);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isPolicyRejected(String payload) {
        return payload != null && payload.contains("allowed=false");
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

        int statusColor() {
            String joined = statusSource();
            if (joined.contains("allowed=false") || joined.contains("status=FAILED")) {
                return 0xFFB65B5B;
            }
            if (joined.contains("verified=true") || joined.contains("status=SUCCESS")) {
                return 0xFF3C8A69;
            }
            return 0xFFB5813E;
        }

        private String toolStatus(Context context) {
            String joined = statusSource();
            if (joined.contains("allowed=false")) {
                return context.getString(R.string.conversation_debug_trace_policy_rejected);
            }
            if (joined.contains("verified=true") || joined.contains("status=SUCCESS")) {
                return context.getString(R.string.conversation_debug_trace_verified);
            }
            if (joined.contains("DEVICE_VERIFIED") || joined.contains("verified=false")) {
                return context.getString(R.string.conversation_debug_trace_unverified);
            }
            if (joined.contains("REQUEST_DELIVERED")) {
                return context.getString(R.string.conversation_debug_trace_requesting);
            }
            return context.getString(R.string.conversation_debug_trace_planned);
        }

        private String statusSource() {
            StringBuilder joined = new StringBuilder();
            for (Event event : events) {
                joined.append(event.phase).append(' ').append(event.payload()).append('\n');
            }
            return joined.toString();
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
            if ("POLICY_DECIDED".equals(phase)) return "策略";
            if ("REQUEST_DELIVERED".equals(phase)) return "调用";
            if ("DEVICE_VERIFIED".equals(phase)) return "结果";
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
