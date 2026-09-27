package com.matrix.agent.launcher.presentation;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.launcher.R;

/** Shared role, message text, status and steer rendering. Hosts own scrolling and commands. */
public final class ConversationMessageRenderer {
    private final Context context;
    public ConversationMessageRenderer(Context context) { this.context = context; }
    private int color(int resource) { return ContextCompat.getColor(context, resource); }
    public View create(ConversationViewModel.UiMessage message, int availableWidth,
            java.util.function.Consumer<ConversationViewModel.UiMessage> longPress) {
        float density = context.getResources().getDisplayMetrics().density;
        int screenWidth = availableWidth;
        boolean isUser = message.role() == ConversationMessage.ROLE_USER;
        boolean isSystem = message.role() == ConversationMessage.ROLE_SYSTEM;
        int avatarSize = (int) (42 * density + .5f);
        int avatarGap = (int) (8 * density + .5f);
        int maxBubbleWidth = Math.max(1, Math.min((int) (screenWidth * 0.72f),
                screenWidth - avatarSize - avatarGap) - (int) (24 * density));

        // 外层负责微信式左右编排；气泡只承载内容，不再承载“用户/助手”身份文字。
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.TOP);
        row.setPadding(0, (int) (7 * density + .5f), 0,
                (int) (7 * density + .5f));
        row.setTag(R.id.conversation_row_sequence, message.sequence());
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = (int) (3 * density + .5f);
        row.setLayoutParams(rowParams);

        LinearLayout bubbleContent = new LinearLayout(context);
        bubbleContent.setOrientation(LinearLayout.VERTICAL);
        int bubblePadH = (int) (12 * density + .5f);
        int bubblePadV = (int) (9 * density + .5f);
        bubbleContent.setPadding(bubblePadH, bubblePadV, bubblePadH, bubblePadV);

        android.graphics.drawable.GradientDrawable bubble =
                new android.graphics.drawable.GradientDrawable();
        bubble.setCornerRadius(15 * density);
        if (isUser) {
            bubble.setColor(color(R.color.matrix_chat_user_bubble));
            bubble.setStroke(1, color(R.color.matrix_chat_user_bubble_stroke));
        } else if (isSystem) {
            bubble.setColor(color(R.color.matrix_chat_system_bubble));
        } else {
            bubble.setColor(color(R.color.matrix_chat_assistant_bubble));
            bubble.setStroke(1, color(R.color.matrix_chat_assistant_bubble_stroke));
        }
        bubbleContent.setBackground(bubble);
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        TextView body = new TextView(context);
        body.setTextSize(14);
        body.setTextColor(ContextCompat.getColor(context,
                isSystem ? R.color.matrix_muted : R.color.matrix_text));
        body.setText(message.text());
        body.setMaxWidth(maxBubbleWidth);
        bubbleContent.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (isUser && !isTerminalStatus(message.status())) {
            TextView status = new TextView(context);
            status.setTextSize(10);
            status.setTextColor(ContextCompat.getColor(context, R.color.matrix_muted));
            status.setText(statusText(message.status()));
            status.setMaxWidth(maxBubbleWidth);
            bubbleContent.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (isUser && message.inputKind() == ConversationMessage.INPUT_STEER) {
            TextView steerNote = new TextView(context);
            steerNote.setTextSize(10);
            steerNote.setTypeface(Typeface.DEFAULT_BOLD);
            steerNote.setTextColor(ContextCompat.getColor(context, R.color.matrix_primary));
            steerNote.setText(steerNoteText(message.steerDeliveryState(), message.status()));
            steerNote.setMaxWidth(maxBubbleWidth);
            bubbleContent.addView(steerNote, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (isSystem) {
            row.addView(weightSpacer());
            row.addView(bubbleContent, bubbleParams);
            row.addView(weightSpacer());
        } else if (isUser) {
            row.addView(weightSpacer());
            row.addView(bubbleContent, bubbleParams);
            row.addView(avatarView(R.drawable.avatar_user_penguin,
                    R.string.conversation_avatar_user, R.color.matrix_chat_avatar_user_bg),
                    avatarLayoutParams(avatarSize, avatarGap, true));
        } else {
            row.addView(avatarView(R.drawable.avatar_assistant_matrix,
                    R.string.conversation_avatar_assistant, R.color.matrix_chat_avatar_assistant_bg),
                    avatarLayoutParams(avatarSize, avatarGap, false));
            row.addView(bubbleContent, bubbleParams);
            row.addView(weightSpacer());
        }

        row.setOnLongClickListener(view -> {
            if (longPress != null) longPress.accept(message);
            return true;
        });
        return row;
    }

    private View weightSpacer() {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return spacer;
    }

    private LinearLayout.LayoutParams avatarLayoutParams(int size, int gap, boolean userSide) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        if (userSide) params.setMarginStart(gap);
        else params.setMarginEnd(gap);
        return params;
    }

    private android.widget.ImageView avatarView(int drawableRes, int descriptionRes,
            int backgroundColorRes) {
        android.widget.ImageView avatar = new android.widget.ImageView(context);
        avatar.setImageResource(drawableRes);
        avatar.setContentDescription(context.getString(descriptionRes));
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(color(backgroundColorRes));
        avatar.setBackground(mask);
        avatar.setClipToOutline(true);
        return avatar;
    }

    private String steerNoteText(int steerDeliveryState, int status) {
        if (status == ConversationMessage.STATUS_FAILED) {
            return context.getString(R.string.conversation_steer_failed);
        }
        switch (steerDeliveryState) {
            case ConversationMessage.STEER_DELIVERY_OFFERED:
                return context.getString(R.string.conversation_steer_offered);
            case ConversationMessage.STEER_DELIVERY_PENDING:
                // 恢复后仍 PENDING：诚实显示“未确认”，宿主终态不谎称并入
                return isTerminalStatus(status)
                        ? context.getString(R.string.conversation_steer_recovered)
                        : context.getString(R.string.conversation_steer_pending);
            default:
                return context.getString(R.string.conversation_steer_failed);
        }
    }

    private boolean isTerminalStatus(int status) {
        return status != ConversationMessage.STATUS_ACCEPTED
                && status != ConversationMessage.STATUS_RUNNING;
    }

    private String statusText(int status) {
        if (status == ConversationMessage.STATUS_ACCEPTED) {
            return context.getString(R.string.conversation_status_accepted);
        }
        if (status == ConversationMessage.STATUS_RUNNING) {
            return context.getString(R.string.conversation_status_running);
        }
        return "";
    }

}
