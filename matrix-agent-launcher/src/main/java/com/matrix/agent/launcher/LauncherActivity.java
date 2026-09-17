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
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.launcher.presentation.AgentTaskFragment;
import com.matrix.agent.launcher.presentation.DownloadFragment;
import com.matrix.agent.launcher.presentation.LauncherViewModel;
import com.matrix.agent.launcher.presentation.LauncherViewModelFactory;
import com.matrix.agent.launcher.presentation.ModelFragment;

/** Shell navigation only; feature pages keep their own MVVM state and SDK boundary. */
public final class LauncherActivity extends AppCompatActivity {
    private DrawerLayout drawer;
    private TextView status;
    private TextView pageTitle;
    private Button tasks;
    private Button models;
    private Button downloads;
    private View tasksIndicator;
    private View modelsIndicator;
    private View downloadsIndicator;
    private LauncherViewModelFactory viewModelFactory;

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        drawer = findViewById(R.id.drawer_layout);
        status = findViewById(R.id.host_status);
        pageTitle = findViewById(R.id.page_title);
        tasks = findViewById(R.id.nav_tasks);
        models = findViewById(R.id.nav_models);
        downloads = findViewById(R.id.nav_downloads);
        tasksIndicator = findViewById(R.id.nav_tasks_indicator);
        modelsIndicator = findViewById(R.id.nav_models_indicator);
        downloadsIndicator = findViewById(R.id.nav_downloads_indicator);
        findViewById(R.id.menu_button).setOnClickListener(ignored -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.drawer_close).setOnClickListener(ignored -> drawer.closeDrawer(GravityCompat.START));
        ((TextView) findViewById(R.id.drawer_version)).setText("MATRIX AGENT · v" + versionName());
        tasks.setOnClickListener(v -> show(new AgentTaskFragment(), tasks, R.string.nav_tasks));
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
            show(new AgentTaskFragment(), tasks, R.string.nav_tasks);
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
        Button[] buttons = {tasks, models, downloads};
        View[] indicators = {tasksIndicator, modelsIndicator, downloadsIndicator};
        for (int index = 0; index < buttons.length; index++) {
            Button button = buttons[index];
            boolean active = button == selected;
            button.setBackgroundResource(active ? R.drawable.bg_nav_selected : R.drawable.bg_nav);
            button.setTextColor(active ? Color.WHITE : Color.rgb(126, 163, 158));
            indicators[index].setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    private void updateConnection(int state) {
        status.setText(state == ConnectionState.CONNECTED ? "● HOST ONLINE"
                : "○ HOST CONNECTING");
    }

    public LauncherViewModelFactory viewModelFactory() { return viewModelFactory; }
    public int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException impossible) { return "—"; }
    }
}
