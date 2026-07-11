package com.jjktbf.model.character;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jjktbf.model.repo.BaseRepository;

import java.util.List;

/**
 * Persistent repository for character definitions ({@code data/characters/all_characters.json}).
 *
 * ID scheme and behaviour are inherited from {@link BaseRepository}: 6-digit
 * zero-padded sequential ids, resequenced on delete.
 *
 * On first run (no file), seeds Yuji Itadori and Ryomen Sukuna.
 *
 * <b>Note:</b> seeded characters reference seeded move ids by position
 * (see {@link com.jjktbf.model.move.MoveRepository}). Resequencing a move out
 * from under a character's {@code moveIds} orphans the reference — load()
 * resolves move ids leniently (missing moves are skipped with a warning).
 */
public class CharacterRepository extends BaseRepository<CharacterData> {

    public CharacterRepository(String dataDirectory) {
        super(dataDirectory, "all_characters.json");
    }

    @Override protected String idOf(CharacterData d)             { return d.id; }
    @Override protected void assignId(CharacterData d, String id) { d.id = id; }
    @Override protected String entityName()                       { return "character"; }
    @Override protected TypeReference<List<CharacterData>> typeReference() {
        return new TypeReference<>() {};
    }

    @Override protected void seed() {
        // Yuji — no innate technique.
        // Move ids correspond to positions in the seeded MoveRepository (000000–000011):
        // 000000 Basic Punch, 000001 Basic Block, 000002 Heavy Punch,
        // 000003 Rapid Strikes, 000004 Divekick, 000005 Cursed Strike,
        // 000006 Divergent Fist, 000010 Cursed Energy Armor, 000011 Iron Wall
        CharacterData yuji = new CharacterData();
        yuji.name                  = "Yuji Itadori";
        yuji.description           = "A close-range fighter with exceptional physical talent and resolve.";
        yuji.spriteAsset           = "assets/characters/yuji_itadori.png";
        yuji.innateTechniqueName   = null;
        yuji.vitality              = 175;
        yuji.strength              = 210;
        yuji.durability            = 190;
        yuji.speed                 = 200;
        yuji.cursedEnergyReserves  = 160;
        yuji.cursedEnergyEfficiency= 100;
        yuji.cursedEnergyOutput    = 130;
        yuji.jujutsuSkill          = 140;
        yuji.combatAbility         = 220;
        yuji.cursedTechniqueMastery= 10;
        yuji.moveIds = List.of(
            "000000", "000001", "000003", "000002", "000004",
            "000005", "000006", "000010", "000011");
        super.add(yuji);

        // Sukuna — innate technique: Shrine.
        // 000000 Basic Punch, 000001 Basic Block, 000002 Heavy Punch,
        // 000003 Rapid Strikes, 000005 Cursed Strike,
        // 000007 Dismantle, 000008 Cleave, 000009 Fleshy Strike,
        // 000010 Cursed Energy Armor, 000011 Iron Wall
        CharacterData sukuna = new CharacterData();
        sukuna.name                  = "Ryomen Sukuna";
        sukuna.description           = "The King of Curses, wielding the Shrine innate technique.";
        sukuna.spriteAsset           = "assets/characters/ryomen_sukuna.png";
        sukuna.innateTechniqueName   = "Shrine";
        sukuna.vitality              = 300;
        sukuna.strength              = 280;
        sukuna.durability            = 270;
        sukuna.speed                 = 290;
        sukuna.cursedEnergyReserves  = 300;
        sukuna.cursedEnergyEfficiency= 250;
        sukuna.cursedEnergyOutput    = 295;
        sukuna.jujutsuSkill          = 290;
        sukuna.combatAbility         = 285;
        sukuna.cursedTechniqueMastery= 300;
        sukuna.moveIds = List.of(
            "000000", "000001", "000003", "000002", "000005",
            "000007", "000008", "000009", "000010", "000011");
        super.add(sukuna);
    }
}
