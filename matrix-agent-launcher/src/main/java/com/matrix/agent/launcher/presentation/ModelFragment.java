package com.matrix.agent.launcher.presentation;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.api.model.ModelInfo;
import com.matrix.agent.api.model.ModelRuntimeStatus;
import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.R;

import java.util.Arrays;
import java.util.List;

/** SDK-only model gateway for cloud, LAN OpenAI-compatible, and downloaded on-device models. */
public final class ModelFragment extends Fragment {
    private static final String ARG_ON_DEVICE_MODEL = "on_device_model";
    private List<ProviderOption> providers;

    private Spinner provider;
    private EditText modelId, endpoint, key;
    private TextView detail, status, idLabel, keyLabel;
    private LinearLayout endpointGroup, keyGroup, models;
    private Button save, test, refresh;
    private ModelViewModel viewModel;
    @Nullable private ModelViewModel.State rendered;
    private boolean connected, keyVisible;

    public static ModelFragment forOnDevice(String modelId) {
        ModelFragment fragment = new ModelFragment();
        Bundle args = new Bundle(); args.putString(ARG_ON_DEVICE_MODEL, modelId); fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup parent, @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_model, parent, false);
        viewModel = new ViewModelProvider(requireActivity(), activity().viewModelFactory()).get(ModelViewModel.class);
        providers = providerOptions();
        provider = root.findViewById(R.id.model_provider); modelId = root.findViewById(R.id.model_id);
        endpoint = root.findViewById(R.id.model_endpoint); key = root.findViewById(R.id.model_key);
        detail = root.findViewById(R.id.model_provider_detail); status = root.findViewById(R.id.model_status);
        idLabel = root.findViewById(R.id.model_id_label); keyLabel = root.findViewById(R.id.model_key_label);
        endpointGroup = root.findViewById(R.id.model_endpoint_group); keyGroup = root.findViewById(R.id.model_key_group);
        models = root.findViewById(R.id.model_list); save = root.findViewById(R.id.model_save);
        test = root.findViewById(R.id.model_test); refresh = root.findViewById(R.id.model_refresh);
        provider.setDropDownVerticalOffset(dp(8));
        provider.setAdapter(new ArrayAdapter<ProviderOption>(requireContext(), R.layout.item_model_provider, providers) {
            @NonNull @Override public View getView(int p, @Nullable View v, @NonNull ViewGroup parent) { TextView t=(TextView)super.getView(p,v,parent); t.setText(providers.get(p).label); return t; }
            @NonNull @Override public View getDropDownView(int p, @Nullable View v, @NonNull ViewGroup parent) { TextView t=(TextView)super.getDropDownView(p,v,parent); t.setText(providers.get(p).label); return t; }
        });
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { renderProvider(providers.get(pos)); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        String requestedModel = getArguments() == null ? null : getArguments().getString(ARG_ON_DEVICE_MODEL);
        if (requestedModel != null) {
            provider.setSelection(indexOf("on_device"));
            modelId.setText(requestedModel);
        }
        root.<ImageButton>findViewById(R.id.model_key_visibility).setOnClickListener(v -> toggleKeyVisibility());
        save.setOnClickListener(v -> provision()); test.setOnClickListener(v -> viewModel.testConnection(currentProvider().id)); refresh.setOnClickListener(v -> viewModel.refresh());
        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        new ViewModelProvider(requireActivity(), activity().viewModelFactory()).get(LauncherViewModel.class).connectionState().observe(getViewLifecycleOwner(), v -> { connected = viewModel.isHostConnected(); renderControls(); if (connected) viewModel.refresh(); });
        return root;
    }
    private List<ProviderOption> providerOptions() {
        return Arrays.asList(
                cloud("glm", R.string.model_provider_glm, "glm-5.2"), cloud("deepseek", R.string.model_provider_deepseek, "deepseek-v4-flash"),
                cloud("qwen", R.string.model_provider_qwen, "qwen3.7-plus"), cloud("kimi", R.string.model_provider_kimi, "kimi-k2.5"),
                cloud("doubao", R.string.model_provider_doubao, "Endpoint ID"), cloud("anthropic", R.string.model_provider_anthropic, "claude-sonnet-4-5"),
                cloud("gemini", R.string.model_provider_gemini, "gemini-3.5-flash"),
                local("ollama", R.string.model_provider_ollama, "qwen2.5:7b", "http://127.0.0.1:11434/api/chat"),
                local("lmstudio", R.string.model_provider_lmstudio, "local-model", "http://127.0.0.1:1234/v1/chat/completions"),
                local("vllm", R.string.model_provider_vllm, "Qwen/Qwen3-8B", "http://127.0.0.1:8000/v1/chat/completions"),
                new ProviderOption("on_device", getString(R.string.model_provider_on_device), "", "", false, false, true),
                local("custom", R.string.model_provider_custom, "", "https://example.com/v1"));
    }
    private ProviderOption cloud(String id, int label, String model) { return new ProviderOption(id,getString(label),model,"",true,false,false); }
    private ProviderOption local(String id, int label, String model, String url) { return new ProviderOption(id,getString(label),model,url,false,true,false); }
    private ProviderOption currentProvider() { return (ProviderOption) provider.getSelectedItem(); }
    private int indexOf(String providerId) { for (int i = 0; i < providers.size(); i++) if (providerId.equals(providers.get(i).id)) return i; return 0; }
    private void renderProvider(ProviderOption p) {
        modelId.setText(p.defaultModel); endpoint.setText(p.defaultEndpoint); endpointGroup.setVisibility(p.endpointEditable ? View.VISIBLE : View.GONE); keyGroup.setVisibility(p.onDevice ? View.GONE : View.VISIBLE);
        idLabel.setText(p.onDevice ? R.string.model_installed_id_label : R.string.model_id_label); modelId.setHint(p.onDevice ? R.string.model_on_device_id_hint : p.id.equals("doubao") ? R.string.model_doubao_id_hint : R.string.model_id_hint);
        keyLabel.setText(p.apiKeyRequired ? R.string.model_key_required : R.string.model_key_optional);
        detail.setText(p.onDevice ? R.string.model_on_device_guidance
                : p.endpointEditable ? R.string.model_endpoint_lan_guidance : R.string.model_guidance);
    }
    private void provision() {
        ProviderOption p=currentProvider(); String model=modelId.getText().toString().trim();
        if (p.onDevice) {
            if (model.isEmpty()) { status.setText(R.string.model_on_device_missing); return; }
            viewModel.select(new ModelInfo(model, model, "on_device", false, true)); return;
        }
        char[] secret=key.getText().toString().toCharArray(); key.setText(""); viewModel.provision(p.id,model,endpoint.getText().toString().trim(),p.apiKeyRequired,secret);
    }
    private void toggleKeyVisibility() { keyVisible=!keyVisible; int at=key.getSelectionEnd(); key.setInputType(InputType.TYPE_CLASS_TEXT | (keyVisible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD)); key.setTypeface(key.getTypeface()); if(at>=0&&at<=key.length())key.setSelection(at); requireView().<ImageButton>findViewById(R.id.model_key_visibility).setImageResource(keyVisible?R.drawable.ic_eye_visible:R.drawable.ic_eye_hidden); }
    private void render(@NonNull ModelViewModel.State v) { rendered=v; renderStatus(v); models.removeAllViews(); if(v.models.isEmpty()) models.addView(muted(getString(R.string.model_empty_with_download_hint))); else for(ModelInfo m:v.models)addModelCard(m,v.busy); renderControls(); }
    private void renderStatus(ModelViewModel.State v) {
        if(v.notice==ModelViewModel.Notice.RUNTIME&&v.runtime!=null){ ModelRuntimeStatus r=v.runtime; int backend=r.backend==ModelRuntimeStatus.BACKEND_ON_DEVICE?R.string.model_backend_device:r.backend==ModelRuntimeStatus.BACKEND_CLOUD?R.string.model_backend_cloud:R.string.model_backend_none; String error=r.lastErrorCode==0?"":getString(R.string.model_runtime_error,r.lastErrorCode); status.setText(getString(R.string.model_runtime,getString(backend),r.activeModelId==null?getString(R.string.none):r.activeModelId,r.ready,error)); return; }
        switch(v.notice){case MISSING_KEY:status.setText(R.string.model_missing_key);break;case MISSING_MODEL_ID:status.setText(R.string.model_missing_model_id);break;case INVALID_MODEL_ID:status.setText(R.string.model_invalid_model_id);break;case PROVISIONING:status.setText(R.string.model_provisioning);break;case SAVED:status.setText(R.string.model_saved_active);viewModel.refresh();break;case SAVE_FAILED:status.setText(getString(R.string.model_save_failed,v.code));break;case TESTING:status.setText(R.string.model_testing);break;case TEST_SUCCEEDED:status.setText(getString(R.string.model_test_success,v.code));break;case TEST_FAILED:status.setText(getString(R.string.model_test_failed_code,v.code));break;case SWITCHING:status.setText(R.string.model_switching);break;case SWITCH_FAILED:status.setText(getString(R.string.model_switch_failed,v.code));break;case HOST_UNAVAILABLE:status.setText(R.string.host_not_connected);break;default:if(v.runtime==null)status.setText(R.string.model_waiting);}
    }
    private void addModelCard(ModelInfo m, boolean busy) { LinearLayout c=new LinearLayout(requireContext());c.setOrientation(LinearLayout.VERTICAL);c.setBackgroundResource(R.drawable.bg_card);c.setPadding(dp(15),dp(13),dp(15),dp(13)); TextView t=new TextView(requireContext());t.setText(getString(R.string.model_card_title,m.active?getString(R.string.model_active_prefix):getString(R.string.model_inactive_prefix),m.displayName));t.setTextSize(16);t.setTypeface(Typeface.DEFAULT_BOLD);t.setTextColor(color(R.color.matrix_text));c.addView(t);c.addView(muted(getString(R.string.model_card_detail,m.modelId,m.providerId,m.available?"":getString(R.string.model_unavailable_suffix))),top(4));Button b=new Button(requireContext());b.setText(m.active?R.string.model_current:R.string.model_select);b.setEnabled(connected&&!busy&&!m.active&&m.available);b.setBackgroundResource(m.active?R.drawable.bg_outline:R.drawable.bg_primary);b.setTextColor(color(m.active?R.color.matrix_primary_dark:android.R.color.white));b.setOnClickListener(v->viewModel.select(m));c.addView(b,top(9));models.addView(c,space()); }
    private void renderControls() { boolean busy=rendered!=null&&rendered.busy; save.setEnabled(connected&&!busy); test.setEnabled(connected&&!busy&&!currentProvider().onDevice); refresh.setEnabled(connected&&!busy); }
    private TextView muted(String s){TextView v=new TextView(requireContext());v.setText(s);v.setTextColor(color(R.color.matrix_muted));v.setTextSize(13);return v;} private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.topMargin=dp(n);return p;} private LinearLayout.LayoutParams space(){LinearLayout.LayoutParams p=top(0);p.bottomMargin=dp(9);return p;} private int color(int r){return ContextCompat.getColor(requireContext(),r);} private int dp(int v){return activity().dp(v);} private LauncherActivity activity(){return (LauncherActivity)requireActivity();}
    private static final class ProviderOption { final String id,label,defaultModel,defaultEndpoint; final boolean apiKeyRequired,endpointEditable,onDevice; ProviderOption(String i,String l,String m,String e,boolean key,boolean endpoint,boolean device){id=i;label=l;defaultModel=m;defaultEndpoint=e;apiKeyRequired=key;endpointEditable=endpoint;onDevice=device;} }
}
