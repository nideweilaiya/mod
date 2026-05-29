package com.aiworkbench.companion.core.decision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 记忆快照 — 供决策层使用的记忆摘要。
 */
public class MemorySnapshot {

    public List<String> recentEvents = new ArrayList<>();
    public String currentGoal;
    public String ownerLocation;
    /** 当前授权的能力ID集合（评估层只评估授权的能力） */
    public Set<String> authorizedCapabilities = Collections.emptySet();

    public static MemorySnapshot empty() {
        return new MemorySnapshot();
    }

    public static MemorySnapshot withAuth(Set<String> authorizedCapabilities) {
        MemorySnapshot m = new MemorySnapshot();
        m.authorizedCapabilities = authorizedCapabilities;
        return m;
    }
}
