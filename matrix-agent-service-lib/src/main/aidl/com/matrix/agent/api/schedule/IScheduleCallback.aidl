package com.matrix.agent.api.schedule;
import com.matrix.agent.api.schedule.ScheduleEvent;

/** Content-free ordered invalidations. RESYNC_REQUIRED requests a fresh page snapshot. */
oneway interface IScheduleCallback {
    void onChanged(in ScheduleEvent event);
}
