package dev.minecontrol;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Presentation-only localization; protocol values and macro output stay unchanged. */
public final class UiText {
    private static final Map<String, String> ENGLISH = load("en_us");
    private static final Map<String, String> CHINESE = load("zh_cn");
    private static volatile Function<String, String> resolver = key -> ENGLISH.getOrDefault(key, key);

    private UiText() {}

    public static void setResolver(Function<String, String> gameTranslations) { resolver = gameTranslations; }
    public static String get(String key) { return resolver.apply(key); }
    public static String format(String key, Object... args) {
        return String.format(java.util.Locale.ROOT, get(key), args);
    }

    /** Translate known manager states/errors without replacing text inside filenames or program output. */
    public static String status(String value) {
        if (value == null) return "";
        for (var entry : CHINESE.entrySet()) {
            if (!entry.getKey().startsWith("minecontrol.status.")) continue;
            if (entry.getValue().equals(value)) return get(entry.getKey());
        }
        for (var entry : CHINESE.entrySet()) {
            if (!entry.getKey().startsWith("minecontrol.message.")) continue;
            if (value.startsWith(entry.getValue()))
                return get(entry.getKey()) + value.substring(entry.getValue().length());
        }
        var active = java.util.regex.Pattern.compile("^(\\d+) 个宏 · (.*)$").matcher(value);
        if (active.matches()) return format("minecontrol.macros.active_count", active.group(1), active.group(2));
        return value;
    }

    private static Map<String, String> load(String language) {
        try (var input = UiText.class.getResourceAsStream("/assets/minecontrol/lang/" + language + ".json")) {
            if (input == null) throw new IllegalStateException("Missing MineControl translations: " + language);
            var values = new LinkedHashMap<String, String>();
            JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject()
                    .entrySet().forEach(entry -> values.put(entry.getKey(), entry.getValue().getAsString()));
            return values;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read MineControl translations", error);
        }
    }
}
