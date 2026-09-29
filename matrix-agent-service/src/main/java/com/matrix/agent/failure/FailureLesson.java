package com.matrix.agent.failure;

import java.util.List;

/** Versioned, host-rendered diagnostic advice; never a user fact or an authorization. */
public record FailureLesson(int version, Code lessonCode, List<Integer> evidenceRefs) {
    public static final int VERSION = 1;

    public enum Code {
        UNKNOWN("UNKNOWN", "", FailureEvidence.Signal.UNKNOWN),
        CHECK_PARAMETERS("PARAMETERS", "再次调用前核对参数契约；信息不足时询问用户。",
                FailureEvidence.Signal.PARAMETER_REJECTED),
        RESPECT_CAPABILITY_DENIAL("POLICY", "能力被拒绝时停止尝试，不得绕过策略或推断授权。",
                FailureEvidence.Signal.CAPABILITY_REJECTED),
        VERIFY_RESULT("VERIFICATION", "核验失败时不要宣称成功；重新读取状态并说明不确定性。",
                FailureEvidence.Signal.VERIFICATION_FAILED),
        RECONCILE_UNKNOWN("VERIFICATION", "结果未知时先回读，不要直接重复可能已生效的写操作。",
                FailureEvidence.Signal.EXECUTION_UNKNOWN);

        private final String category;
        private final String advice;
        private final FailureEvidence.Signal required;
        Code(String category, String advice, FailureEvidence.Signal required) {
            this.category = category;
            this.advice = advice;
            this.required = required;
        }
        public String category() { return category; }
        public String advice() { return advice; }
        public boolean supports(FailureEvidence.Item item) { return item.signal() == required; }
    }

    public FailureLesson {
        if (version != VERSION || lessonCode == null) throw new IllegalArgumentException("lesson version/code");
        evidenceRefs = List.copyOf(evidenceRefs);
        if (evidenceRefs.size() > 3 || evidenceRefs.stream().distinct().count() != evidenceRefs.size()
                || evidenceRefs.stream().anyMatch(ref -> ref < 0 || ref >= 32)
                || (lessonCode == Code.UNKNOWN) != evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("lesson evidence references");
        }
    }

    public boolean groundedIn(FailureEvidence evidence) {
        return lessonCode != Code.UNKNOWN && evidenceRefs.stream().allMatch(ref ->
                ref < evidence.items().size() && lessonCode.supports(evidence.items().get(ref)));
    }

    public static FailureLesson unknown() { return new FailureLesson(VERSION, Code.UNKNOWN, List.of()); }

    /** The frozen no-model baseline. Models must not invent a more specific cause. */
    public static FailureLesson deterministic(FailureEvidence evidence) {
        for (var item : evidence.items()) {
            for (Code code : Code.values()) {
                if (code != Code.UNKNOWN && code.supports(item)) {
                    return new FailureLesson(VERSION, code, List.of(item.ref()));
                }
            }
        }
        return unknown();
    }
}
