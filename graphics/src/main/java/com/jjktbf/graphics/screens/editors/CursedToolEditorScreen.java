package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.DynamicSelectBox;
import com.jjktbf.graphics.ui.editor.EditorScreenBase;
import com.jjktbf.graphics.ui.editor.ValidationResult;
import com.jjktbf.model.character.AbilityData;
import com.jjktbf.model.character.AbilityRepository;
import com.jjktbf.model.character.CharacterData;
import com.jjktbf.model.character.CharacterRepository;
import com.jjktbf.model.move.MoveData;
import com.jjktbf.model.move.MoveRepository;
import com.jjktbf.model.weapon.CursedToolData;
import com.jjktbf.model.weapon.CursedToolRepository;
import com.jjktbf.model.weapon.WeaponType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graphical CRUD editor for {@link CursedToolData}. Master-detail layout with:
 *   - Name / Weapon Type / optional Imbued Technique fields
 *   - Read-only flat nodes for moves and abilities assigned from their editors
 *
 * <p>Equipping a tool on a character (character editor's EQUIPMENT section)
 * unlocks matching weapon moves for normal assignment and activates content
 * explicitly assigned to the tool from the move/ability editors.
 */
public class CursedToolEditorScreen extends EditorScreenBase<CursedToolData> {

    private final CursedToolRepository repo;
    private final MoveRepository moveRepo;
    private final AbilityRepository abilityRepo;
    private final CharacterRepository charRepo;

    public CursedToolEditorScreen(JJKGame game, AssetLoader assets) {
        super(game, assets);
        repo       = new CursedToolRepository("data/tools");
        moveRepo   = new MoveRepository("data/moves");
        abilityRepo = new AbilityRepository("data/abilities");
        charRepo   = new CharacterRepository("data/characters");
    }

    @Override protected String title() { return "CURSED TOOL EDITOR"; }

    @Override protected CursedToolData newDraft() {
        CursedToolData tool = new CursedToolData();
        tool.name = "New Cursed Tool";
        tool.weaponType = WeaponType.KATANA.name();
        return tool;
    }

    @Override protected CursedToolData draftFromRecord(CursedToolData stored) {
        return stored.copy();
    }

    @Override protected String idOf(CursedToolData record) { return record.id; }
    @Override protected String nextId() { return repo.nextId(); }
    @Override protected void stampNewId(CursedToolData draft) { draft.id = repo.nextId(); }

    @Override protected String listLabel(CursedToolData record) {
        String name = record.name == null || record.name.isEmpty()
            ? "(unnamed)" : record.name;
        WeaponType type = WeaponType.fromStoredValue(record.weaponType);
        return type == null ? name : name + " [" + type.displayName() + "]";
    }

    @Override protected boolean isNewDraft(CursedToolData draft) {
        return draft.id == null || draft.id.isEmpty() || repo.findById(draft.id).isEmpty();
    }

    @Override
    protected void reloadRecords() throws IOException {
        repo.load();
        moveRepo.load();
        abilityRepo.load();
        charRepo.load();
        records.clear();
        records.addAll(repo.getAll());
    }

    @Override
    protected ValidationResult validateAndSave(CursedToolData tool) {
        if (tool.name == null || tool.name.trim().isEmpty()) {
            return ValidationResult.error("Name is required.");
        }
        tool.name = tool.name.trim();
        if (WeaponType.fromStoredValue(tool.weaponType) == null) {
            return ValidationResult.error("Weapon type is required.");
        }
        try {
            if (isNewDraft(tool)) {
                tool.id = null;
                repo.add(tool);
            } else {
                repo.update(tool);
            }
            repo.save();
        } catch (Exception exception) {
            return ValidationResult.error("Save failed: " + exception.getMessage());
        }
        return ValidationResult.ok("Saved \"" + tool.name + "\".");
    }

    @Override
    protected ValidationResult delete(String id) {
        CursedToolData tool = repo.findById(id).orElse(null);
        if (tool == null) return ValidationResult.error("Cursed tool no longer exists.");
        CharacterData wielder = charRepo.getAll().stream()
            .filter(character -> character.equippedCursedToolIds != null
                && character.equippedCursedToolIds.contains(id))
            .findFirst().orElse(null);
        if (wielder != null) {
            return ValidationResult.error(
                "Cannot delete while \"" + wielder.name + "\" has this tool equipped.");
        }
        MoveData assignedMove = moveRepo.getAll().stream()
            .filter(move -> id.equals(move.requiredCursedToolId))
            .findFirst().orElse(null);
        if (assignedMove != null) {
            return ValidationResult.error(
                "Cannot delete while move \"" + assignedMove.name + "\" is assigned to it.");
        }
        AbilityData assignedAbility = abilityRepo.getAll().stream()
            .filter(ability -> "CURSED_TOOL".equalsIgnoreCase(ability.sourceType))
            .filter(ability -> id.equals(ability.sourceValue))
            .findFirst().orElse(null);
        if (assignedAbility != null) {
            return ValidationResult.error(
                "Cannot delete while ability \"" + assignedAbility.name + "\" is assigned to it.");
        }
        try {
            Map<String, String> remappedIds = new LinkedHashMap<>();
            int nextIndex = 0;
            for (CursedToolData candidate : repo.getAll()) {
                if (id.equals(candidate.id)) continue;
                remappedIds.put(candidate.id,
                    com.jjktbf.model.repo.BaseRepository.formatId(nextIndex++));
            }
            for (MoveData move : moveRepo.getAll()) {
                if (move.requiredCursedToolId != null) {
                    move.requiredCursedToolId = remappedIds.getOrDefault(
                        move.requiredCursedToolId, move.requiredCursedToolId);
                }
            }
            for (AbilityData ability : abilityRepo.getAll()) {
                if ("CURSED_TOOL".equalsIgnoreCase(ability.sourceType)
                    && ability.sourceValue != null) {
                    ability.sourceValue = remappedIds.getOrDefault(
                        ability.sourceValue, ability.sourceValue);
                }
            }
            for (CharacterData character : charRepo.getAll()) {
                if (character.equippedCursedToolIds != null) {
                    character.equippedCursedToolIds = character.equippedCursedToolIds.stream()
                        .map(toolId -> remappedIds.getOrDefault(toolId, toolId))
                        .distinct()
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                }
            }
            repo.delete(id);
            repo.save();
            moveRepo.save();
            abilityRepo.save();
            charRepo.save();
            return ValidationResult.ok("Deleted.");
        } catch (Exception exception) {
            return ValidationResult.error("Delete failed: " + exception.getMessage());
        }
    }

    @Override
    protected Actor buildDetailForm(CursedToolData tool) {
        Table form = formRoot();

        Table identity = formSection(form, "NAME");
        identity.add(idBadge(tool.id)).left().row();
        identity.add(labelledField("Name", tool.name, value -> tool.name = value))
            .growX().row();

        SelectBox<String> weaponTypeSelect = new DynamicSelectBox<>(skin, uiProfile);
        List<String> labels = new ArrayList<>();
        for (WeaponType type : WeaponType.values()) labels.add(type.displayName());
        weaponTypeSelect.setItems(labels.toArray(new String[0]));
        WeaponType current = WeaponType.fromStoredValue(tool.weaponType);
        weaponTypeSelect.setSelected(current == null
            ? WeaponType.KATANA.displayName() : current.displayName());
        weaponTypeSelect.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.audio().play(SoundCue.UI_TOGGLE);
                String label = weaponTypeSelect.getSelected();
                for (WeaponType type : WeaponType.values()) {
                    if (type.displayName().equals(label)) {
                        tool.weaponType = type.name();
                        break;
                    }
                }
                markDirty();
            }
        });
        identity.add(labelledRow("Weapon Type", weaponTypeSelect)).growX().row();
        identity.add(formHint(
            "Unlocks matching weapon-tagged moves, waives their jujutsu stat prerequisites, and makes them cost no cursed energy."))
            .left().row();

        identity.add(labelledField("Imbued Technique (optional)", tool.imbuedTechniqueName,
            value -> tool.imbuedTechniqueName =
                (value == null || value.isBlank()) ? null : value.trim()))
            .growX().row();
        identity.add(formHint(
            "Flavour name of the technique sealed into the tool."))
            .left().row();

        Table grantedMoves = formSection(form, "ASSIGNED MOVES");
        grantedMoves.add(buildMoveNodes(tool)).growX().row();

        Table grantedAbilities = formSection(form, "ASSIGNED ABILITIES");
        grantedAbilities.add(buildAbilityNodes(tool)).growX().row();

        return form;
    }

    private Actor buildMoveNodes(CursedToolData tool) {
        List<MoveData> moves = moveRepo.getAll().stream()
            .filter(move -> moveAssignedTo(tool, move))
            .toList();
        Table nodes = new Table(skin);
        nodes.defaults().growX().pad(4f);
        if (moves.isEmpty()) {
            nodes.add(formHint("No moves are explicitly assigned from the Move Editor.")).left();
        } else {
            for (MoveData move : moves) {
                nodes.add(contentNode(move.name, "MOVE", move.description)).row();
            }
        }
        return nodes;
    }

    private Actor buildAbilityNodes(CursedToolData tool) {
        List<AbilityData> abilities = abilityRepo.getAll().stream()
            .filter(ability -> abilityAssignedTo(tool, ability))
            .toList();
        Table nodes = new Table(skin);
        nodes.defaults().growX().pad(4f);
        if (abilities.isEmpty()) {
            nodes.add(formHint("No abilities are assigned from the Ability Editor.")).left();
        } else {
            for (AbilityData ability : abilities) {
                nodes.add(contentNode(ability.name, ability.category, ability.mechanicText)).row();
            }
        }
        return nodes;
    }

    static boolean moveAssignedTo(CursedToolData tool, MoveData move) {
        return tool != null && move != null && tool.id != null
            && tool.id.equals(move.requiredCursedToolId);
    }

    static boolean abilityAssignedTo(CursedToolData tool, AbilityData ability) {
        return tool != null && ability != null && tool.id != null
            && "CURSED_TOOL".equalsIgnoreCase(ability.sourceType)
            && tool.id.equals(ability.sourceValue);
    }

    private Actor contentNode(String name, String type, String description) {
        Table node = new Table(skin);
        node.setBackground(skin.getDrawable("white-panel"));
        node.pad(9f);
        node.defaults().left().growX();
        Label typeLabel = new Label(type == null ? "ABILITY" : type.toUpperCase(), skin, "small");
        typeLabel.setColor(skin.get("text-dim", com.badlogic.gdx.graphics.Color.class));
        node.add(typeLabel).row();
        Label nameLabel = new Label(name == null ? "(unnamed)" : name, skin);
        nameLabel.setColor(skin.get("text-dark", com.badlogic.gdx.graphics.Color.class));
        node.add(nameLabel).row();
        Label descriptionLabel = new Label(description == null ? "" : description, skin, "small");
        descriptionLabel.setColor(skin.get("text-dark", com.badlogic.gdx.graphics.Color.class));
        descriptionLabel.setWrap(true);
        node.add(descriptionLabel).growX().row();
        return node;
    }
}
