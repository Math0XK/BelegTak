package be.heh.math;

import be.belegkarnil.game.board.tak.Action;
import be.belegkarnil.game.board.tak.Board;
import be.belegkarnil.game.board.tak.Game;
import be.belegkarnil.game.board.tak.Player;
import be.belegkarnil.game.board.tak.event.RoundEvent;
import be.belegkarnil.game.board.tak.event.RoundListener;
import be.belegkarnil.game.board.tak.strategy.Strategy;
import be.heh.math.core.move.MoveGenerator;

import java.util.List;
import java.util.Random;

/**
 * Strategy de validation : pioche un coup LÉGAL au hasard via MoveGenerator.
 *
 * Différence avec RandomStrategy fournie par BelegTak :
 *   - RandomStrategy génère des coups au "petit bonheur" puis vérifie au moteur.
 *   - NaiveStrategy énumère TOUS les coups légaux et en tire un au hasard.
 *
 * À quoi ça sert ?
 *   - Si NaiveStrategy ne produit JAMAIS d'erreur (pas un seul MisdesignEvent
 *     sur 1000 parties), c'est la preuve empirique que MoveGenerator est correct.
 *   - C'est aussi un baseline plus fort que RandomStrategy pour les comparaisons.
 *
 * @author be.heh.math
 */
public class NaiveStrategy implements Strategy, RoundListener {

    private final Random random;
    private boolean firstAction;

    public NaiveStrategy() {
        this.random = new Random();
        this.firstAction = false;
    }

    @Override
    public Action plays(Player myself, Board board, Player opponent) {
        List<Action> legal = MoveGenerator.generateLegal(board, myself, opponent, firstAction);
        if (firstAction) firstAction = false;
        if (legal.isEmpty()) return new Action(); // SKIP forcé
        return legal.get(random.nextInt(legal.size()));
    }

    @Override
    public void register(Game game)   { game.addRoundListener(this); }
    @Override
    public void unregister(Game game) { game.removeRoundListener(this); }

    @Override
    public void onRoundBegins(RoundEvent event) { this.firstAction = true; }
    @Override
    public void onRoundEnds(RoundEvent event)   { /* rien */ }
}
