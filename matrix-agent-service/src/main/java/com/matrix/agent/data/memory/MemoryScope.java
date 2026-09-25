package com.matrix.agent.data.memory;

import com.matrix.agent.identity.VehicleZone;

import java.util.Objects;

/**
 * Memory 双维度隔离键——(userId, zone)。
 *
 * <p>旧版 MemoryStore 主键是单维度 userId("demo-driver" / "demo-passenger");
 * 引入 zone 维度后,同一 userId 可在不同 zone(主驾屏 / 副驾屏)有独立 Memory。
 *
 * <p>兼容契约:{@link #ofLegacy(String)} 把 zone 设为 GLOBAL,等价于旧版二元组行为。
 *
 * <p>持久化层(Room MemoryRecordEntity)用 {@link #storageKey(MemoryLayer, String)} 作主键,
 * 与 SharedPreferences 二元组不冲突——双写、一次性迁移。
 *
 * <p><b>双维度隔离边界</b>:
 * <ul>
 *   <li>PREFERENCE 层({@link LegacyPreferenceMemorySource}):以本 scope 过滤。</li>
 *   <li>WORKING 层只投影 owner/zone 绑定会话中的核验结构化状态。</li>
 *   <li>EPISODIC / SEMANTIC:Room 查询同时按 userId 与规范 zone 过滤。</li>
 * </ul>
 * 同一 userId 在主驾屏 / 副驾屏的 Preference 独立。已确认归属的旧数据在 v14
 * 迁移中映射到对应座舱；无法确认归属的旧行不进入可召回域。
 */
public final class MemoryScope {
    private final String userId;
    private final VehicleZone zone;

    public MemoryScope(String userId, VehicleZone zone) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        this.userId = userId;
        this.zone = zone == null ? VehicleZone.GLOBAL : zone;
    }

    /** 旧版兼容路径:zone=GLOBAL,等价于旧 MemoryStore 二元组语义。 */
    public static MemoryScope ofLegacy(String userId) {
        return new MemoryScope(userId, VehicleZone.GLOBAL);
    }

    public String getUserId() {
        return userId;
    }

    public VehicleZone getZone() {
        return zone;
    }

    /**
     * Room MemoryRecordEntity 主键格式。
     *
     * <p>格式稳定——持久化层与迁移脚本依赖此格式,变更需要 Migration。
     */
    public String storageKey(MemoryLayer layer, String key) {
        return userId + "@" + zone.wireValue() + "#" + layer.wireValue() + "/"
                + (key == null ? "" : key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryScope)) return false;
        MemoryScope that = (MemoryScope) o;
        return userId.equals(that.userId) && zone == that.zone;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, zone);
    }

    @Override
    public String toString() {
        return "MemoryScope{userId=" + userId + ", zone=" + zone + "}";
    }
}
