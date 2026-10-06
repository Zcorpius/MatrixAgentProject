package com.matrix.agent.platform.weather;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.host.MatrixAgentApplication;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.platform.MatrixHttpClient;

/** Host-owned credential form: the secret never enters Launcher, Binder DTOs or schedule rows. */
public final class WeatherConfigActivity extends Activity {
    private static final MatrixHttpClient NETWORK = new MatrixHttpClient();
    private EditText host, secret, keyId, developerId, projectId;
    private Spinner kind;
    private Button save;
    private Button permissionAction;
    private TextView permissionState;
    private WeatherConfigStore store;
    private com.matrix.agent.identity.CancellationToken searchCancellation;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new WeatherConfigStore(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("和风天气服务配置"); title.setTextSize(23); form.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("仅 Host 保存凭据。请填入项目专属 API Host。JWT 私钥经 Android Keystore 加密保存，每次请求临时签发 15 分钟令牌；API Key 也可使用。服务未配置时，天气计划保留为草稿。");
        form.addView(explanation);
        WeatherConfigStore.Config current = store.load();
        host = new EditText(this); host.setHint("xxxx.qweatherapi.com"); host.setSingleLine(true);
        if (current != null) host.setText(current.host()); form.addView(host);
        kind = new Spinner(this);
        kind.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"API_KEY", "JWT"}));
        if (current != null && current.authKind().equals("JWT")) kind.setSelection(1);
        form.addView(kind);
        keyId = new EditText(this); keyId.setHint("JWT Key ID (kid)");
        developerId = new EditText(this); developerId.setHint("Developer ID (iss)");
        projectId = new EditText(this); projectId.setHint("Project ID (sub)");
        if (current != null) { keyId.setText(current.keyId()); developerId.setText(current.developerId()); projectId.setText(current.projectId()); }
        form.addView(keyId); form.addView(developerId); form.addView(projectId);
        secret = new EditText(this); secret.setHint("API Key 或 PKCS#8 Ed25519 私钥 PEM（不回显已有值）");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); form.addView(secret);
        kind.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int visibility = position == 1 ? View.VISIBLE : View.GONE;
                keyId.setVisibility(visibility); developerId.setVisibility(visibility); projectId.setVisibility(visibility);
                secret.setSingleLine(position != 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        save = new Button(this); save.setText("保存天气服务配置"); save.setOnClickListener(v -> save()); form.addView(save);
        Button clear = new Button(this); clear.setText("清除天气服务配置");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("清除天气服务凭据？已有天气计划仍会到点提醒，但天气内容不可用。")
                .setNegativeButton("返回", null).setPositiveButton("清除", (dialog, which) -> {
                    store.clear(); ((MatrixAgentApplication) getApplication()).scheduleRuntime().changed();
                    Toast.makeText(this, "已清除天气配置", Toast.LENGTH_LONG).show(); finish();
                }).show());
        form.addView(clear);
        if (getIntent().getBooleanExtra(MatrixServiceConstants.EXTRA_PICK_WEATHER_CITY, false)) {
            TextView pickerTitle = new TextView(this); pickerTitle.setText("搜索固定城市"); pickerTitle.setTextSize(18);
            form.addView(pickerTitle);
            EditText cityQuery = new EditText(this); cityQuery.setSingleLine(true);
            cityQuery.setHint("输入城市或区县名称"); form.addView(cityQuery);
            Button search = new Button(this); search.setText("搜索并选择城市");
            search.setOnClickListener(v -> searchCity(cityQuery, search)); form.addView(search);
        }
        permissionState = new TextView(this); form.addView(permissionState);
        permissionAction = new Button(this);
        permissionAction.setOnClickListener(v -> {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, 91);
            else if (getSystemService(android.location.LocationManager.class) == null
                    || !getSystemService(android.location.LocationManager.class).isLocationEnabled())
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            else startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        });
        form.addView(permissionAction);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(form);
        setContentView(scroll);
        refreshPermissions();
    }
    @Override protected void onResume() { super.onResume(); if (permissionState != null) refreshPermissions(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results); refreshPermissions();
    }
    private void refreshPermissions() {
        boolean coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean background = checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        var manager = getSystemService(android.location.LocationManager.class);
        boolean enabled = manager != null && manager.isLocationEnabled();
        boolean cityProvider = manager != null
                && manager.getAllProviders().contains(android.location.LocationManager.NETWORK_PROVIDER)
                && manager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        permissionState.setText("当前位置：" + (coarse ? "粗定位已授权" : "粗定位未授权")
                + " · " + (background ? "后台定位已授权" : "后台定位未授权")
                + " · 系统定位" + (enabled ? "已开启" : "已关闭")
                + "\n城市级后台定位源：" + (cityProvider ? "已就绪，仍需冷启动/待机实测" : "不可用；当前位置定时计划保持草稿，请选固定城市"));
        permissionAction.setText(coarse ? "检查后台定位或系统定位设置" : "申请 Host 粗定位权限");
    }
    private void save() {
        String endpoint = host.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
        String credential = secret.getText().toString().trim();
        String authentication = kind.getSelectedItem().toString();
        final WeatherConfigStore.Config config;
        try { config = new WeatherConfigStore.Config(endpoint, authentication, credential,
                keyId.getText().toString().trim(), developerId.getText().toString().trim(), projectId.getText().toString().trim()); }
        catch (IllegalArgumentException invalid) { host.setError(invalid.getMessage()); return; }
        save.setEnabled(false);
        Thread worker = new Thread(() -> {
            boolean success;
            try { store.save(config); success = true; }
            catch (Exception failure) { success = false; }
            boolean saved = success;
            runOnUiThread(() -> {
                secret.setText("");
                save.setEnabled(true);
                if (saved) {
                    ((MatrixAgentApplication) getApplication()).scheduleRuntime().changed();
                    Toast.makeText(this, "天气服务配置已加密保存", Toast.LENGTH_LONG).show(); finish();
                } else Toast.makeText(this, "安全存储不可用，未保存配置", Toast.LENGTH_LONG).show();
            });
        }, "matrix-weather-config");
        worker.start();
    }
    private void searchCity(EditText query, Button search) {
        String text = query.getText().toString().strip();
        if (store.load() == null) { Toast.makeText(this, "请先保存天气服务配置", Toast.LENGTH_LONG).show(); return; }
        search.setEnabled(false);
        var token = new com.matrix.agent.identity.CancellationToken();
        searchCancellation = token;
        new Thread(() -> {
            java.util.List<CityResolverPort.City> choices = null;
            String failure = "";
            try {
                var transport = new QWeatherTransport(NETWORK.metadata(), store);
                var request = AgentRequest.builder("前台搜索固定城市", Actor.DRIVER)
                        .timeoutMillis(8_000).cancellationToken(token).build();
                choices = new QWeatherCitySearch(transport).find(text, request);
            } catch (WeatherFailure known) { failure = known.code(); }
            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); failure = "CITY_SEARCH_CANCELLED"; }
            catch (RuntimeException invalid) { failure = "CITY_SEARCH_UNAVAILABLE"; }
            java.util.List<CityResolverPort.City> found = choices;
            String error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                searchCancellation = null;
                search.setEnabled(true);
                if (found == null) { Toast.makeText(this, "城市搜索未完成：" + error, Toast.LENGTH_LONG).show(); return; }
                String[] labels = found.stream().map(city -> city.name() + " · " + city.district() + " · " + city.zone()).toArray(String[]::new);
                new AlertDialog.Builder(this).setTitle("确认固定城市")
                        .setItems(labels, (dialog, which) -> {
                            var city = found.get(which);
                            setResult(RESULT_OK, new android.content.Intent()
                                    .putExtra(MatrixServiceConstants.EXTRA_WEATHER_CITY_ID, city.id())
                                    .putExtra(MatrixServiceConstants.EXTRA_WEATHER_CITY_NAME, city.name())
                                    .putExtra(MatrixServiceConstants.EXTRA_WEATHER_CITY_ZONE, city.zone().getId()));
                            finish();
                        }).show();
            });
        }, "matrix-weather-city-search").start();
    }
    @Override protected void onDestroy() {
        if (searchCancellation != null) searchCancellation.cancel();
        if (secret != null) secret.setText("");
        super.onDestroy();
    }
}
