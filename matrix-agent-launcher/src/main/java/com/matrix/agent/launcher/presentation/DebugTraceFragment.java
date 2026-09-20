package com.matrix.agent.launcher.presentation;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.R;

import java.util.List;

/**
 * 调试轨迹页（评估 v1.0 §4.3 契约 2）：独立的"调试轨迹"视图。
 * 仅在 {@code BuildConfig.MATRIX_DEBUG_TRACE_UI=true} 的构建中挂载入口
 * （LauncherActivity 按门控显示导航项）；本 Fragment 自身也做 BuildConfig 复查。
 */
public final class DebugTraceFragment extends Fragment {

    private LinearLayout eventRows;
    private TextView statusLine;
    private DebugTraceViewModel viewModel;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup parent, @Nullable Bundle savedInstanceState) {
        if (!com.matrix.agent.launcher.BuildConfig.MATRIX_DEBUG_TRACE_UI) {
            // 双侧自门控（契约 1）：本 Fragment 被意外路由到时渲染空页
            return new View(requireContext());
        }
        viewModel = new ViewModelProvider(requireActivity(), activity().viewModelFactory())
                .get(DebugTraceViewModel.class);

        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(48), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(requireContext());
        title.setText("调试轨迹");
        title.setTextSize(24);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        title.setTextColor(ContextCompat.getColor(requireContext(),
                R.color.matrix_primary_dark));
        root.addView(title);

        statusLine = new TextView(requireContext());
        statusLine.setTextSize(11);
        statusLine.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_muted));
        statusLine.setPadding(0, dp(4), 0, dp(8));
        root.addView(statusLine);

        Button clear = new Button(requireContext());
        clear.setText("清空");
        clear.setTextSize(12);
        clear.setOnClickListener(v -> viewModel.clear());
        root.addView(clear);

        eventRows = new LinearLayout(requireContext());
        eventRows.setOrientation(LinearLayout.VERTICAL);
        root.addView(eventRows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        return scroll;
    }

    private void render(DebugTraceViewModel.State state) {
        statusLine.setText(state.connected
                ? "已连接 · " + state.events.size() + " 事件"
                : "Host 未连接");

        eventRows.removeAllViews();
        for (DebugTraceWireEvent event : state.events) {
            eventRows.addView(buildRow(event));
        }
        if (eventRows.getParent() instanceof ScrollView sv) {
            sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private View buildRow(DebugTraceWireEvent event) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(6), dp(10), dp(6));

        android.graphics.drawable.GradientDrawable card =
                new android.graphics.drawable.GradientDrawable();
        card.setCornerRadius(dp(6));
        card.setColor(phaseColor(event.phase));
        row.setBackground(card);

        // 头行：阶段 + 分片
        TextView header = new TextView(requireContext());
        header.setTextSize(10);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(ContextCompat.getColor(requireContext(),
                R.color.matrix_primary_dark));
        String part = event.partCount > 1
                ? " [" + (event.partIndex + 1) + "/" + event.partCount + "]" : "";
        header.setText(event.phase + part + " · " + event.taskId);
        row.addView(header);

        // 正文
        TextView body = new TextView(requireContext());
        body.setTextSize(11);
        body.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_text));
        body.setText(event.payload);
        row.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private int phaseColor(String phase) {
        if (phase == null) return 0xFFF0F2F1;
        switch (phase) {
            case "MODEL_REASONING": return 0xFFE8F0FE; // 蓝——思考
            case "MODEL_PROPOSED": return 0xFFFFF3E0;   // 橙——模型建议
            case "POLICY_DECIDED": return 0xFFE8F5E9;   // 绿——策略
            case "REQUEST_DELIVERED": return 0xFFF3E5F5; // 紫——送达
            case "DEVICE_VERIFIED": return 0xFFE0F2F1;  // 青——核验
            case "ROUND_START":
            case "ROUND_END": return 0xFFECEFF1;         // 灰——边界
            default: return 0xFFF0F2F1;
        }
    }

    @Override public void onStart() {
        super.onStart();
        if (viewModel != null) viewModel.start();
    }

    @Override public void onStop() {
        if (viewModel != null) viewModel.stop();
        super.onStop();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }
    private LauncherActivity activity() { return (LauncherActivity) requireActivity(); }
}
