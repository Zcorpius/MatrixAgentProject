package com.matrix.agent.launcher.presentation;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.api.agent.AgentTaskEvent;
import com.matrix.agent.api.agent.AgentTaskSnapshot;
import com.matrix.agent.api.common.AgentTaskState;
import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.R;

/** Presentation-only task desk; task authority and state remain entirely with Host via SDK. */
public final class AgentTaskFragment extends Fragment {
    private EditText command; private TextView taskStatus, eventLog; private Button submit, cancel, refresh;
    private AgentTaskViewModel viewModel; @Nullable private AgentTaskViewModel.State rendered; private boolean connected;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(requireActivity(), activity().viewModelFactory()).get(AgentTaskViewModel.class);
        ScrollView scroll = new ScrollView(requireContext()); scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(requireContext()); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(22),dp(20),dp(32)); scroll.addView(root);
        root.addView(eyebrow("AGENT DESK")); root.addView(heading("任务工作台",31),top(7)); root.addView(copy("把自然语言意图交给 Host；执行身份、调度与可审计运行轨迹始终由系统侧保存。"),top(6));
        LinearLayout composer = new LinearLayout(requireContext()); composer.setOrientation(LinearLayout.VERTICAL); composer.setBackgroundResource(R.drawable.bg_task_composer); composer.setPadding(dp(18),dp(16),dp(18),dp(16));
        TextView label = new TextView(requireContext()); label.setText("NEW INTENT"); label.setTextColor(0xFFE3C68F); label.setTextSize(10); label.setTypeface(Typeface.DEFAULT_BOLD); composer.addView(label);
        command = new EditText(requireContext()); command.setHint(R.string.agent_command_hint); command.setHintTextColor(0xFFBDB3A5); command.setTextColor(0xFFFFFAF0); command.setTextSize(16); command.setMinLines(3); command.setGravity(android.view.Gravity.TOP); command.setBackgroundColor(android.graphics.Color.TRANSPARENT); command.setPadding(0,dp(9),0,dp(4)); composer.addView(command);
        submit=button(R.string.agent_submit,true,v->viewModel.submit(command.getText().toString())); composer.addView(submit,top(8)); root.addView(composer,top(20));
        root.addView(eyebrow("QUICK START"),top(21)); LinearLayout samples = new LinearLayout(requireContext()); samples.setOrientation(LinearLayout.HORIZONTAL); samples.addView(sample("空调",R.string.agent_sample_climate_value),weight()); samples.addView(sample("导航",R.string.agent_sample_navigation_value),weightWithStart()); samples.addView(sample("偏好",R.string.agent_sample_preference_value),weightWithStart()); root.addView(samples,top(8));
        root.addView(eyebrow("CURRENT RUN"),top(24)); taskStatus=new TextView(requireContext()); taskStatus.setBackgroundResource(R.drawable.bg_card); taskStatus.setPadding(dp(16),dp(14),dp(16),dp(14)); taskStatus.setTextColor(color(R.color.matrix_text)); taskStatus.setTextSize(14); taskStatus.setMinHeight(dp(84)); root.addView(taskStatus,top(8));
        LinearLayout utilities=new LinearLayout(requireContext()); utilities.setOrientation(LinearLayout.HORIZONTAL); cancel=button(R.string.agent_cancel,false,v->viewModel.cancel()); refresh=button(R.string.agent_refresh,false,v->viewModel.refresh()); utilities.addView(cancel,weight()); utilities.addView(refresh,weightWithStart()); root.addView(utilities,top(9));
        root.addView(eyebrow("ACTIVITY"),top(25)); eventLog=new TextView(requireContext()); eventLog.setTextIsSelectable(true); eventLog.setTextColor(color(R.color.matrix_muted)); eventLog.setTextSize(13); eventLog.setLineSpacing(dp(3),1f); ScrollView events=new ScrollView(requireContext()); events.setBackgroundResource(R.drawable.bg_card); events.setPadding(dp(15),dp(12),dp(15),dp(12)); events.addView(eventLog); root.addView(events,height(dp(230),12));
        viewModel.state().observe(getViewLifecycleOwner(),this::render); new ViewModelProvider(requireActivity(),activity().viewModelFactory()).get(LauncherViewModel.class).connectionState().observe(getViewLifecycleOwner(),v->{connected=viewModel.isHostConnected();renderControls();if(connected)viewModel.refresh();}); return scroll;
    }
    private void render(@NonNull AgentTaskViewModel.State value){rendered=value;renderStatus(value);StringBuilder events=new StringBuilder();for(AgentTaskEvent event:value.events)events.append(eventText(event)).append("\n\n");eventLog.setText(events.length()==0?"尚没有运行轨迹。提交一个任务后，Host 的安全事件会显示在这里。":events);renderControls();}
    private void renderStatus(AgentTaskViewModel.State value){AgentTaskSnapshot snapshot=value.snapshot;if(snapshot!=null){String text=getString(R.string.agent_snapshot,snapshot.taskId,stateName(snapshot.state),snapshot.lastSequence,snapshot.errorCode);if(snapshot.pendingConfirmationId!=null)text+=getString(R.string.agent_pending_confirmation,snapshot.pendingConfirmationId);if(snapshot.safeText!=null&&!snapshot.safeText.isEmpty())text+="\n\n"+snapshot.safeText;taskStatus.setText(text);return;}switch(value.notice){case ENTER_TASK:taskStatus.setText(R.string.agent_enter_task);break;case SUBMITTING:taskStatus.setText(R.string.agent_submitting);break;case SUBMIT_FAILED:taskStatus.setText(R.string.agent_submit_failed);break;case REJECTED:taskStatus.setText(getString(R.string.agent_rejected,value.code));break;case ACCEPTED:taskStatus.setText(getString(R.string.agent_accepted,value.taskId,stateName(value.code)));break;case SNAPSHOT_UNAVAILABLE:taskStatus.setText(R.string.agent_snapshot_unavailable);break;case SUBSCRIBE_FAILED:taskStatus.setText(getString(R.string.agent_subscribe_failed,"SDK"));break;case CANCELLING:taskStatus.setText(R.string.agent_cancelling);break;case CANCEL_RESULT:taskStatus.setText(getString(R.string.agent_cancel_result,value.code,getString(R.string.state_unknown,value.code)));break;case NO_ACTIVE_TASK:taskStatus.setText(R.string.agent_no_active_cancel);break;default:if(value.taskId==null)taskStatus.setText("准备就绪。输入一个意图，开始一次可追溯的 Agent 运行。");}}
    private void renderControls(){boolean active=rendered!=null&&rendered.taskId!=null;submit.setEnabled(connected);refresh.setEnabled(connected&&active);cancel.setEnabled(connected&&active);}
    private String eventText(AgentTaskEvent e){String safe=e.safePayload==null||e.safePayload.isEmpty()?getString(R.string.safe_detail_empty):e.safePayload;return getString(R.string.agent_event,e.sequence,eventName(e.type),stateName(e.state),safe);}
    private String eventName(int type){switch(type){case AgentTaskEvent.TYPE_STATE_CHANGED:return getString(R.string.event_state_changed);case AgentTaskEvent.TYPE_TEXT_DELTA:return getString(R.string.event_text_delta);case AgentTaskEvent.TYPE_CONFIRMATION_REQUEST:return getString(R.string.event_confirmation);case AgentTaskEvent.TYPE_RESYNC_REQUIRED:return getString(R.string.event_resync);default:return getString(R.string.event_unknown,type);}}
    private String stateName(int state){switch(state){case AgentTaskState.ACCEPTED:return getString(R.string.state_accepted);case AgentTaskState.RUNNING:return getString(R.string.state_running);case AgentTaskState.WAITING_CONFIRMATION:return getString(R.string.state_waiting_confirmation);case AgentTaskState.DEFERRED:return getString(R.string.state_deferred);case AgentTaskState.COMPLETED:return getString(R.string.state_completed);case AgentTaskState.PARTIALLY_COMPLETED:return getString(R.string.state_partially_completed);case AgentTaskState.REJECTED:return getString(R.string.state_rejected);case AgentTaskState.FAILED:return getString(R.string.state_failed);case AgentTaskState.CANCELLED:return getString(R.string.state_cancelled);case AgentTaskState.EXECUTION_UNKNOWN:return getString(R.string.state_unknown_execution);default:return getString(R.string.state_unknown,state);}}
    private TextView eyebrow(String text){TextView v=new TextView(requireContext());v.setText("●  "+text);v.setTextColor(color(R.color.matrix_accent));v.setTextSize(11);v.setTypeface(Typeface.DEFAULT_BOLD);return v;}private TextView heading(String text,int size){TextView v=new TextView(requireContext());v.setText(text);v.setTextColor(color(R.color.matrix_text));v.setTypeface(Typeface.create(Typeface.SERIF,Typeface.BOLD));v.setTextSize(size);return v;}private TextView copy(String text){TextView v=new TextView(requireContext());v.setText(text);v.setTextColor(color(R.color.matrix_muted));v.setTextSize(14);v.setLineSpacing(dp(3),1f);return v;}private Button sample(String title,int value){Button b=button(title,false,v->command.setText(value));b.setTextColor(color(R.color.matrix_primary_dark));return b;}private Button button(int text,boolean primary,View.OnClickListener listener){return button(getString(text),primary,listener);}private Button button(String text,boolean primary,View.OnClickListener listener){Button b=new Button(requireContext());b.setText(text);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackgroundResource(primary?R.drawable.bg_primary:R.drawable.bg_outline);b.setTextColor(color(primary?android.R.color.white:R.color.matrix_primary_dark));b.setOnClickListener(listener);return b;}private LinearLayout.LayoutParams top(int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.topMargin=dp(margin);return p;}private LinearLayout.LayoutParams height(int h,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,h);p.topMargin=dp(margin);return p;}private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,dp(44),1f);}private LinearLayout.LayoutParams weightWithStart(){LinearLayout.LayoutParams p=weight();p.leftMargin=dp(7);return p;}private int dp(int v){return activity().dp(v);}private int color(int id){return ContextCompat.getColor(requireContext(),id);}private LauncherActivity activity(){return (LauncherActivity)requireActivity();}
}
