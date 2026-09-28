package com.matrix.agent.evaluation;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Uses the org.json API common to Android's compile stubs and the JVM test implementation. */
final class JsonValues {
    private JsonValues() {}
    static List<Object> values(JSONArray array) {
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.opt(i));
        return result;
    }
    static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>();
        object.keys().forEachRemaining(result::add);
        return result;
    }
}
