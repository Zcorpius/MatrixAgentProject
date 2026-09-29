package com.matrix.agent.api.schedule;
import com.matrix.agent.api.schedule.ScheduleSpec;
import com.matrix.agent.api.schedule.ScheduleCalendarResult;
import com.matrix.agent.api.schedule.ScheduleInfo;
import com.matrix.agent.api.schedule.ScheduleRunInfo;
import com.matrix.agent.api.schedule.ScheduleStepInfo;
import com.matrix.agent.api.schedule.ScheduleTemplateInfo;
import com.matrix.agent.api.schedule.ScheduleMutation;
import com.matrix.agent.api.schedule.SchedulePreview;
import com.matrix.agent.api.schedule.SchedulePage;
import com.matrix.agent.api.schedule.ScheduleRunPage;
import com.matrix.agent.api.schedule.ScheduleReadiness;
import com.matrix.agent.api.schedule.IScheduleCallback;

/** Every transaction revalidates trusted caller/owner. Client IDs never supply execution identity. */
interface IScheduleService {
    ScheduleReadiness getReadiness();
    SchedulePreview preview(in ScheduleSpec spec);
    ScheduleMutation create(in ScheduleSpec spec, String operationId);
    ScheduleMutation update(String scheduleId, long expectedRevision, in ScheduleSpec spec, String operationId);
    ScheduleMutation control(String scheduleId, String runId, long expectedRevision, int operation, String operationId);
    ScheduleInfo get(String scheduleId);
    SchedulePage list(String cursor, int limit);
    ScheduleRunInfo getRun(String runId);
    ScheduleRunPage listRuns(String scheduleId, String cursor, int limit);
    List<ScheduleStepInfo> getSteps(String runId);
    List<ScheduleTemplateInfo> listTemplates();
    void subscribe(long afterSequence, IScheduleCallback callback);
    void unsubscribe(IScheduleCallback callback);
    ScheduleCalendarResult calendar(String capability, String parametersJson, String operationId);
    ScheduleCalendarResult bindCalendar(long eventId, long originalStartMillis, String reminderOwner, String operationId);
    ScheduleCalendarResult calendarBindings();
}
