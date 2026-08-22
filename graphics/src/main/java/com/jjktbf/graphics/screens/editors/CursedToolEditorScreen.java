package com.jjktbf.graphics.screens.editors;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.jjktbf.graphics.AssetLoader;
import com.jjktbf.graphics.JJKGame;
import com.jjktbf.graphics.audio.SoundCue;
import com.jjktbf.graphics.ui.DynamicSelectBox;
import com.jjktbf.graphics.ui.editor.AssignmentPanel;
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
import java.util.List;
import java.util.Locale;

/**
 * Graphical CRUD editor for {@link CursedToolData}. Master-detail layout with:
 *   - Name / Weapon Type / optional Imbued Technique fields
 *   - Optional granted moves and granted abilities (bestown while equipped)
 *
 * <p>Equipping a tool on a character (character editor's EQUIPMENT section)
 * grants its weapon type — whose moves then cost no cursed energy — plus any
 * granted content authored here.
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
        tool.grantedMoveIds = new ArrayList<>();
        tool.grantedAbilityIds = new ArrayList<>();
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
        if (tool.grantedMoveIds != null) {
            String missingMove = tool.grantedMoveIds.stream()
                .filter(moveId -> moveId == null || moveRepo.findById(moveId).isEmpty())
                .map(String::valueOf)
                .findFirst().orElse(null);
            if (missingMove != null) {
                return ValidationResult.error(
                    "Remove missing granted move reference " + missingMove + " before saving.");
            }
        }
        if (tool.grantedAbilityIds != null) {
            String missingAbility = tool.grantedAbilityIds.stream()
                .filter(abilityId -> abilityId == null || abilityRepo.findById(abilityId).isEmpty())
                .map(String::valueOf)
                .findFirst().orElse(null);
            if (missingAbility != null) {
                return ValidationResult.error(
                    "Remove missing granted ability reference " + missingAbility
                        + " before saving.");
            }
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
        try {
            repo.delete(id);
            repo.save();
            return ValidationResult.ok("Deleted.");
        } catch (Exception exception) {
            return ValidationResult.error("Delete failed: " + exception.getMessage());
        }
    }

    @Override
    protected Actor buildDetailForm(CursedToolData tool) {
        if (tool.grantedMoveIds == null) tool.grantedMoveIds = new ArrayList<>();
        if (tool.grantedAbilityIds == null) tool.grantedAbilityIds = new ArrayList<>();

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
            "Equipping this tool lets the wielder use moves of this weapon type at no cursed energy cost."))
            .left().row();

        identity.add(labelledField("Imbued Technique (optional)", tool.imbuedTechniqueName,
            value -> tool.imbuedTechniqueName =
                (value == null || value.isBlank()) ? null : value.trim()))
            .growX().row();
        identity.add(formHint(
            "Flavour name of the technique sealed into the tool. The technique's moves are granted below."))
            .left().row();

        Table grantedMoves = formSection(form, "GRANTED MOVES");
        grantedMoves.add(buildGrantedMovesPanel(tool)).growX().row();

        Table grantedAbilities = formSection(form, "GRANTED ABILITIES");
        grantedAbilities.add(buildGrantedAbilitiesPanel(tool)).growX().row();

        return form;
    }

    private AssignmentPanel buildGrantedMovesPanel(CursedToolData tool) {
        return new AssignmentPanel(new AssignmentPanel.Controller() {
            @Override public List<AssignmentPanel.Item> availableItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                for (MoveData move : moveRepo.getAll()) {
                    if (tool.grantedMoveIds.contains(move.id)) continue;
                    items.add(new AssignmentPanel.Item(
                        move.id, move.name, moveSublabel(move)));
                }
                return items;
            }

            @Override public List<AssignmentPanel.Item> assignedItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                for (String moveId : tool.grantedMoveIds) {
                    MoveData move = moveRepo.findById(moveId).orElse(null);
                    if (move == null) continue;
                    items.add(new AssignmentPanel.Item(move.id, move.name, moveSublabel(move)));
                }
                return items;
            }

            @Override public boolean canAssign(String id) { return true; }

            @Override public void onAssign(String id) {
                game.audio().play(SoundCue.UI_TOGGLE);
                tool.grantedMoveIds.add(id);
                markDirty();
            }

            @Override public void onUnassign(String id) {
                game.audio().play(SoundCue.UI_TOGGLE);
                tool.grantedMoveIds.remove(id);
                markDirty();
            }

            @Override public String budgetSummary() {
                return "Granted while equipped";
            }
        }, game.audio()::play, uiProfile, skin);
    }

    private AssignmentPanel buildGrantedAbilitiesPanel(CursedToolData tool) {
        return new AssignmentPanel(new AssignmentPanel.Controller() {
            @Override public List<AssignmentPanel.Item> availableItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                for (AbilityData ability : abilityRepo.getAll()) {
                    if (tool.grantedAbilityIds.contains(ability.id)) continue;
                    items.add(new AssignmentPanel.Item(
                        ability.id, ability.name, abilitySublabel(ability)));
                }
                return items;
            }

            @Override public List<AssignmentPanel.Item> assignedItems() {
                List<AssignmentPanel.Item> items = new ArrayList<>();
                for (String abilityId : tool.grantedAbilityIds) {
                    AbilityData ability = abilityRepo.findById(abilityId).orElse(null);
                    if (ability == null) continue;
                    items.add(new AssignmentPanel.Item(
                        ability.id, ability.name, abilitySublabel(ability)));
                }
                return items;
            }

            @Override public boolean canAssign(String id) { return true; }

            @Override public void onAssign(String id) {
                game.audio().play(SoundCue.UI_TOGGLE);
                tool.grantedAbilityIds.add(id);
                markDirty();
            }

            @Override public void onUnassign(String id) {
                game.audio().play(SoundCue.UI_TOGGLE);
                tool.grantedAbilityIds.remove(id);
                markDirty();
            }

            @Override public String budgetSummary() {
                return "Granted while equipped";
            }
        }, game.audio()::play, uiProfile, skin);
    }

    private static String moveSublabel(MoveData move) {
        String tags = move.tags == null ? "" : String.join(", ", move.tags);
        return tags.toLowerCase(Locale.ROOT);
    }

    private static String abilitySublabel(AbilityData ability) {
        String category = ability.category == null ? "PASSIVE" : ability.category;
        String source = ability.sourceType == null ? "CHARACTER" : ability.sourceType;
        return category + " (" + source + ")";
    }
}
