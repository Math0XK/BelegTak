package be.heh.math.tools;

import static org.junit.jupiter.api.Assertions.assertTimeout;

import be.belegkarnil.game.board.tak.Board;
import be.belegkarnil.game.board.tak.Game;
import be.belegkarnil.game.board.tak.Player;
import be.belegkarnil.game.board.tak.event.GameEvent;
import be.belegkarnil.game.board.tak.event.GameListener;
import be.belegkarnil.game.board.tak.event.MisdesignEvent;
import be.belegkarnil.game.board.tak.event.MisdesignListener;
import be.belegkarnil.game.board.tak.strategy.RandomStrategy;
import be.belegkarnil.game.board.tak.strategy.Strategy;
import be.heh.math.ChampionStrategy;
import be.heh.math.GreedyStrategy;
import be.heh.math.MctsStrategy;
import be.heh.math.NaiveStrategy;
import be.heh.math.ReflexStrategy;

/**
 * ÉTAPE 2 — Harness de tournoi.
 *
 * Fait jouer 2 strategies l'une contre l'autre sur N parties et imprime :
 *   - wins de chacune (perspective de chacune des deux strategies)
 *   - nombre de timeouts / exceptions / actions invalides (par camp)
 *
 * Pourquoi c'est CRITIQUE :
 *   Sans mesure, on itère à l'aveugle. Ce harness sera utilisé après chaque
 *   modification d'algo pour répondre à la seule vraie question : "Est-ce que
 *   ma modif a amélioré ou dégradé l'IA ?"
 *
 * USAGE EN LIGNE DE COMMANDE :
 *   java -cp <classpath> be.heh.math.tools.TournamentRunner \
 *        [strategyA_class] [strategyB_class] [size] [numGames] [timeoutSec]
 *
 * Exemple (avec valeurs par défaut) :
 *   java -cp ... be.heh.math.tools.TournamentRunner
 *     → NaiveStrategy vs RandomStrategy, MEDIUM (5x5), 1000 parties, 5s/coup.
 *
 * @author be.heh.math
 */
public class TournamentRunner {

    public static void main(String[] args) throws Exception {
        // === Parsing arguments avec valeurs par défaut ===
        String classA      = args.length > 0 ? args[0] : ReflexStrategy.class.getName();
        String classB      = args.length > 1 ? args[1] : ChampionStrategy.class.getName();
        Board.Size size    = args.length > 2 ? Board.Size.valueOf(args[2]) : Board.Size.MEDIUM;
        int numGames       = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        int timeoutSec     = args.length > 4 ? Integer.parseInt(args[4]) : 60;

        @SuppressWarnings("unchecked")
        Class<? extends Strategy> klassA = (Class<? extends Strategy>) Class.forName(classA);
        @SuppressWarnings("unchecked")
        Class<? extends Strategy> klassB = (Class<? extends Strategy>) Class.forName(classB);

        System.out.println("=== TOURNAMENT ===");
        System.out.println("A   : " + klassA.getSimpleName());
        System.out.println("B   : " + klassB.getSimpleName());
        System.out.println("Size: " + size + " (" + size.length + "x" + size.length + ")");
        System.out.println("N   : " + numGames + " parties");
        System.out.println("Tout: " + timeoutSec + " s/coup");
        System.out.println();

        Stats statsA = new Stats(klassA.getSimpleName());
        Stats statsB = new Stats(klassB.getSimpleName());

        long t0 = System.currentTimeMillis();

        for (int g = 0; g < numGames; g++) {
            // Alterner qui commence pour éviter le biais de second-player (cf. règle TIE_SECOND_PLAYER)
            boolean aFirst = (g % 2 == 0);

            Strategy stratA = klassA.getDeclaredConstructor().newInstance();
            Strategy stratB = klassB.getDeclaredConstructor().newInstance();

            Player playerA = new Player("A_" + klassA.getSimpleName(), stratA);
            Player playerB = new Player("B_" + klassB.getSimpleName(), stratB);

            Board board = new Board(size);
            Game game = aFirst
                    ? new Game(board, playerA, playerB, timeoutSec, Game.DEFAULT_NUMBER_OF_WINNING_ROUNDS,
                               Game.DEFAULT_SKIP_LIMIT, Game.DEFAULT_SKIP_PENALTY)
                    : new Game(board, playerB, playerA, timeoutSec, Game.DEFAULT_NUMBER_OF_WINNING_ROUNDS,
                               Game.DEFAULT_SKIP_LIMIT, Game.DEFAULT_SKIP_PENALTY);

            // Capture du vainqueur
            final Player[] winnerHolder = new Player[1];
            game.addGameListener(new GameListener() {
                @Override public void onGameBegins(GameEvent e) { }
                @Override public void onGameEnds(GameEvent e)   { winnerHolder[0] = e.winner; }
            });

            // Capture des MisdesignEvents pour stats d'erreurs
            game.addMisdesignListener(new MisdesignListener() {
                @Override public void onTimeout(MisdesignEvent e) {
                    statsFor(e.player, playerA, statsA, statsB).timeouts++;
                }
                @Override public void onException(MisdesignEvent e) {
                    statsFor(e.player, playerA, statsA, statsB).exceptions++;
                }
                @Override public void onInvalidPiece(MisdesignEvent e) {
                    statsFor(e.player, playerA, statsA, statsB).invalidPieces++;
                }
                @Override public void onInvalidAction(MisdesignEvent e) {
                    statsFor(e.player, playerA, statsA, statsB).invalidActions++;
                }
            });

            game.run(); // synchrone : bloque jusqu'à la fin de la partie

            Player winner = winnerHolder[0];
            if (winner == playerA) statsA.wins++;
            else if (winner == playerB) statsB.wins++;

            if ((g + 1) % 10 == 0) {
                System.out.printf("  [%d/%d] %s=%d  %s=%d%n",
                        g + 1, numGames,
                        statsA.name, statsA.wins,
                        statsB.name, statsB.wins);
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.println("=== RÉSULTATS ===");
        printStats(statsA, numGames);
        printStats(statsB, numGames);
        System.out.printf("Durée totale : %.1f s (%.2f s/partie)%n",
                elapsed / 1000.0, elapsed / 1000.0 / numGames);
    }

    private static Stats statsFor(Player who, Player playerA, Stats sA, Stats sB) {
        return (who == playerA) ? sA : sB;
    }

    private static void printStats(Stats s, int total) {
        double winRate = 100.0 * s.wins / total;
        System.out.printf("[%s]%n", s.name);
        System.out.printf("  Wins           : %d / %d  (%.1f%%)%n", s.wins, total, winRate);
        System.out.printf("  Timeouts       : %d%n", s.timeouts);
        System.out.printf("  Exceptions     : %d%n", s.exceptions);
        System.out.printf("  Invalid pieces : %d%n", s.invalidPieces);
        System.out.printf("  Invalid actions: %d%n", s.invalidActions);
    }

    /** Compteurs par strategy. */
    private static class Stats {
        final String name;
        int wins;
        int timeouts;
        int exceptions;
        int invalidPieces;
        int invalidActions;
        Stats(String name) { this.name = name; }
    }
}
