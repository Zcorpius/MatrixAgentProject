package com.matrix.agent.launcher;

import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.launcher.presentation.AgentTaskFragment;
import com.matrix.agent.launcher.presentation.ConversationFragment;
import com.matrix.agent.launcher.presentation.DownloadFragment;
import com.matrix.agent.launcher.presentation.LauncherViewModel;
import com.matrix.agent.launcher.presentation.LauncherViewModelFactory;
import com.matrix.agent.launcher.presentation.ModelFragment;
import com.matrix.agent.launcher.presentation.VoiceFragment;
import com.matrix.agent.launcher.presentation.SettingsFragment;
import com.matrix.agent.launcher.presentation.theme.LauncherThemePreferences;

/** Shell navigation only; feature pages keep their own MVVM state and SDK boundary. */
public final class LauncherActivity extends AppCompatActivity {
    private static final String STATE_SELECTED_NAVIGATION = "selected_navigation";
    @Override
    public void dump(String prefix, java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        if (args != null && java.util.Arrays.asList(args).contains("--handoff")) {
            ((LauncherApplication) getApplication()).diagnostics().dump(writer);
        } else super.dump(prefix, fd, writer, args);
    }

    /** φ⁻¹：导航覆盖屏幕 61.803%，保留 38.197% 的工作区作为空间锚点。 */
    private static final double DRAWER_GOLDEN_RATIO = 0.61803398875d;

    private DrawerLayout drawer;
    private View drawerContent;
    private TextView status;
    private TextView pageTitle;
    private Button conversation;
    private Button tasks;
    private Button voice;
    private Button models;
    private Button downloads;
    private Button settings;
    private ImageView[] navigationIcons;
    private LauncherViewModelFactory viewModelFactory;
    private int selectedNavigationId = R.id.nav_conversation;
    private boolean leavingForPet;
    private final ActivityResultLauncher<Intent> overlayPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.canDrawOverlays(this)) returnWithFloatingPet();
                else Toast.makeText(this, R.string.launcher_overlay_permission_needed,
                        Toast.LENGTH_SHORT).show();
            });

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        boolean dark = LauncherThemePreferences.isDark(this);
        setTheme(dark ? R.style.Theme_MatrixLauncher_Dark : R.style.Theme_MatrixLauncher_Light);
        getTheme().applyStyle(LauncherThemePreferences.paletteStyle(this), true);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        drawer = findViewById(R.id.drawer_layout);
        drawerContent = findViewById(R.id.drawer_content);
        applyGoldenRatioDrawerWidth();
        applySystemBarInsets();
        status = findViewById(R.id.host_status);
        pageTitle = findViewById(R.id.page_title);
        conversation = findViewById(R.id.nav_conversation);
        tasks = findViewById(R.id.nav_tasks);
        voice = findViewById(R.id.nav_voice);
        models = findViewById(R.id.nav_models);
        downloads = findViewById(R.id.nav_downloads);
        settings = findViewById(R.id.nav_settings);
        navigationIcons = new ImageView[]{findViewById(R.id.nav_conversation_icon),
                findViewById(R.id.nav_tasks_icon), findViewById(R.id.nav_voice_icon),
                findViewById(R.id.nav_models_icon), findViewById(R.id.nav_downloads_icon),
                findViewById(R.id.nav_settings_icon)};
        findViewById(R.id.menu_button).setOnClickListener(ignored -> drawer.openDrawer(GravityCompat.START));
        ((TextView) findViewById(R.id.drawer_version)).setText(
                getString(R.string.launcher_version, versionName()));
        conversation.setOnClickListener(v -> show(new ConversationFragment(), conversation,
                R.string.nav_conversation));
        tasks.setOnClickListener(v -> show(new com.matrix.agent.launcher.presentation.ScheduleFragment(), tasks, R.string.nav_tasks));
        voice.setOnClickListener(v -> show(new VoiceFragment(), voice, R.string.nav_voice));
        models.setOnClickListener(v -> show(new ModelFragment(), models, R.string.nav_models));
        downloads.setOnClickListener(v -> show(new DownloadFragment(), downloads, R.string.nav_downloads));
        settings.setOnClickListener(v -> show(new SettingsFragment(), settings, R.string.launcher_nav_settings));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });

        viewModelFactory = new LauncherViewModelFactory(
                ((LauncherApplication) getApplication()).hostGateway(),
                ((LauncherApplication) getApplication()).draftLane());
        new ViewModelProvider(this, viewModelFactory).get(LauncherViewModel.class)
                .connectionState().observe(this, this::updateConnection);
        if (savedInstanceState == null) {
            showInitialPage(getIntent());
        } else {
            selectedNavigationId = savedInstanceState.getInt(STATE_SELECTED_NAVIGATION, R.id.nav_conversation);
            View selected = findViewById(selectedNavigationId);
            if (selected == null) selected = conversation;
            pageTitle.setText(titleForNavigation(selected.getId()));
            selectNavigation(selected);
        }
        applySystemBarColors();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showInitialPage(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!leavingForPet) ((LauncherApplication) getApplication()).overlay().launcherVisible();
    }

    @Override protected void onStop() {
        leavingForPet = false;
        super.onStop();
    }

    /** Settings' return action leaves the task behind only after the pet window is attached. */
    public void returnWithFloatingPet() {
        if (!Settings.canDrawOverlays(this)) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try { overlayPermission.launch(permission); }
            catch (RuntimeException failure) {
                Toast.makeText(this, R.string.launcher_overlay_permission_needed,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!((LauncherApplication) getApplication()).overlay().showFloatingPet()) {
            Toast.makeText(this, R.string.launcher_overlay_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        leavingForPet = true;
        if (!moveTaskToBack(true)) finish();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_SELECTED_NAVIGATION, selectedNavigationId);
        super.onSaveInstanceState(outState);
    }

    private void showInitialPage(android.content.Intent intent) {
        if (intent != null && MatrixServiceConstants.ACTION_OPEN_SCHEDULE.equals(intent.getAction())) {
            String run = intent.getStringExtra(MatrixServiceConstants.EXTRA_SCHEDULE_RUN_ID);
            show(run == null ? com.matrix.agent.launcher.presentation.ScheduleFragment.forHistory()
                    : com.matrix.agent.launcher.presentation.ScheduleFragment.forRun(run), tasks, R.string.nav_tasks);
        } else if (intent != null && MatrixServiceConstants.ACTION_OPEN_DOWNLOADS.equals(intent.getAction())) {
            show(new DownloadFragment(), downloads, R.string.nav_downloads);
        } else {
            if (intent != null && com.matrix.agent.api.handoff.HandoffProtocol.ACTION_OPEN_CONVERSATION.equals(intent.getAction())) {
                String target = intent.getStringExtra(com.matrix.agent.api.handoff.HandoffProtocol.EXTRA_CONVERSATION_ID);
                if (target != null && target.length() <= 128) {
                    new ViewModelProvider(this, viewModelFactory)
                            .get(com.matrix.agent.launcher.presentation.ConversationViewModel.class).switchConversation(target);
                }
            }
            show(new ConversationFragment(), conversation, R.string.nav_conversation);
        }
    }

    private void show(Fragment fragment, View selected, int title) {
        pageTitle.setText(title);
        selectedNavigationId = selected.getId();
        getSupportFragmentManager().beginTransaction().replace(R.id.page_container, fragment).commit();
        selectNavigation(selected);
        drawer.closeDrawer(GravityCompat.START);
    }

    /** Opens the model page with a downloaded model preselected; it still switches through the SDK. */
    public void showOnDeviceModel(String modelId) {
        pageTitle.setText(R.string.nav_models);
        selectedNavigationId = R.id.nav_models;
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.page_container, ModelFragment.forOnDevice(modelId)).commit();
        selectNavigation(models);
        drawer.closeDrawer(GravityCompat.START);
    }

    /** 模型胶囊入口（I5 §8.1）：跳转只读模型接入页（配置仍由 Host 管理）。 */
    public void showModelsPage() {
        show(new ModelFragment(), models, R.string.nav_models);
    }

    private void selectNavigation(Button selected) {
        selectNavigation((View) selected);
    }

    private void selectNavigation(View selected) {
        Button[] buttons = {conversation, tasks, voice, models, downloads, settings};
        for (int index = 0; index < buttons.length; index++) {
            Button button = buttons[index];
            boolean active = button == selected;
            button.setBackgroundResource(R.drawable.bg_nav);
            button.setTextColor(LauncherThemePreferences.color(this,
                    active ? R.attr.matrix_accent_deep : R.attr.matrix_chrome_muted));
            button.setTypeface(null, active ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            navigationIcons[index].setColorFilter(LauncherThemePreferences.color(this,
                    active ? R.attr.matrix_accent_deep : R.attr.matrix_chrome_muted));
        }
    }

    private int titleForNavigation(int id) {
        if (id == R.id.nav_tasks) return R.string.nav_tasks;
        if (id == R.id.nav_voice) return R.string.nav_voice;
        if (id == R.id.nav_models) return R.string.nav_models;
        if (id == R.id.nav_downloads) return R.string.nav_downloads;
        if (id == R.id.nav_settings) return R.string.launcher_nav_settings;
        return R.string.nav_conversation;
    }

    private void updateConnection(int state) {
        switch (state) {
            case ConnectionState.CONNECTED:
                status.setText(R.string.launcher_host_online);
                break;
            case ConnectionState.CONNECTING:
                status.setText(R.string.launcher_host_connecting);
                break;
            case ConnectionState.SERVICE_NOT_READY:
                status.setText(R.string.launcher_host_not_ready);
                break;
            case ConnectionState.PERMISSION_DENIED:
                status.setText(R.string.launcher_host_access_denied);
                break;
            case ConnectionState.DISCONNECTED:
            default:
                status.setText(R.string.launcher_host_disconnected);
                break;
        }
        int color = state == ConnectionState.CONNECTED ? R.attr.matrix_success
                : state == ConnectionState.CONNECTING ? R.attr.matrix_warning : R.attr.matrix_ink_muted;
        status.setTextColor(LauncherThemePreferences.color(this, color));
    }

    private void applySystemBarColors() {
        boolean dark = LauncherThemePreferences.isDark(this);
        getWindow().setStatusBarColor(LauncherThemePreferences.color(this, R.attr.matrix_chrome));
        getWindow().setNavigationBarColor(LauncherThemePreferences.color(this, R.attr.matrix_paper));
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
    }

    public LauncherViewModelFactory viewModelFactory() { return viewModelFactory; }
    public int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    /**
     * DrawerLayout 的宽度必须基于实际窗口而不是固定 dp：分屏、旋转或显示尺寸变化后，
     * 仍按同一黄金比例覆盖当前工作区。XML 的 240dp 仅用于首次测量前的安全回退。
     */
    private void applyGoldenRatioDrawerWidth() {
        drawer.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int availableWidth = right - left;
            if (availableWidth <= 0) return;
            int goldenWidth = (int) Math.round(availableWidth * DRAWER_GOLDEN_RATIO);
            DrawerLayout.LayoutParams params =
                    (DrawerLayout.LayoutParams) drawerContent.getLayoutParams();
            if (params.width != goldenWidth) {
                params.width = goldenWidth;
                drawerContent.setLayoutParams(params);
            }
        });
    }

    /**
     * Android 15+ draws app content edge-to-edge by default.  Keep the workspace and drawer
     * deliberately below the system status bar instead of relying on a fixed, device-specific
     * top margin.
     */
    private void applySystemBarInsets() {
        View workspace = findViewById(R.id.workspace_content);
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Android 15 enforces edge-to-edge for this target SDK.  In that mode an
            // activity-level adjustResize flag alone is not a reliable IME contract:
            // the keyboard can be drawn over the workspace.  The workspace owns the
            // page container, so reserve the larger of navigation-bar and IME bottoms
            // here.  This keeps the conversation composer actionable on every IME.
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            workspace.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            drawerContent.setPadding(dp(20), dp(24) + bars.top, dp(20), dp(16) + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(drawer);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException impossible) { return "—"; }
    }
}
