package com.matrix.agent.launcher.presentation;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.launcher.R;
import java.util.List;

/** Shared, opt-in process projection; its host supplies the available conversation width. */
public final class ConversationTraceRenderer {
    private final Context context;
    public ConversationTraceRenderer(Context context) { this.context = context; }
    private int color(int resource) { return ContextCompat.getColor(context, resource); }
    public View create(ConversationViewModel.UiMessage userMessage, int availableWidth) {
        float density = context.getResources().getDisplayMetrics().density;
        int screenWidth = availableWidth;
        // 过程是辅助信息而非第三种聊天气泡。收起时只保留居中的细分隔标题；展开后
        // 才在其下挂出独立详情卡，避免和用户/助手的会话层级争夺注意力。
        int maxWidth = (int) (screenWidth * 0.86f);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, (int) (6 * density + .5f), 0, (int) (6 * density + .5f));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(maxWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = (int) (3 * density + .5f);
        row.setLayoutParams(params);
        row.addView(buildDebugTracePanel(userMessage.debugTraces(), maxWidth, density));
        return row;
    }

    /**
     * 过程组：一轮 AI 回复把连续的思考、工具请求和工具结果编排成一个
     * 可折叠组；组内“思考”和每个 capability 又各自独立折叠。这里不是 Logcat 的镜像：
     * 只展示写时已脱敏的事实投影；所有构建变体均由 matrix.debugTraceUi 显式控制。
     */
    private View buildDebugTracePanel(List<DebugTraceWireEvent> rawEvents, int maxBubbleWidth,
            float density) {
        List<DebugTraceTimeline.Node> nodes = DebugTraceTimeline.from(rawEvents);
        int thinkingCount = 0;
        int toolCount = 0;
        for (DebugTraceTimeline.Node node : nodes) {
            if (node.kind == DebugTraceTimeline.Kind.THINKING) thinkingCount++;
            if (node.kind == DebugTraceTimeline.Kind.TOOL) toolCount++;
        }

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        String groupText = thinkingCount == 0
                ? context.getString(R.string.conversation_debug_trace_group_without_reasoning, toolCount)
                : context.getString(R.string.conversation_debug_trace_group, thinkingCount, toolCount);

        // 收起态使用轻量过程分隔器：没有背景、没有描边、没有信息图标，只把
        // 标题置于两条细线之间。箭头是唯一的交互暗示，过程不会再误读成助手气泡。
        LinearLayout header = new LinearLayout(context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(0, (int) (3 * density + .5f), 0, (int) (3 * density + .5f));
        View leftRule = traceRule(density);
        header.addView(leftRule, new LinearLayout.LayoutParams(0, Math.max(1, (int) density), 1f));
        TextView title = new TextView(context);
        title.setText(groupText);
        title.setContentDescription(groupText);
        title.setTextSize(10);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.matrix_trace_group_title));
        title.setPadding((int) (10 * density + .5f), 0, (int) (5 * density + .5f), 0);
        header.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.ImageButton affordance = traceDisclosure(false, density, groupText);
        LinearLayout.LayoutParams affordanceParams = new LinearLayout.LayoutParams(
                (int) (22 * density + .5f), (int) (22 * density + .5f));
        affordanceParams.rightMargin = (int) (5 * density + .5f);
        header.addView(affordance, affordanceParams);
        View rightRule = traceRule(density);
        header.addView(rightRule, new LinearLayout.LayoutParams(0, Math.max(1, (int) density), 1f));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout detail = new LinearLayout(context);
        detail.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = roundedBackground(color(R.color.matrix_trace_detail_bg),
                color(R.color.matrix_trace_detail_stroke), 9 * density);
        detail.setBackground(background);
        int inset = (int) (8 * density + .5f);
        detail.setPadding(inset, inset, inset, inset);
        // 静态消息默认收起；进行中的消息才能自动展开。Matrix 只持久化 final，
        // 因而重进会话保持稳定、可预期的收起状态。
        detail.setVisibility(View.GONE);
        for (int i = 0; i < nodes.size(); i++) {
            detail.addView(buildDebugTraceNode(nodes.get(i), i == nodes.size() - 1,
                    maxBubbleWidth - inset * 2, density));
        }
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = (int) (8 * density + .5f);
        panel.addView(detail, detailParams);
        View.OnClickListener toggleGroup = ignored -> {
            boolean expanding = detail.getVisibility() != View.VISIBLE;
            detail.setVisibility(expanding ? View.VISIBLE : View.GONE);
            updateTraceDisclosure(affordance, expanding, groupText);
        };
        header.setOnClickListener(toggleGroup);
        affordance.setOnClickListener(toggleGroup);
        return panel;
    }

    private View traceRule(float density) {
        View rule = new View(context);
        rule.setBackgroundColor(color(R.color.matrix_trace_group_stroke));
        return rule;
    }

    /**
     * 无底座的 Material 图标：Matrix 的收起态向左，展开态向下。
     * 它没有背景或文字，排布仍由 Matrix 的居中分隔器与节点标题负责。
     */
    private android.widget.ImageButton traceDisclosure(boolean expanded, float density,
            String subject) {
        android.widget.ImageButton disclosure = new android.widget.ImageButton(context);
        disclosure.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        disclosure.setPadding((int) (2 * density + .5f), (int) (2 * density + .5f),
                (int) (2 * density + .5f), (int) (2 * density + .5f));
        disclosure.setBackground(null);
        updateTraceDisclosure(disclosure, expanded, subject);
        return disclosure;
    }

    private void updateTraceDisclosure(android.widget.ImageButton disclosure, boolean expanded,
            String subject) {
        disclosure.setImageResource(expanded ? R.drawable.ic_trace_disclosure_down
                : R.drawable.ic_trace_disclosure_right);
        disclosure.setContentDescription(subject + "，"
                + (expanded ? context.getString(R.string.conversation_debug_trace_collapse_detail)
                : context.getString(R.string.conversation_debug_trace_expand_detail)));
    }

    private View buildDebugTraceNode(DebugTraceTimeline.Node node, boolean last, int maxWidth,
            float density) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setPadding(0, 0, 0, last ? 0 : (int) (7 * density + .5f));

        LinearLayout rail = new LinearLayout(context);
        rail.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        rail.setOrientation(LinearLayout.VERTICAL);
        TextView dot = new TextView(context);
        dot.setText(node.kind == DebugTraceTimeline.Kind.THINKING ? "✦" : "●");
        dot.setTextSize(12);
        dot.setGravity(android.view.Gravity.CENTER);
        dot.setTextColor(node.kind == DebugTraceTimeline.Kind.TOOL
                ? ContextCompat.getColor(context, node.statusColorRes())
                : color(R.color.matrix_trace_thinking_dot));
        rail.addView(dot, new LinearLayout.LayoutParams((int) (18 * density + .5f),
                (int) (18 * density + .5f)));
        if (!last) {
            View guide = new View(context);
            guide.setBackgroundColor(color(R.color.matrix_trace_guide_line));
            LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(
                    Math.max(1, (int) density), 0, 1f);
            guideParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            rail.addView(guide, guideParams);
        }
        item.addView(rail, new LinearLayout.LayoutParams((int) (18 * density + .5f),
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) (6 * density + .5f), 0, 0, 0);
        LinearLayout nodeHeader = new LinearLayout(context);
        nodeHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setTextSize(11);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.matrix_trace_node_title));
        title.setText(node.title(context));
        nodeHeader.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String nodeTitle = node.title(context);
        android.widget.ImageButton affordance = traceDisclosure(false, density, nodeTitle);
        LinearLayout.LayoutParams nodeAffordanceParams = new LinearLayout.LayoutParams(
                (int) (22 * density + .5f), (int) (22 * density + .5f));
        nodeHeader.addView(affordance, nodeAffordanceParams);
        card.addView(nodeHeader, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView content = new TextView(context);
        content.setTextSize(10);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextColor(color(R.color.matrix_trace_node_content));
        content.setText(node.detail(context));
        content.setMaxWidth(maxWidth - (int) (30 * density + .5f));
        content.setPadding((int) (9 * density + .5f), (int) (5 * density + .5f), 0, 0);
        content.setBackground(roundedBackground(color(R.color.matrix_trace_node_content_bg),
                0x00000000, 5 * density));
        content.setVisibility(View.GONE);
        card.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // 节点标题只承担语义与折叠控制。此前同时渲染两行 preview 和完整 detail，
        // 使同一段 reasoning/tool payload 在展开时重复出现；调试信息应完整保留一次，
        // 而不是用重复文本换取“摘要”。
        View.OnClickListener toggle = ignored -> {
            boolean expanding = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expanding ? View.VISIBLE : View.GONE);
            updateTraceDisclosure(affordance, expanding, nodeTitle);
        };
        nodeHeader.setOnClickListener(toggle);
        affordance.setOnClickListener(toggle);
        item.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return item;
    }

    private GradientDrawable roundedBackground(int color, int strokeColor, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        if (strokeColor != 0x00000000) background.setStroke(1, strokeColor);
        return background;
    }

}
