package com.matrix.agent.data.memory;

import com.matrix.agent.data.SensitiveKeys;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** One source of truth for persisted key syntax, retrieval terms and prompt-safe key projection. */
public final class MemoryKeyCatalog {
    /** Bump when key syntax, intent aliases, or prompt projection contracts change. */
    public static final int CATALOG_VERSION = 2;
    public static final int MAX_KEY_LENGTH = 64;
    public static final String PREFERENCE_PATTERN = "[a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)*";
    public static final String SEMANTIC_PATTERN = "(?:family|allergy|work|fact)\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*";
    private static final Pattern PREFERENCE = Pattern.compile(PREFERENCE_PATTERN);
    private static final Pattern SEMANTIC = Pattern.compile(SEMANTIC_PATTERN);
    private static final Map<String, List<String>> TERMS;
    private static final Map<String, IntRange> SAFE_NUMERIC_PROJECTIONS = Map.of(
            "preferred_temperature", new IntRange(16, 30),
            "preferred_seat_level", new IntRange(0, 3),
            "preferred_media_volume", new IntRange(0, 100));
    private static final List<String> SAVE_DIRECTIVES = List.of("记住", "记下", "以后默认",
            "不要忘记", "别忘了", "默认用", "长期都用", "永远用", "remember",
            "keep in memory", "don't forget", "do not forget");
    private static final List<String> SAVE_NEGATIONS = List.of("不要记住", "别记住", "无需保存",
            "不用保存", "不要长期保存", "删除记忆", "取消记住",
            "do not remember", "don't remember", "stop remembering", "quit saving");

    static {
        Map<String, List<String>> terms = new LinkedHashMap<>();
        register(terms, "preferred_temperature", List.of("温度", "空调", "冷", "热", "度", "temperature", "climate"));
        register(terms, "preferred_seat_level", List.of("座椅", "座位", "seat"));
        register(terms, "preferred_media_volume", List.of("音量", "音乐", "volume"));
        register(terms, "home_address", List.of("家", "住址", "家庭地址", "home", "address"));
        register(terms, "work_address", List.of("公司", "单位", "工作地址", "office", "work"));
        register(terms, "common_destinations", List.of("常去", "目的地", "destination"));
        register(terms, "family", List.of("家人", "家庭", "孩子", "女儿", "儿子", "父母", "family"));
        register(terms, "allergy", List.of("过敏", "忌口", "allergy", "allergic"));
        register(terms, "work", List.of("工作", "职业", "公司", "职位", "job", "work"));
        register(terms, "fact.home_city", List.of("家乡", "城市", "哪里人", "home city"));
        register(terms, "fact.birthday", List.of("生日", "出生日期", "birthday"));
        TERMS = Collections.unmodifiableMap(terms);
        for (Map.Entry<String, List<String>> entry : terms.entrySet()) {
            if (!isPreferenceKey(entry.getKey()) || entry.getValue().isEmpty()
                    || entry.getValue().stream().anyMatch(alias -> alias == null || alias.isBlank()
                    || !alias.equals(alias.toLowerCase(Locale.ROOT)))) {
                throw new ExceptionInInitializerError("invalid memory catalog entry");
            }
        }
        for (String key : SAFE_NUMERIC_PROJECTIONS.keySet()) {
            if (!terms.containsKey(key) || isSensitive(key)) {
                throw new ExceptionInInitializerError("unsafe memory value projection");
            }
        }
    }

    private MemoryKeyCatalog() { }

    private static void register(Map<String, List<String>> terms, String key,
            List<String> aliases) {
        if (terms.putIfAbsent(key, aliases) != null) {
            throw new ExceptionInInitializerError("duplicate memory catalog key: " + key);
        }
    }

    public static boolean isPreferenceKey(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH && PREFERENCE.matcher(key).matches()
                && !key.startsWith("__");
    }

    /** Legacy arbitrary keys stay available for exact read/forget, never for new saves or prompts. */
    public static boolean isReadablePreferenceKey(String key) {
        return key != null && !key.isBlank() && key.length() <= 2048;
    }

    public static boolean isSemanticKey(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH && SEMANTIC.matcher(key).matches();
    }

    /** Historical keys remain readable by exact lookup, but unsafe keys never reach a model prompt. */
    public static String promptKey(MemoryLayer layer, String key) {
        boolean valid = layer == MemoryLayer.SEMANTIC ? isSemanticKey(key)
                : layer == MemoryLayer.PREFERENCE ? isPreferenceKey(key)
                : key != null && key.length() <= MAX_KEY_LENGTH
                && PREFERENCE.matcher(key).matches();
        return valid ? key : null;
    }

    /** Only bounded numeric preferences are exposed as values in model context. */
    public static String preferenceValueForPrompt(String key, String value) {
        if (key == null || value == null || SensitiveKeys.isPiiKey(key)) return null;
        IntRange range = SAFE_NUMERIC_PROJECTIONS.get(key);
        if (range == null) return null;
        try {
            int number = Integer.parseInt(value.trim());
            return number >= range.min && number <= range.max ? Integer.toString(number) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** Only this structured, verified session state can occupy a Working prompt slot. */
    public static String workingValueForPrompt(String key, String value) {
        if (!"last_climate_temperature".equals(key) || value == null
                || !value.matches("(?:driver|passenger):(?:1[6-9]|2[0-9]|30)")) return null;
        String[] parts = value.split(":", 2);
        return ("driver".equals(parts[0]) ? "主驾" : "副驾") + " " + parts[1] + "°C";
    }

    public static boolean isSensitive(String key) {
        return key != null && (SensitiveKeys.isPiiKey(key)
                || key.startsWith("family.") || key.startsWith("allergy.")
                || key.startsWith("fact.") || key.startsWith("work."));
    }

    /** Number of catalog terms tied to the key that appear in the current request. */
    public static int relevance(String key, String query) {
        if (key == null || query == null || query.isBlank()) return 0;
        String normalized = query.toLowerCase(Locale.ROOT);
        String namespace = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        List<String> aliases = TERMS.getOrDefault(key, TERMS.get(namespace));
        int hits = 0;
        if (aliases != null) for (String alias : aliases) {
            if (normalized.contains(alias)) { hits = 1; break; }
        }
        for (String part : key.split("[._]")) {
            if (part.length() >= 3 && normalized.contains(part)) hits++;
        }
        return hits;
    }

    public static boolean explicitDeleteIntent(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("不要忘记") || lower.contains("别忘了")
                || lower.contains("不要删除") || lower.contains("别删除")
                || lower.contains("do not forget") || lower.contains("don't forget")) return false;
        return lower.contains("忘记") || lower.contains("删除记忆") || lower.contains("删掉记忆")
                || lower.contains("取消记住") || lower.contains("forget") || lower.contains("remove memory");
    }

    /** Bind a model-proposed key to a topic explicitly present in the user's utterance. */
    public static boolean keyGroundedIn(String key, String userText) {
        return key != null && userText != null && (relevance(key, userText) > 0
                || userText.toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT)));
    }

    /** Values must originate in the same utterance; model invented personal facts are rejected. */
    public static boolean valueGroundedIn(String value, String userText) {
        if (value == null || userText == null || value.isBlank()) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return userText.toLowerCase(Locale.ROOT).contains(normalized);
    }

    public static boolean hasExplicitSaveIntent(String userText) {
        if (userText == null || userText.isBlank()) return false;
        String normalized = userText.toLowerCase(Locale.ROOT);
        for (String clause : saveClauses(normalized)) {
            if (containsSaveDirective(clause)) return true;
        }
        return false;
    }

    /** A proposed fact must be grounded in the same clause as the save request. */
    public static boolean saveAuthorized(String key, String value, String userText) {
        if (!hasExplicitSaveIntent(userText)) return false;
        for (String clause : saveClauses(userText.toLowerCase(Locale.ROOT))) {
            if (containsSaveDirective(clause) && keyGroundedIn(key, clause)
                    && valueGroundedIn(value, clause)) return true;
        }
        return false;
    }

    /** A delete target must occur in the same clause as the user's forget instruction. */
    public static boolean deleteAuthorized(String key, String userText) {
        if (!explicitDeleteIntent(userText)) return false;
        if (PreferenceReferences.isAlias(key)) {
            for (String clause : saveClauses(userText.toLowerCase(Locale.ROOT))) {
                if (explicitDeleteIntent(clause) && containsReference(clause, key)) return true;
            }
            return false;
        }
        if (!isPreferenceKey(key) && isReadablePreferenceKey(key)) {
            return userText.toLowerCase(Locale.ROOT).contains(
                    "删除记忆 key=" + key.toLowerCase(Locale.ROOT));
        }
        for (String clause : saveClauses(userText.toLowerCase(Locale.ROOT))) {
            if (explicitDeleteIntent(clause) && keyGroundedIn(key, clause)) return true;
        }
        return false;
    }

    /** Episodic deletion requires the exact reference selected by the user, in the delete clause. */
    public static boolean episodicDeleteAuthorized(String eventId, String userText) {
        if (!EpisodicFactCodec.validEventId(eventId) || userText == null) return false;
        for (String clause : saveClauses(userText.toLowerCase(Locale.ROOT))) {
            if (explicitDeleteIntent(clause) && containsReference(clause, eventId)) return true;
        }
        return false;
    }

    private static boolean containsReference(String clause, String reference) {
        return Pattern.compile("(?<![a-z0-9_:])" + Pattern.quote(reference)
                + "(?![a-z0-9_:])").matcher(clause).find();
    }

    private static boolean containsSaveDirective(String clause) {
        for (String negative : SAVE_NEGATIONS) {
            if (clause.contains(negative)) return false;
        }
        for (String directive : SAVE_DIRECTIVES) {
            if (clause.contains(directive)) return true;
        }
        return clause.contains("保存") && (clause.contains("偏好")
                || clause.contains("记忆") || clause.contains("个人设置")
                || clause.contains("保存我"));
    }

    private static String[] saveClauses(String normalized) {
        return normalized.split("[，,。.;；!?！？]|然后|顺便|另外|接着|同时|但是|但|\\bthen\\b|\\bbut\\b", -1);
    }

    private static final class IntRange {
        final int min;
        final int max;
        IntRange(int min, int max) { this.min = min; this.max = max; }
    }
}
