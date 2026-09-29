package com.matrix.agent.evaluation;

import okhttp3.Interceptor;
import okhttp3.Response;
import okio.Buffer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Capture only decoding knobs and numeric usage; never persist headers, endpoints, or request text. */
final class HttpMeasurements implements Interceptor {
    private final List<JSONObject> requests = new ArrayList<>();

    HttpMeasurements() { }

    HttpMeasurements(JSONArray previous) throws org.json.JSONException {
        for (int i = 0; i < previous.length(); i++) {
            requests.add(new JSONObject(previous.getJSONObject(i).toString()));
        }
    }

    @Override public Response intercept(Chain chain) throws IOException {
        try { return measure(chain); }
        catch (org.json.JSONException invalid) { throw new IOException("invalid evaluation measurement JSON", invalid); }
    }

    private Response measure(Chain chain) throws IOException, org.json.JSONException {
        JSONObject sample = new JSONObject();
        var request = chain.request();
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            String bodyText = buffer.readUtf8();
            JSONObject body = new JSONObject(bodyText);
            sample.put("requestBodySha256", Provenance.sha256(bodyText));
            for (String key : List.of("temperature", "top_p", "top_k", "seed", "max_tokens", "max_completion_tokens")) {
                Object value = body.opt(key);
                sample.put(key, value instanceof Number ? value : "provider_default");
            }
            for (String container : List.of("options", "generationConfig")) {
                JSONObject nested = body.optJSONObject(container);
                if (nested != null) for (String key : List.of("temperature", "top_p", "topP", "topK", "seed", "num_predict", "maxOutputTokens")) {
                    Object value = nested.opt(key);
                    if (value instanceof Number) sample.put(container + "." + key, value);
                }
            }
        }
        sample.put("usage", JSONObject.NULL);
        synchronized (this) { requests.add(sample); }
        long start = System.nanoTime();
        Response response = chain.proceed(request);
        synchronized (this) {
            sample.put("httpStatus", response.code());
            sample.put("responseHeadersMillis", (System.nanoTime() - start) / 1_000_000L);
        }
        // Usage is optional and provider-specific. An absent/oversized/unparseable body remains unknown.
        try {
            JSONObject body = new JSONObject(response.peekBody(1_048_576).string());
            JSONObject usage = body.optJSONObject("usage");
            if (usage == null) usage = body.optJSONObject("usageMetadata");
            JSONObject numbers = new JSONObject();
            if (usage != null) for (String key : JsonValues.keys(usage)) {
                if (usage.opt(key) instanceof Number number) numbers.put(key, number);
            }
            for (String key : List.of("prompt_eval_count", "eval_count")) {
                if (body.opt(key) instanceof Number number) numbers.put(key, number);
            }
            if (numbers.length() > 0) synchronized (this) { sample.put("usage", numbers); }
        } catch (org.json.JSONException ignored) { /* Unknown usage is not a model/protocol failure. */ }
        return response;
    }

    synchronized JSONArray snapshot() throws org.json.JSONException {
        JSONArray result = new JSONArray();
        for (JSONObject item : requests) result.put(new JSONObject(item.toString()));
        return result;
    }

    synchronized int count() { return requests.size(); }

    synchronized boolean hasStatusSince(int first, int status) {
        for (int i = first; i < requests.size(); i++) {
            if (requests.get(i).optInt("httpStatus", -1) == status) return true;
        }
        return false;
    }
}
