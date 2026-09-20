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
import com.matrix.agent.launcher.presentation.DebugTraceFragment;
import com.matrix.agent.launcher.presentation.DownloadFragment;
import com.matrix.agent.launcher.presentation.LauncherViewModel;
import com.matrix.agent.launcher.presentation.LauncherViewModelFactory;
import com.matrix.agent.launcher.presentation.ModelFragment;
import com.matrix.agent.launcher.presentation.VoiceFragment;

/** Shell navigation only; feature pages keep their own MVVM state and SDK boundary. */
public final class LauncherActivity extends AppCompatActivity {
    private DrawerLayout drawer;
    private TextView status;
    private TextView pageTitle;
    private Button conversation;
    private Button tasks;
    private Button voice;
    private Button models;
    private Button downloads;
    private Button debugTrace;
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
        // 调试轨迹入口（评估 v1.0 §4.3）：仅 MATRIX_DEBUG_TRACE_UI=true 的构建显示
        if (com.matrix.agent.launcher.BuildConfig.MATRIX_DEBUG_TRACE_UI) {
            debugTrace = findViewById(R.id.nav_debug_trace);
            debugTrace.setVisibility(View.VISIBLE);
            debugTrace.setOnClickListener(v -> show(new DebugTraceFragment(),
                    debugTrace, R.string.nav_debug_trace));
        }
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
        status.setText(state == ConnectionState.CONNECTED ? R.string.launcher_host_online
                : R.string.launcher_host_connecting);
    }

    public LauncherViewModelFactory viewModelFactory() { return viewModelFactory; }
    public int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    /**
     * Android 15+ draws app content edge-to-edge by default.  Keep the workspace and drawer
     * deliberately below the system status bar instead of relying on a fixed, device-specific
     * top margin.
     */
    private void applySystemBarInsets() {
        View workspace = findViewById(R.id.workspace_content);
        View drawerContent = findViewById(R.id.drawer_content);
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            workspace.setPadding(0, bars.top, 0, bars.bottom);
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
