package be.heh.math;

import be.belegkarnil.game.board.tak.Action;
import be.belegkarnil.game.board.tak.Board;
import be.belegkarnil.game.board.tak.Game;
import be.belegkarnil.game.board.tak.Player;
import be.belegkarnil.game.board.tak.event.RoundEvent;
import be.belegkarnil.game.board.tak.event.RoundListener;
import be.belegkarnil.game.board.tak.strategy.Strategy;
import be.heh.math.core.eval.Evaluator;
import be.heh.math.core.move.MoveApplier;
import be.heh.math.core.move.MoveGenerator;
import be.heh.math.core.state.BoardState;

import java.awt.Color;
import java.util.List;

/**
 * ÉTAPE 3 — GreedyStrategy avec lookahead 1-ply défensif.
 *
 * Principe :
 *   1) Énumère tous les coups légaux via MoveGenerator.
 *   2) Si un coup mène à la victoire immédiate → le joue tout de suite.
 *   3) Sinon, pour chaque coup, regarde les réponses possibles de l'adversaire :
 *      - si l'adversaire peut compléter un chemin en 1 coup, le mien est DANGEREUX.
 *   4) Parmi les coups NON dangereux, sélectionne celui qui maximise l'Evaluator.
 *   5) Si tous les coups sont dangereux, joue celui de moindre dommage (best eval).
 *
 * Pourquoi cette structure ?
 *   - L'éval seule récompense déjà la complétion (WIN_BONUS).
 *   - Le filtre défensif évite le piège classique "je joue un coup gagnant à mes yeux
 *     mais qui ouvre la voie à l'adversaire".
 *   - C'est l'équivalent d'un minimax à profondeur 2 simplifié (max-min sur 1 ply).
 *
 * Performance estimée sur MEDIUM (5×5) :
 *   N coups × M réponses adverses ≈ 100 × 100 = 10⁴ simulations / tour.
 *   Chaque simulation = clone BoardState + MoveApplier.apply. Coût négligeable.
 *
 * @author be.heh.math
 */
public class GreedyStrategy implements Strategy, RoundListener {

    private boolean firstAction;

    public GreedyStrategy() {
        this.firstAction = false;
    }

    @Override
    public Action plays(Player myself, Board board, Player opponent) {
        boolean isFirst = firstAction;
        if (firstAction) firstAction = false;

        List<Action> legal = MoveGenerator.generateLegal(board, myself, opponent, isFirst);
        if (legal.isEmpty()) return new Action(); // SKIP forcé

        // Sur le 1er coup du round, peu importe la stratégie : on place le dolmen adverse au centre.
        // (Pas vraiment optimisable — toutes les cases libres se valent à ce stade.)
        if (isFirst) {
            return pickCenterIshFirst(legal, board.getSize());
        }

        final Color myColor  = myself.getColor();
        final Color oppColor = opponent.getColor();

        int bestSafeScore   = Integer.MIN_VALUE;
        Action bestSafe     = null;
        int bestAnyScore    = Integer.MIN_VALUE;
        Action bestAny      = legal.get(0); // fallback

        for (Action a : legal) {
            // === Simulation de MON coup ===
            BoardState simAfterMe = new BoardState(board);
            Color winnerAfterMe = MoveApplier.apply(simAfterMe, a);

            // Victoire immédiate → on ne cherche pas plus loin
            if (winnerAfterMe == myColor) return a;
            // Auto-but (mon coup complète le chemin adverse) → on évite
            if (winnerAfterMe == oppColor) continue;

            // === Lookahead 1-ply : l'adversaire peut-il gagner en réponse ? ===
            boolean dangerous = opponentCanWin(simAfterMe, opponent, myself);

            // === Évaluation de la position résultante ===
            int score = Evaluator.evaluate(simAfterMe, myself, opponent);

            // Mémorise meilleur coup global (au cas où tous seraient dangereux)
            if (score > bestAnyScore) {
                bestAnyScore = score;
                bestAny = a;
            }
            // Mémorise meilleur coup SAFE
            if (!dangerous && score > bestSafeScore) {
                bestSafeScore = score;
                bestSafe = a;
            }
        }

        return (bestSafe != null) ? bestSafe : bestAny;
    }

    /**
     * Y a-t-il une réponse adverse qui complète un chemin pour l'adversaire ?
     * On NE génère PAS la firstAction de l'adversaire (la règle du DOLMEN adverse
     * ne peut pas créer un chemin en 1 coup de toute façon).
     */
    private boolean opponentCanWin(BoardState state, Player opponent, Player myself) {
        List<Action> oppResponses = MoveGenerator.generateLegal(state, opponent, myself, false);
        for (Action r : oppResponses) {
            BoardState branch = new BoardState(state);
            Color w = MoveApplier.apply(branch, r);
            if (w == opponent.getColor()) return true;
        }
        return false;
    }

    /** Premier coup du round : choisit une case proche du centre (heuristique simple). */
    private Action pickCenterIshFirst(List<Action> legal, int size) {
        double cx = (size - 1) / 2.0;
        double cy = (size - 1) / 2.0;
        Action best = legal.get(0);
        double bestDist = Double.MAX_VALUE;
        for (Action a : legal) {
            if (!a.isPlace()) continue;
            double dx = a.position.x - cx;
            double dy = a.position.y - cy;
            double d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        return best;
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
