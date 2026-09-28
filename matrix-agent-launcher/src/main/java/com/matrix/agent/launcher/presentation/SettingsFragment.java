package com.matrix.agent.launcher.presentation;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.LauncherApplication;
import com.matrix.agent.launcher.R;
import com.matrix.agent.launcher.overlay.pet.PetCharacter;
import com.matrix.agent.launcher.overlay.pet.PetCharacterPreferences;
import com.matrix.agent.launcher.overlay.pet.PetSpriteRepository;
import com.matrix.agent.launcher.presentation.theme.LauncherThemePreferences;
import com.matrix.agent.launcher.presentation.theme.LauncherThemePreferences.ColorTheme;
import com.matrix.agent.launcher.presentation.theme.LauncherThemePreferences.Mode;

import java.util.List;
import java.util.ArrayList;
import java.util.EnumMap;

/** Appearance controls for persisted mode, launcher palettes, and the floating character. */
public final class SettingsFragment extends Fragment {
    private static final String STATE_SCROLL_Y = "settings_scroll_y";
    private record PetOption(LinearLayout card, TextView label, TextView check) {}
    private LauncherActivity activity;
    private ScrollView settingsScroll;
    private int ink;
    private int muted;
    private int paper;
    private int card;
    private int accent;
    private final List<PetSpriteRepository.Subscription> petPreviews = new ArrayList<>();
    private final EnumMap<PetCharacter, PetOption> petOptions = new EnumMap<>(PetCharacter.class);

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = (LauncherActivity) requireActivity();
        petOptions.clear();
        ink = tone(R.attr.matrix_ink);
        muted = tone(R.attr.matrix_ink_muted);
        paper = tone(R.attr.matrix_paper);
        card = tone(R.attr.matrix_paper_high);
        accent = tone(R.attr.matrix_accent);

        ScrollView scroll = new ScrollView(requireContext());
        settingsScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(paper);
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(32));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button back = new Button(requireContext());
        back.setText("‹   返回");
        back.setAllCaps(false);
        back.setTextSize(14);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(muted);
        back.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        back.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        back.setPadding(0, 0, 0, 0);
        back.setOnClickListener(view -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        content.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        TextView title = text(getString(R.string.launcher_appearance_title), 30, ink, true, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(4);
        content.addView(title, titleParams);
        TextView intro = text(getString(R.string.launcher_appearance_intro), 13, muted, false, false);
        intro.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams introParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introParams.topMargin = dp(7);
        introParams.bottomMargin = dp(22);
        content.addView(intro, introParams);

        content.addView(sectionHeading(R.string.launcher_appearance_mode,
                R.string.launcher_appearance_mode_summary));
        content.addView(modeChoices(LauncherThemePreferences.modes(),
                LauncherThemePreferences.mode(requireContext())));

        LinearLayout colorHeading = sectionHeading(R.string.launcher_appearance_color,
                R.string.launcher_appearance_color_summary);
        LinearLayout.LayoutParams colorHeadingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colorHeadingParams.topMargin = dp(24);
        content.addView(colorHeading, colorHeadingParams);
        content.addView(themeChoices(LauncherThemePreferences.colorThemes(),
                LauncherThemePreferences.colorTheme(requireContext())));

        LinearLayout petHeading = sectionHeading(R.string.launcher_floating_character,
                R.string.launcher_floating_character_summary);
        LinearLayout.LayoutParams petHeadingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        petHeadingParams.topMargin = dp(24);
        content.addView(petHeading, petHeadingParams);
        content.addView(petChoices(List.of(PetCharacter.values()), PetCharacterPreferences.get(requireContext())));
        int restoreY = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_SCROLL_Y);
        if (restoreY > 0) scroll.post(() -> scroll.scrollTo(0, restoreY));
        return scroll;
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        if (settingsScroll != null) outState.putInt(STATE_SCROLL_Y, settingsScroll.getScrollY());
        super.onSaveInstanceState(outState);
    }

    private LinearLayout petChoices(List<PetCharacter> characters, PetCharacter selected) {
        LinearLayout grid = new LinearLayout(requireContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int start = 0; start < characters.size(); start += 2) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 2; column++) {
                int index = start + column;
                if (index >= characters.size()) {
                    row.addView(new View(requireContext()), new LinearLayout.LayoutParams(0, dp(126), 1f));
                    continue;
                }
                PetCharacter character = characters.get(index);
                row.addView(petOption(character, character == selected), weightedCardParams(column));
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (start > 0) rowParams.topMargin = dp(9);
            grid.addView(row, rowParams);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        grid.setLayoutParams(params);
        return grid;
    }

    private View petOption(PetCharacter character, boolean selected) {
        LinearLayout option = new LinearLayout(requireContext());
        option.setOrientation(LinearLayout.VERTICAL);
        option.setGravity(Gravity.CENTER_HORIZONTAL);
        option.setPadding(dp(10), dp(10), dp(10), dp(10));
        option.setMinimumHeight(dp(126));
        option.setBackground(panelBackground(selected ? tone(R.attr.matrix_selected) : card, dp(16)));
        option.setClickable(true);
        option.setFocusable(true);

        ImageView portrait = new ImageView(requireContext());
        portrait.setScaleType(ImageView.ScaleType.FIT_CENTER);
        option.addView(portrait, new LinearLayout.LayoutParams(dp(58), dp(62)));
        PetSpriteRepository repository = ((LauncherApplication) requireActivity().getApplication()).petSprites();
        petPreviews.add(repository.loadPreview(character, bitmap -> {
            if (bitmap != null) portrait.setImageBitmap(bitmap);
        }));

        TextView label = text(getString(character.label()), 13, selected ? tone(R.attr.matrix_accent_deep) : ink,
                selected, false);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(5);
        option.addView(label, labelParams);
        TextView check = text("✓ 已选择", 11, tone(R.attr.matrix_accent_deep), true, false);
        check.setGravity(Gravity.CENTER);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        option.addView(check);
        petOptions.put(character, new PetOption(option, label, check));
        option.setContentDescription(getString(character.label()) + (selected ? "，已选择" : ""));
        option.setOnClickListener(view -> selectPet(character));
        return option;
    }

    private void selectPet(PetCharacter character) {
        if (PetCharacterPreferences.get(requireContext()) == character) return;
        PetCharacterPreferences.set(requireContext(), character);
        ((LauncherApplication) requireActivity().getApplication()).overlay().setPetCharacter(character);
        petOptions.forEach((candidate, option) -> {
            boolean selected = candidate == character;
            option.card().setBackground(panelBackground(selected ? tone(R.attr.matrix_selected) : card, dp(16)));
            option.label().setTextColor(selected ? tone(R.attr.matrix_accent_deep) : ink);
            option.label().setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            option.check().setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            option.card().setContentDescription(getString(candidate.label()) + (selected ? "，已选择" : ""));
        });
    }

    @Override public void onDestroyView() {
        petPreviews.forEach(PetSpriteRepository.Subscription::close);
        petPreviews.clear();
        petOptions.clear();
        super.onDestroyView();
    }

    private LinearLayout modeChoices(List<Mode> modes, Mode selected) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int index = 0; index < modes.size(); index++) {
            Mode mode = modes.get(index);
            boolean active = mode == selected;
            TextView option = text(getString(mode.label), 13,
                    active ? tone(R.attr.matrix_accent_deep) : muted, active, false);
            option.setGravity(Gravity.CENTER);
            option.setMinHeight(dp(46));
            option.setPadding(dp(4), dp(8), dp(4), dp(8));
            option.setBackground(panelBackground(active ? tone(R.attr.matrix_selected)
                    : android.graphics.Color.TRANSPARENT, dp(24)));
            option.setClickable(true);
            option.setFocusable(true);
            option.setOnClickListener(view -> {
                LauncherThemePreferences.setMode(requireContext(), mode);
                activity.recreate();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (index > 0) params.leftMargin = dp(4);
            row.addView(option, params);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        row.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        row.setLayoutParams(params);
        return row;
    }

    private LinearLayout themeChoices(List<ColorTheme> themes, ColorTheme selected) {
        LinearLayout grid = new LinearLayout(requireContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int start = 0; start < themes.size(); start += 2) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 2; column++) {
                int index = start + column;
                if (index >= themes.size()) {
                    row.addView(new View(requireContext()), new LinearLayout.LayoutParams(0, dp(80), 1f));
                    continue;
                }
                ColorTheme theme = themes.get(index);
                row.addView(themeOption(theme, theme == selected), weightedCardParams(column));
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (start > 0) rowParams.topMargin = dp(9);
            grid.addView(row, rowParams);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        grid.setLayoutParams(params);
        return grid;
    }

    private View themeOption(ColorTheme theme, boolean selected) {
        Context preview = LauncherThemePreferences.previewContext(requireContext(), theme,
                LauncherThemePreferences.isDark(requireContext()));
        int themeAccent = LauncherThemePreferences.color(preview, R.attr.matrix_accent);
        int themePaper = LauncherThemePreferences.color(preview, R.attr.matrix_paper);
        int themeCard = LauncherThemePreferences.color(preview, R.attr.matrix_paper_high);

        LinearLayout option = new LinearLayout(requireContext());
        option.setOrientation(LinearLayout.VERTICAL);
        option.setPadding(dp(12), dp(12), dp(12), dp(11));
        option.setMinimumHeight(dp(92));
        option.setBackground(panelBackground(selected ? tone(R.attr.matrix_selected) : card, dp(16)));
        option.setClickable(true);
        option.setFocusable(true);

        LinearLayout swatches = new LinearLayout(requireContext());
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        TextView accentDot = new TextView(requireContext());
        accentDot.setBackground(panelBackground(themeAccent, dp(99)));
        swatches.addView(accentDot, new LinearLayout.LayoutParams(dp(22), dp(22)));
        View paperTile = new View(requireContext());
        paperTile.setBackground(panelBackground(themePaper, dp(7)));
        LinearLayout.LayoutParams paperParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        paperParams.leftMargin = dp(7);
        swatches.addView(paperTile, paperParams);
        View cardTile = new View(requireContext());
        cardTile.setBackground(panelBackground(themeCard, dp(7)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        cardParams.leftMargin = dp(5);
        swatches.addView(cardTile, cardParams);
        TextView choice = text(selected ? "✓" : "", 14, accent, true, false);
        choice.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams choiceParams = new LinearLayout.LayoutParams(0, dp(22), 1f);
        swatches.addView(choice, choiceParams);
        option.addView(swatches);

        TextView label = text(getString(theme.label), 13, ink, selected, false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(9);
        option.addView(label, labelParams);
        if (selected) option.setElevation(dp(2));
        option.setContentDescription(getString(theme.label));
        option.setOnClickListener(view -> {
            LauncherThemePreferences.setColorTheme(requireContext(), theme);
            activity.recreate();
        });
        return option;
    }

    private LinearLayout sectionHeading(int title, int summary) {
        LinearLayout block = new LinearLayout(requireContext());
        block.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text(getString(title), 17, ink, true, false);
        block.addView(heading);
        TextView detail = text(getString(summary), 12, muted, false, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(4);
        block.addView(detail, detailParams);
        return block;
    }

    private LinearLayout.LayoutParams weightedCardParams(int column) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (column > 0) params.leftMargin = dp(8);
        return params;
    }

    private TextView text(String value, int size, int color, boolean bold, boolean serif) {
        TextView view = new TextView(requireContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(serif ? Typeface.create(Typeface.SERIF, Typeface.BOLD) : Typeface.DEFAULT_BOLD);
        else if (serif) view.setTypeface(Typeface.SERIF);
        return view;
    }

    private GradientDrawable panelBackground(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int tone(int attribute) { return LauncherThemePreferences.color(requireContext(), attribute); }
    private int dp(int value) { return activity.dp(value); }
}
