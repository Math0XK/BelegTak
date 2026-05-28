package be.heh.math;

import be.belegkarnil.game.board.tak.Action;
import be.belegkarnil.game.board.tak.Board;
import be.belegkarnil.game.board.tak.Player;
import be.belegkarnil.game.board.tak.strategy.StrategyAdapter;

/**
 * ÉTAPE 0 — Sanity check.
 *
 * Strategy minimale : passe systématiquement son tour.
 * But pédagogique : valider que :
 *   1) le package be.heh.math est bien scanné par BelegTak.loadStrategies("be.heh"),
 *   2) la classe a bien un constructeur sans argument,
 *   3) la signature de plays(...) est correcte,
 *   4) l'UI propose HelloStrategy dans la liste des stratégies disponibles.
 *
 * Cette strategy va perdre toutes les parties (3 skips = perte du round),
 * c'est NORMAL. Le but n'est pas de gagner, c'est de tourner.
 *
 * @author be.heh.math
 */
public class HelloStrategy extends StrategyAdapter {

    /** Constructeur par défaut OBLIGATOIRE pour que BelegTak charge la classe. */
    public HelloStrategy() {
        // rien à initialiser
    }

    @Override
    public Action plays(Player myself, Board board, Player opponent) {
        // SKIP_ACTION = action sans pièce, sans position, sans destination
        return new Action();
    }
}
