package com.jjktbf.model.technique;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for {@link InnateTechnique}, persisted as JSON by
 * {@link TechniqueRepository}.
 *
 * Fields mirror the domain class: {@code id} (6-digit, assigned by the
 * repository), {@code name} (the technique's identity, matched case-insensitively
 * against {@code MoveData.requiredTechniqueId} and
 * {@code CharacterData.innateTechniqueName}), and {@code description}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InnateTechniqueData {

    /** 6-digit zero-padded sequential id, assigned by the repository. */
    public String id;

    /** Technique identity name (e.g. "Shrine", "Limitless"). Case-insensitive. */
    public String name;

    /** Free-form description / flavour text. */
    public String description;

    public InnateTechnique toTechnique() {
        return new InnateTechnique(id, name, description);
    }
}
