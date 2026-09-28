package com.matrix.agent.launcher.presentation.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.AttrRes;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;

import com.matrix.agent.launcher.R;

import java.util.Arrays;
import java.util.List;

/** Persisted launcher appearance settings and the single source of palette metadata. */
public final class LauncherThemePreferences {
    private static final String PREFS = "launcher_appearance";
    private static final String KEY_MODE = "mode";
    private static final String KEY_COLOR_THEME = "color_theme";

    private LauncherThemePreferences() {}

    public enum Mode {
        SYSTEM("system", R.string.theme_mode_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", R.string.theme_mode_light, AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", R.string.theme_mode_dark, AppCompatDelegate.MODE_NIGHT_YES);

        public final String key;
        public final int label;
        public final int nightMode;

        Mode(String key, int label, int nightMode) {
            this.key = key;
            this.label = label;
            this.nightMode = nightMode;
        }

        public static Mode fromKey(String key) {
            for (Mode mode : values()) if (mode.key.equals(key)) return mode;
            return SYSTEM;
        }
    }

    public enum ColorTheme {
        CLASSIC("classic", R.string.theme_classic, R.style.Palette_Classic_Light, R.style.Palette_Classic_Dark),
        OCEAN("ocean", R.string.theme_ocean, R.style.Palette_Ocean_Light, R.style.Palette_Ocean_Dark),
        FOREST("forest", R.string.theme_forest, R.style.Palette_Forest_Light, R.style.Palette_Forest_Dark),
        SAKURA("sakura", R.string.theme_sakura, R.style.Palette_Sakura_Light, R.style.Palette_Sakura_Dark),
        VIOLET("violet", R.string.theme_violet, R.style.Palette_Violet_Light, R.style.Palette_Violet_Dark),
        SUNSET("sunset", R.string.theme_sunset, R.style.Palette_Sunset_Light, R.style.Palette_Sunset_Dark),
        CELADON("celadon", R.string.theme_celadon, R.style.Palette_Celadon_Light, R.style.Palette_Celadon_Dark),
        INK("ink", R.string.theme_ink, R.style.Palette_Ink_Light, R.style.Palette_Ink_Dark);

        public final String key;
        public final int label;
        @StyleRes public final int lightStyle;
        @StyleRes public final int darkStyle;

        ColorTheme(String key, int label, @StyleRes int lightStyle, @StyleRes int darkStyle) {
            this.key = key;
            this.label = label;
            this.lightStyle = lightStyle;
            this.darkStyle = darkStyle;
        }

        public static ColorTheme fromKey(String key) {
            for (ColorTheme theme : values()) if (theme.key.equals(key)) return theme;
            return CLASSIC;
        }
    }

    public static List<Mode> modes() { return Arrays.asList(Mode.values()); }
    public static List<ColorTheme> colorThemes() { return Arrays.asList(ColorTheme.values()); }

    public static Mode mode(Context context) {
        return Mode.fromKey(preferences(context).getString(KEY_MODE, Mode.SYSTEM.key));
    }

    public static ColorTheme colorTheme(Context context) {
        return ColorTheme.fromKey(preferences(context).getString(KEY_COLOR_THEME, ColorTheme.CLASSIC.key));
    }

    public static void setMode(Context context, Mode mode) {
        preferences(context).edit().putString(KEY_MODE, mode.key).apply();
    }

    public static void setColorTheme(Context context, ColorTheme theme) {
        preferences(context).edit().putString(KEY_COLOR_THEME, theme.key).apply();
    }

    public static boolean isDark(Context context) {
        Mode mode = mode(context);
        if (mode == Mode.DARK) return true;
        if (mode == Mode.LIGHT) return false;
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @StyleRes
    public static int paletteStyle(Context context) {
        ColorTheme theme = colorTheme(context);
        return isDark(context) ? theme.darkStyle : theme.lightStyle;
    }

    @ColorInt
    public static int color(Context context, @AttrRes int attribute) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)) {
            throw new IllegalArgumentException("Missing launcher theme attribute: " + attribute);
        }
        return value.data;
    }

    /** Resolve legacy color resource call sites through the active semantic palette. */
    @ColorInt
    public static int colorResource(Context context, int colorResource) {
        String name = context.getResources().getResourceEntryName(colorResource);
        int attribute = attributeForLegacyColor(name);
        return attribute == 0 ? ContextCompat.getColor(context, colorResource) : color(context, attribute);
    }

    public static Context previewContext(Context context, ColorTheme colorTheme, boolean dark) {
        ContextThemeWrapper preview = new ContextThemeWrapper(context,
                dark ? R.style.Theme_MatrixLauncher_Dark : R.style.Theme_MatrixLauncher_Light);
        preview.getTheme().applyStyle(dark ? colorTheme.darkStyle : colorTheme.lightStyle, true);
        return preview;
    }

    @AttrRes
    private static int attributeForLegacyColor(String name) {
        switch (name) {
            case "matrix_primary":
            case "matrix_accent": return R.attr.matrix_accent;
            case "matrix_primary_dark":
            case "matrix_text": return R.attr.matrix_ink;
            case "matrix_surface": return R.attr.matrix_paper;
            case "matrix_card": return R.attr.matrix_paper_high;
            case "matrix_muted": return R.attr.matrix_ink_muted;
            case "matrix_border": return R.attr.matrix_divider;
            case "matrix_badge": return R.attr.matrix_selected;
            case "matrix_chat_user_bubble": return R.attr.matrix_user_bubble;
            case "matrix_chat_assistant_bubble": return R.attr.matrix_assistant_bubble;
            case "matrix_chat_system_bubble": return R.attr.matrix_system_bubble;
            case "matrix_chat_user_bubble_stroke":
            case "matrix_chat_assistant_bubble_stroke":
            case "matrix_trace_group_stroke":
            case "matrix_trace_detail_stroke":
            case "matrix_trace_guide_line": return R.attr.matrix_divider;
            case "matrix_trace_group_bg":
            case "matrix_trace_detail_bg":
            case "matrix_chat_avatar_assistant_bg": return R.attr.matrix_paper_high;
            case "matrix_chat_avatar_user_bg":
            case "matrix_trace_node_content_bg": return R.attr.matrix_selected;
            case "matrix_trace_group_title":
            case "matrix_trace_node_preview": return R.attr.matrix_ink_muted;
            case "matrix_trace_node_title":
            case "matrix_trace_node_content": return R.attr.matrix_ink;
            case "matrix_trace_thinking_dot": return R.attr.matrix_moss;
            case "matrix_trace_status_verified": return R.attr.matrix_success;
            case "matrix_trace_status_pending": return R.attr.matrix_warning;
            case "matrix_trace_status_failed": return R.attr.matrix_danger;
            case "composer_surface": return R.attr.matrix_paper_high;
            case "composer_border": return R.attr.matrix_divider;
            case "composer_action": return R.attr.matrix_accent;
            case "composer_action_pressed": return R.attr.matrix_accent_deep;
            case "composer_icon": return R.attr.matrix_ink;
            case "overlay_header": return R.attr.matrix_chrome;
            case "overlay_on_header": return R.attr.matrix_chrome_ink;
            case "overlay_header_muted": return R.attr.matrix_chrome_muted;
            case "overlay_surface": return R.attr.matrix_paper;
            case "overlay_card": return R.attr.matrix_paper_high;
            case "overlay_text": return R.attr.matrix_ink;
            case "overlay_muted": return R.attr.matrix_ink_muted;
            case "overlay_border":
            case "overlay_disabled": return R.attr.matrix_divider;
            case "overlay_input": return R.attr.matrix_paper_high;
            case "overlay_active":
            case "overlay_send": return R.attr.matrix_accent;
            case "overlay_active_surface": return R.attr.matrix_selected;
            case "overlay_success": return R.attr.matrix_success;
            case "overlay_success_surface": return R.attr.matrix_selected;
            case "overlay_warning": return R.attr.matrix_warning;
            case "overlay_warning_surface": return R.attr.matrix_selected;
            case "overlay_danger": return R.attr.matrix_danger;
            case "overlay_danger_surface": return R.attr.matrix_selected;
            default: return 0;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
