package com.matrix.agent.identity;

import android.util.Log;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Actor → userId 字面量映射工具。
 *
 * <p>ProviderContext / MemoryStore 都用 {@code "demo-driver"} / {@code "demo-passenger"}
 * 字面量;Audit / Memory 引用同一字面量,确保 Room TrajectoryEntity.userId 列
 * 与 SharedPreferences MemoryStore 主键兼容(一次性迁移)。
 *
 * <p>后续版本接 OccupantZone / Android User 时,这里改读 CarOccupantZoneManager。
 */
public final class ActorUsers {
    private static final String TAG = "MatrixAgent";

    public static final String USER_DRIVER = "demo-driver";
    public static final String USER_PASSENGER = "demo-passenger";
    public static final String USER_GLOBAL = "demo-global";

    private ActorUsers() {}

    public static String userIdOf(Actor actor) {
        if (actor == Actor.DRIVER) return USER_DRIVER;
        if (actor == Actor.PASSENGER) return USER_PASSENGER;
        Log.w(TAG, "[ActorUsers] unknown actor=" + actor + ", fallback=USER_GLOBAL");
        return USER_GLOBAL;
    }

    public static String userIdOf(AgentRequest request) {
        if (request == null) return USER_GLOBAL;
        return userIdOf(request.getActor());
    }

    /** One owner inventory for memory, conversation, audit and deferred reset. */
    public static List<String> allKnownUserIds() {
        LinkedHashSet<String> users = new LinkedHashSet<>();
        for (Actor actor : Actor.values()) users.add(userIdOf(actor));
        users.add(USER_GLOBAL);
        return List.copyOf(users);
    }
}
