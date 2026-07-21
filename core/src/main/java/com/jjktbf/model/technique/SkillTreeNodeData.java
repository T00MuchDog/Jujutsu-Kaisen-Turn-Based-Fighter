package com.jjktbf.model.technique;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Persisted layout and unlock metadata for one move or ability in a technique. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillTreeNodeData {

    public static final String MOVE = "MOVE";
    public static final String ABILITY = "ABILITY";

    /** Stable, technique-local id used by node prerequisites. */
    public String id;

    /** {@link #MOVE} or {@link #ABILITY}. */
    public String contentType;

    /** MoveData or AbilityData repository id. */
    public String contentId;

    /** Bottom-left canvas coordinates. */
    public float x;
    public float y;

    public List<SkillTreePrerequisiteData> prerequisites;

    public SkillTreeNodeData copy() {
        SkillTreeNodeData copy = new SkillTreeNodeData();
        copy.id = id;
        copy.contentType = contentType;
        copy.contentId = contentId;
        copy.x = x;
        copy.y = y;
        copy.prerequisites = new ArrayList<>();
        if (prerequisites != null) {
            prerequisites.stream()
                .filter(java.util.Objects::nonNull)
                .map(SkillTreePrerequisiteData::copy)
                .forEach(copy.prerequisites::add);
        }
        return copy;
    }
}
