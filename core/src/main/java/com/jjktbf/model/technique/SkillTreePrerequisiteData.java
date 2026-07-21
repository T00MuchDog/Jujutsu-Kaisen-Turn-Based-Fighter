package com.jjktbf.model.technique;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One authored unlock requirement for a technique skill-tree node. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillTreePrerequisiteData {

    public static final String MASTERY = "MASTERY";
    public static final String STAT = "STAT";
    public static final String NODE = "NODE";

    /** {@link #MASTERY}, {@link #STAT}, or {@link #NODE}. */
    public String type;

    /** Character stat field name for {@link #STAT}. */
    public String stat;

    /** Minimum mastery/stat value for {@link #MASTERY} and {@link #STAT}. */
    public Integer minimum;

    /** Technique-local node id for {@link #NODE}. */
    public String nodeId;

    /** True when the node requirement should also render as a connector. */
    public Boolean attached;

    /** Whether this node requirement is rendered as a persistent connector. */
    public boolean hasAttachment() {
        return NODE.equalsIgnoreCase(type) && Boolean.TRUE.equals(attached);
    }

    public SkillTreePrerequisiteData copy() {
        SkillTreePrerequisiteData copy = new SkillTreePrerequisiteData();
        copy.type = type;
        copy.stat = stat;
        copy.minimum = minimum;
        copy.nodeId = nodeId;
        copy.attached = attached;
        return copy;
    }
}
