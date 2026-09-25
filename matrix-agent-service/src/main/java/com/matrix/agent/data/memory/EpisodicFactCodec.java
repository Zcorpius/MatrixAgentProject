package com.matrix.agent.data.memory;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.VehicleZone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.regex.Pattern;

/** The complete persisted vocabulary for verified episodic details. */
public final class EpisodicFactCodec {
    public static final int MAX_FACTS = 3;
    private static final Pattern EVENT_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern DESTINATION = Pattern.compile(
            "[\\p{IsHan}\\p{L}\\p{N} .,_#\u00b7\u2014-]{1,80}");
    private static final Set<String> KINDS = Set.of("navigation_destination",
            "climate_temperature", "seat_heating_level", "media_volume",
            "display_brightness");

    private EpisodicFactCodec() { }

    public static boolean validEventId(String value) {
        return value != null && EVENT_ID.matcher(value).matches();
    }

    /** Stable for one execution, distinct when a request ID is retried in another session/time. */
    public static String eventIdFor(AgentRequest request, long startedAtMillis) {
        if (request == null || request.getActor() == null) return null;
        String zone = request.getOccupantZone() == null ? VehicleZone.GLOBAL.wireValue()
                : request.getOccupantZone().wireValue();
        String identity = ActorUsers.userIdOf(request) + '\u0000' + zone + '\u0000'
                + request.getSessionId() + '\u0000' + request.getRequestId() + '\u0000'
                + startedAtMillis;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[32];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < 16; i++) {
                hex[i * 2] = alphabet[(digest[i] >>> 4) & 0x0f];
                hex[i * 2 + 1] = alphabet[digest[i] & 0x0f];
            }
            return new String(hex);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static boolean validDestination(String value) {
        return value != null && DESTINATION.matcher(value).matches() && !value.isBlank();
    }

    public static boolean validFacts(JSONArray facts) {
        if (facts == null || facts.length() > MAX_FACTS) return false;
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.optJSONObject(i);
            if (fact == null || fact.length() != 3
                    || !fact.has("kind") || !fact.has("zone") || !fact.has("value")) return false;
            String kind = fact.optString("kind", "");
            String zone = fact.optString("zone", "");
            if (!KINDS.contains(kind)) return false;
            if (!("driver".equals(zone) || "passenger".equals(zone)
                    || "global".equals(zone))) return false;
            if ("navigation_destination".equals(kind)) {
                if (!"global".equals(zone)
                        || !validDestination(fact.optString("value", null))) return false;
            } else {
                boolean global = "media_volume".equals(kind) || "display_brightness".equals(kind);
                if ((global != "global".equals(zone))
                        || !(fact.opt("value") instanceof Number)) return false;
                double number = fact.optDouble("value", Double.NaN);
                if (!Double.isFinite(number) || number != Math.rint(number)) return false;
                if ("climate_temperature".equals(kind) && (number < 16 || number > 30)) return false;
                if ("seat_heating_level".equals(kind) && (number < 0 || number > 3)) return false;
                if (global && (number < 0 || number > 100)) return false;
            }
        }
        return true;
    }

    public static boolean validFactsForCapabilities(JSONArray facts, JSONArray capabilities) {
        if (!validFacts(facts) || capabilities == null) return false;
        for (int i = 0; i < facts.length(); i++) {
            String kind = facts.optJSONObject(i).optString("kind");
            String required = "navigation_destination".equals(kind) ? "navigation.start_route"
                    : "climate_temperature".equals(kind) ? "vehicle.climate.set_temperature"
                    : "seat_heating_level".equals(kind) ? "vehicle.seat.set_heating_level"
                    : "media_volume".equals(kind) ? "system.media.set_volume"
                    : "system.display.set_brightness";
            boolean matched = false;
            for (int j = 0; j < capabilities.length(); j++) {
                if (required.equals(capabilities.optString(j))) { matched = true; break; }
            }
            if (!matched) return false;
        }
        return true;
    }

    /** Rebuild from validated fields so unknown JSON keys never reach the model. */
    public static JSONArray project(JSONArray facts) {
        JSONArray safe = new JSONArray();
        if (!validFacts(facts)) return safe;
        for (int i = 0; i < facts.length(); i++) {
            JSONObject input = facts.optJSONObject(i);
            JSONObject output = new JSONObject();
            try {
                output.put("kind", input.getString("kind"));
                output.put("zone", input.getString("zone"));
                output.put("value", input.get("value"));
            } catch (Exception invalid) { return new JSONArray(); }
            safe.put(output);
        }
        return safe;
    }
}
