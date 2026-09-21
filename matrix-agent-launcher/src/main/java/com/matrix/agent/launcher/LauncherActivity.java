package com.matrix.agent.launcher;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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

/** Shell navigation only; feature pages keep their own MVVM state and SDK boundary. */
public final class LauncherActivity extends AppCompatActivity {
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
    private View conversationIndicator;
    private View tasksIndicator;
    private View voiceIndicator;
    private View modelsIndicator;
    private View downloadsIndicator;
    private LauncherViewModelFactory viewModelFactory;

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
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
        conversationIndicator = findViewById(R.id.nav_conversation_indicator);
        tasksIndicator = findViewById(R.id.nav_tasks_indicator);
        voiceIndicator = findViewById(R.id.nav_voice_indicator);
        modelsIndicator = findViewById(R.id.nav_models_indicator);
        downloadsIndicator = findViewById(R.id.nav_downloads_indicator);
        findViewById(R.id.menu_button).setOnClickListener(ignored -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.drawer_close).setOnClickListener(ignored -> drawer.closeDrawer(GravityCompat.START));
        ((TextView) findViewById(R.id.drawer_version)).setText(
                getString(R.string.launcher_version, versionName()));
        conversation.setOnClickListener(v -> show(new ConversationFragment(), conversation,
                R.string.nav_conversation));
        tasks.setOnClickListener(v -> show(new AgentTaskFragment(), tasks, R.string.nav_tasks));
        voice.setOnClickListener(v -> show(new VoiceFragment(), voice, R.string.nav_voice));
        models.setOnClickListener(v -> show(new ModelFragment(), models, R.string.nav_models));
        downloads.setOnClickListener(v -> show(new DownloadFragment(), downloads, R.string.nav_downloads));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });

        viewModelFactory = new LauncherViewModelFactory(
                ((LauncherApplication) getApplication()).hostGateway());
        new ViewModelProvider(this, viewModelFactory).get(LauncherViewModel.class)
                .connectionState().observe(this, this::updateConnection);
        if (savedInstanceState == null) showInitialPage(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showInitialPage(intent);
    }

    private void showInitialPage(android.content.Intent intent) {
        if (intent != null && MatrixServiceConstants.ACTION_OPEN_DOWNLOADS.equals(intent.getAction())) {
            show(new DownloadFragment(), downloads, R.string.nav_downloads);
        } else {
            show(new ConversationFragment(), conversation, R.string.nav_conversation);
        }
    }

    private void show(Fragment fragment, Button selected, int title) {
        pageTitle.setText(title);
        getSupportFragmentManager().beginTransaction().replace(R.id.page_container, fragment).commit();
        selectNavigation(selected);
        drawer.closeDrawer(GravityCompat.START);
    }

    /** Opens the model page with a downloaded model preselected; it still switches through the SDK. */
    public void showOnDeviceModel(String modelId) {
        pageTitle.setText(R.string.nav_models);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.page_container, ModelFragment.forOnDevice(modelId)).commit();
        selectNavigation(models);
        drawer.closeDrawer(GravityCompat.START);
    }

    private void selectNavigation(Button selected) {
        Button[] buttons = {conversation, tasks, voice, models, downloads};
        View[] indicators = {conversationIndicator, tasksIndicator, voiceIndicator,
                modelsIndicator, downloadsIndicator};
        for (int index = 0; index < buttons.length; index++) {
            Button button = buttons[index];
            boolean active = button == selected;
            button.setBackgroundResource(active ? R.drawable.bg_nav_selected : R.drawable.bg_nav);
            button.setTextColor(active ? Color.WHITE : Color.rgb(126, 163, 158));
            indicators[index].setVisibility(active ? View.VISIBLE : View.GONE);
        }
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
            drawerContent.setPadding(dp(24), dp(28) + bars.top, dp(20), dp(24) + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(drawer);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException impossible) { return "—"; }
    }
}
