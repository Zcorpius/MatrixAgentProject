package com.matrix.agent.schedule.domain;

import org.json.JSONException;
import org.json.JSONObject;

/** Bounded channel receipts, separate from execution success and from unobservable user perception. */
public final class DeliveryFacts {
    private DeliveryFacts() { }
    public static String record(String previous, String channel, String status, long at, String policy) {
        if (!channel.equals("notification") && !channel.equals("speech")) throw new IllegalArgumentException("unknown delivery channel");
        if (status.length() > 120 || policy.length() > 2048) throw new IllegalArgumentException("delivery fact too large");
        try {
            var facts = new JSONObject(previous);
            var receipt = new JSONObject().put("status", status).put("observedAt", at);
            if (status.equals("DELIVERED")) receipt.put("deliveredAt", at);
            if (channel.equals("notification")) receipt.put("soundStatus", "UNKNOWN");
            if (!policy.isEmpty()) receipt.put("policy", new JSONObject(policy));
            facts.put(channel, receipt);
            return facts.toString();
        } catch (JSONException invalid) { throw new IllegalStateException("invalid delivery facts", invalid); }
    }
    public static boolean delivered(String facts, String channel) {
        try {
            var receipt = new JSONObject(facts).optJSONObject(channel);
            return receipt != null && "DELIVERED".equals(receipt.optString("status"));
        } catch (JSONException invalid) { return false; }
    }
}
