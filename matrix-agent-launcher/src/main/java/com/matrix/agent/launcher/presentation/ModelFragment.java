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
    private static final List<ProviderOption> PROVIDERS = Arrays.asList(
            cloud("glm", "智谱 GLM", "glm-5.2"), cloud("deepseek", "DeepSeek", "deepseek-v4-flash"),
            cloud("qwen", "阿里通义千问", "qwen3.7-plus"), cloud("kimi", "Moonshot Kimi", "kimi-k2.5"),
            cloud("doubao", "火山方舟 / 豆包", "Endpoint ID"), cloud("anthropic", "Anthropic Claude", "claude-sonnet-4-5"),
            cloud("gemini", "Google Gemini", "gemini-3.5-flash"),
            local("ollama", "Ollama（局域网）", "qwen2.5:7b", "http://127.0.0.1:11434/api/chat"),
            local("lmstudio", "LM Studio（局域网）", "local-model", "http://127.0.0.1:1234/v1/chat/completions"),
            local("vllm", "本地 vLLM（OpenAI 兼容）", "Qwen/Qwen3-8B", "http://127.0.0.1:8000/v1/chat/completions"),
            new ProviderOption("on_device", "已下载的端侧模型", "", "", false, false, true),
            local("custom", "自定义 OpenAI 兼容接口", "", "https://example.com/v1"));

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
        provider = root.findViewById(R.id.model_provider); modelId = root.findViewById(R.id.model_id);
        endpoint = root.findViewById(R.id.model_endpoint); key = root.findViewById(R.id.model_key);
        detail = root.findViewById(R.id.model_provider_detail); status = root.findViewById(R.id.model_status);
        idLabel = root.findViewById(R.id.model_id_label); keyLabel = root.findViewById(R.id.model_key_label);
        endpointGroup = root.findViewById(R.id.model_endpoint_group); keyGroup = root.findViewById(R.id.model_key_group);
        models = root.findViewById(R.id.model_list); save = root.findViewById(R.id.model_save);
        test = root.findViewById(R.id.model_test); refresh = root.findViewById(R.id.model_refresh);
        provider.setDropDownVerticalOffset(dp(8));
        provider.setAdapter(new ArrayAdapter<ProviderOption>(requireContext(), R.layout.item_model_provider, PROVIDERS) {
            @NonNull @Override public View getView(int p, @Nullable View v, @NonNull ViewGroup parent) { TextView t=(TextView)super.getView(p,v,parent); t.setText(PROVIDERS.get(p).label); return t; }
            @NonNull @Override public View getDropDownView(int p, @Nullable View v, @NonNull ViewGroup parent) { TextView t=(TextView)super.getDropDownView(p,v,parent); t.setText(PROVIDERS.get(p).label); return t; }
        });
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { renderProvider(PROVIDERS.get(pos)); }
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
    private static ProviderOption cloud(String id, String label, String model) { return new ProviderOption(id,label,model,"",true,false,false); }
    private static ProviderOption local(String id, String label, String model, String url) { return new ProviderOption(id,label,model,url,false,true,false); }
    private ProviderOption currentProvider() { return (ProviderOption) provider.getSelectedItem(); }
    private int indexOf(String providerId) { for (int i = 0; i < PROVIDERS.size(); i++) if (providerId.equals(PROVIDERS.get(i).id)) return i; return 0; }
    private void renderProvider(ProviderOption p) {
        modelId.setText(p.defaultModel); endpoint.setText(p.defaultEndpoint); endpointGroup.setVisibility(p.endpointEditable ? View.VISIBLE : View.GONE); keyGroup.setVisibility(p.onDevice ? View.GONE : View.VISIBLE);
        idLabel.setText(p.onDevice ? "已安装模型 ID" : "模型 ID"); modelId.setHint(p.onDevice ? "先在“下载”页安装模型" : p.id.equals("doubao") ? "填写推理接入点 Endpoint ID" : "可选；留空使用默认模型");
        keyLabel.setText(p.apiKeyRequired ? "API Key（必填）" : "API Key（可选）");
        detail.setText(p.onDevice ? "端侧模型不需要 API Key。请先从下载页安装，再选择模型 ID。" : p.endpointEditable ? "服务地址仅支持 http/https；Host 会校验并保存配置。" : "使用 Host 内置的受控 Provider 与默认服务地址。");
    }
    private void provision() {
        ProviderOption p=currentProvider(); String model=modelId.getText().toString().trim();
        if (p.onDevice) {
            if (model.isEmpty()) { status.setText("请先在“模型市场”下载端侧模型，再填写或选用模型 ID。"); return; }
            viewModel.select(new ModelInfo(model, model, "on_device", false, true)); return;
        }
        char[] secret=key.getText().toString().toCharArray(); key.setText(""); viewModel.provision(p.id,model,endpoint.getText().toString().trim(),p.apiKeyRequired,secret);
    }
    private void toggleKeyVisibility() { keyVisible=!keyVisible; int at=key.getSelectionEnd(); key.setInputType(InputType.TYPE_CLASS_TEXT | (keyVisible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD)); key.setTypeface(key.getTypeface()); if(at>=0&&at<=key.length())key.setSelection(at); requireView().<ImageButton>findViewById(R.id.model_key_visibility).setImageResource(keyVisible?R.drawable.ic_eye_visible:R.drawable.ic_eye_hidden); }
    private void render(@NonNull ModelViewModel.State v) { rendered=v; renderStatus(v); models.removeAllViews(); if(v.models.isEmpty()) models.addView(muted("尚无可用模型。端侧模型请先在下载页安装。")); else for(ModelInfo m:v.models)addModelCard(m,v.busy); renderControls(); }
    private void renderStatus(ModelViewModel.State v) {
        if(v.notice==ModelViewModel.Notice.RUNTIME&&v.runtime!=null){ ModelRuntimeStatus r=v.runtime; String b=r.backend==ModelRuntimeStatus.BACKEND_ON_DEVICE?"端侧":r.backend==ModelRuntimeStatus.BACKEND_CLOUD?"云端":"未配置"; status.setText("运行时："+b+"\n当前模型："+(r.activeModelId==null?"无":r.activeModelId)+"\n就绪："+r.ready+(r.lastErrorCode==0?"":"\n错误码："+r.lastErrorCode)); return; }
        switch(v.notice){case MISSING_KEY:status.setText("请填写 API Key");break;case MISSING_MODEL_ID:status.setText("请填写模型或 Endpoint ID");break;case INVALID_MODEL_ID:status.setText("模型标识格式不合法");break;case PROVISIONING:status.setText("正在通过安全通道交给 Host…");break;case SAVED:status.setText("配置已保存并启用，正在刷新运行时…");viewModel.refresh();break;case SAVE_FAILED:status.setText("保存失败，错误码="+v.code);break;case TESTING:status.setText("正在验证当前 Provider…");break;case TEST_SUCCEEDED:status.setText("连接验证成功 · "+v.code+" ms");break;case TEST_FAILED:status.setText("连接验证失败，错误码="+v.code);break;case SWITCHING:status.setText("正在切换模型…");break;case SWITCH_FAILED:status.setText("切换失败，错误码="+v.code);break;case HOST_UNAVAILABLE:status.setText("Host 未连接");break;default:if(v.runtime==null)status.setText("等待 Host 连接…");}
    }
    private void addModelCard(ModelInfo m, boolean busy) { LinearLayout c=new LinearLayout(requireContext());c.setOrientation(LinearLayout.VERTICAL);c.setBackgroundResource(R.drawable.bg_card);c.setPadding(dp(15),dp(13),dp(15),dp(13)); TextView t=new TextView(requireContext());t.setText((m.active?"● ":"")+m.displayName);t.setTextSize(16);t.setTypeface(Typeface.DEFAULT_BOLD);t.setTextColor(color(R.color.matrix_text));c.addView(t);c.addView(muted(m.modelId+" · "+m.providerId+(m.available?"":" · 不可用")),top(4));Button b=new Button(requireContext());b.setText(m.active?"当前使用":"选用");b.setEnabled(connected&&!busy&&!m.active&&m.available);b.setBackgroundResource(m.active?R.drawable.bg_outline:R.drawable.bg_primary);b.setTextColor(color(m.active?R.color.matrix_primary_dark:android.R.color.white));b.setOnClickListener(v->viewModel.select(m));c.addView(b,top(9));models.addView(c,space()); }
    private void renderControls() { boolean busy=rendered!=null&&rendered.busy; save.setEnabled(connected&&!busy); test.setEnabled(connected&&!busy&&!currentProvider().onDevice); refresh.setEnabled(connected&&!busy); }
    private TextView muted(String s){TextView v=new TextView(requireContext());v.setText(s);v.setTextColor(color(R.color.matrix_muted));v.setTextSize(13);return v;} private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.topMargin=dp(n);return p;} private LinearLayout.LayoutParams space(){LinearLayout.LayoutParams p=top(0);p.bottomMargin=dp(9);return p;} private int color(int r){return ContextCompat.getColor(requireContext(),r);} private int dp(int v){return activity().dp(v);} private LauncherActivity activity(){return (LauncherActivity)requireActivity();}
    private static final class ProviderOption { final String id,label,defaultModel,defaultEndpoint; final boolean apiKeyRequired,endpointEditable,onDevice; ProviderOption(String i,String l,String m,String e,boolean key,boolean endpoint,boolean device){id=i;label=l;defaultModel=m;defaultEndpoint=e;apiKeyRequired=key;endpointEditable=endpoint;onDevice=device;} }
}
