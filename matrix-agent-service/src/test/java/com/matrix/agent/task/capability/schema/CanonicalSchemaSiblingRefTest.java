package com.matrix.agent.task.capability.schema;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * CanonicalSchema 循环检测修复的回归测试。
 *
 * <p>旧实现 properties/items/allOf/oneOf/anyOf 共享 visited 集合,
 * 两个兄弟 property 引用同一 {@code $defs/Address} 时第二个被误判 cycle。
 * 改为每个 child 独立 visited 快照,兄弟节点互不影响。
 */
public final class CanonicalSchemaSiblingRefTest {

    @Test
    public void siblingPropertiesSharingSame$DefDoesNotTriggerFalseCycle() {
        // $defs/Address 是一个独立的 schema 对象
        CanonicalSchema address = CanonicalSchema.object()
                .property("city", CanonicalSchema.string().build())
                .property("zip", CanonicalSchema.string().build())
                .build();
        Map<String, CanonicalSchema> defs = new LinkedHashMap<>();
        defs.put("Address", address);

        // root 有两个兄弟 property,都 $ref 同一个 Address
        CanonicalSchema root = CanonicalSchema.object()
                .property("from", CanonicalSchema.$ref("#/$defs/Address")
                        .$defs(defs).build())
                .property("to", CanonicalSchema.$ref("#/$defs/Address")
                        .$defs(defs).build())
                .$defs(defs)
                .build();

        assertNotNull("from 应解析到 $defs/Address",
                root.getProperties().get("from").get$RefTarget());
        assertNotNull("to 应解析到 $defs/Address",
                root.getProperties().get("to").get$RefTarget());
        assertTrue("两个兄弟 property 应解析到同一 Address 对象",
                root.getProperties().get("from").get$RefTarget()
                        == root.getProperties().get("to").get$RefTarget());
    }

    @Test
    public void siblingCompositionSharingSame$DefDoesNotTriggerFalseCycle() {
        CanonicalSchema base = CanonicalSchema.object()
                .property("id", CanonicalSchema.integer().build())
                .build();
        Map<String, CanonicalSchema> defs = new LinkedHashMap<>();
        defs.put("Base", base);

        CanonicalSchema root = CanonicalSchema.object()
                .allOf(CanonicalSchema.$ref("#/$defs/Base").$defs(defs).build(),
                        CanonicalSchema.$ref("#/$defs/Base").$defs(defs).build())
                .anyOf(CanonicalSchema.$ref("#/$defs/Base").$defs(defs).build(),
                        CanonicalSchema.$ref("#/$defs/Base").$defs(defs).build())
                .$defs(defs)
                .build();

        assertTrue("两个 allOf 兄弟应解析到同一 Base",
                root.getAllOf().get(0).get$RefTarget() == root.getAllOf().get(1).get$RefTarget());
        assertTrue("两个 anyOf 兄弟应解析到同一 Base",
                root.getAnyOf().get(0).get$RefTarget() == root.getAnyOf().get(1).get$RefTarget());
    }
}
