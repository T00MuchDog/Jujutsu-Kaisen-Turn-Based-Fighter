package com.jjktbf.model.move;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jjktbf.model.repo.BaseRepository;

import java.util.List;

/**
 * Persistent repository for move definitions ({@code data/moves/all_moves.json}).
 *
 * ID scheme and behaviour are inherited from {@link BaseRepository}: 6-digit
 * zero-padded sequential ids, resequenced on delete.
 *
 * On first run (no file), seeds the core move set from {@link CoreMoves}.
 *
 * Technique restrictions live on {@link MoveData#requiredTechniqueId}; a
 * dedicated technique registry is future work.
 */
public class MoveRepository extends BaseRepository<MoveData> {

    public MoveRepository(String dataDirectory) {
        super(dataDirectory, "all_moves.json");
    }

    @Override protected String idOf(MoveData d)            { return d.id; }
    @Override protected void assignId(MoveData d, String id){ d.id = id; }
    @Override protected String entityName()                 { return "move"; }
    @Override protected TypeReference<List<MoveData>> typeReference() {
        return new TypeReference<>() {};
    }

    @Override protected void seed() {
        List<Move> coreMoves = List.of(
            CoreMoves.basicPunch(),
            CoreMoves.basicBlock(),
            CoreMoves.heavyPunch(),
            CoreMoves.rapidStrikes(),
            CoreMoves.divekick(),
            CoreMoves.cursedStrike(),
            CoreMoves.divergentFist(),
            CoreMoves.rawCursedEnergyStrike(),
            CoreMoves.dismantle(),
            CoreMoves.cleave(),
            CoreMoves.sukunaFleshyStrike(),
            CoreMoves.cursedEnergyArmor(),
            CoreMoves.ironwall()
        );
        for (Move m : coreMoves) {
            // fromMove leaves the id blank; the base add()/resequence assigns it.
            super.add(MoveData.fromMove(m));
        }
    }
}
