package be.heh.math.tools;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * BenchmarkRunner — système automatisé pour benchmarker une IA contre une grille
 * d'opposants de force croissante, sur plusieurs tailles de plateau.
 *
 * USAGE EN LIGNE DE COMMANDE :
 *   java -cp ... be.heh.math.tools.BenchmarkRunner \
 *        [challenger_class] [gamesPerMatch] [timeoutSec] [sizes...]
 *
 * Exemples :
 *   # Benchmark complet de ReflexStrategy
 *   java ... BenchmarkRunner be.heh.math.ReflexStrategy 50 30 MEDIUM LARGE
 *
 *   # Quick benchmark de MctsStrategy
 *   java ... BenchmarkRunner be.heh.math.MctsStrategy 20 10 MEDIUM
 *
 * SORTIE :
 *   Une matrice (taille × opposant) → win rate + temps moyen + erreurs.
 *   Un résumé final avec un score agrégé (ELO maison sur 0..1000).
 *
 * @author be.heh.math
 */
public class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        // === Parse arguments ===
        String challengerName = args.length > 0 ? args[0]
                : ReflexStrategy.class.getName();
        int    gamesPerMatch  = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int    timeoutSec     = args.length > 2 ? Integer.parseInt(args[2]) : 60;

        List<Board.Size> sizes = new ArrayList<>();
        if (args.length > 3) {
            for (int i = 3; i < args.length; i++) sizes.add(Board.Size.valueOf(args[i]));
        } else {
            sizes.add(Board.Size.MEDIUM);
            sizes.add(Board.Size.LARGE);
        }

        @SuppressWarnings("unchecked")
        Class<? extends Strategy> challenger = (Class<? extends Strategy>) Class.forName(challengerName);

        // === Suite d'opposants (par force croissante) ===
        @SuppressWarnings("unchecked")
        List<OpponentSpec> opponents = Arrays.asList(
                new OpponentSpec("Random",     RandomStrategy.class,  10),
                new OpponentSpec("Naive",      NaiveStrategy.class,   15),
                new OpponentSpec("Greedy",     GreedyStrategy.class,  50),
                new OpponentSpec("MCTS",       MctsStrategy.class,    60),
                new OpponentSpec("Champion",   ChampionStrategy.class, 100)
        );

        // === Header ===
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  BENCHMARK : " + padRight(challenger.getSimpleName(), 51) + "║");
        System.out.println("║  Parties par match : " + padRight(String.valueOf(gamesPerMatch), 43) + "║");
        System.out.println("║  Timeout par coup  : " + padRight(timeoutSec + " s", 43) + "║");
        System.out.println("║  Plateaux          : " + padRight(sizes.toString(), 43) + "║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // === Boucle de matches ===
        long totalT0 = System.currentTimeMillis();
        double totalScore = 0.0;
        double totalWeight = 0.0;

        for (Board.Size size : sizes) {
            System.out.println("┌──── Plateau " + size + " (" + size.length + "×" + size.length + ") "
                    + "─".repeat(Math.max(0, 47 - size.toString().length())) + "┐");
            System.out.printf("│ %-22s │ %5s │ %7s │ %5s │ %5s │%n",
                    "Opponent", "Win%", "Time/g", "Err", "Score");

            for (OpponentSpec spec : opponents) {
                MatchResult r = runMatch(challenger, spec.klass, size, gamesPerMatch, timeoutSec);
                double weighted = r.winRate * spec.eloWeight;
                totalScore  += weighted;
                totalWeight += spec.eloWeight;

                System.out.printf("│ %-22s │ %4.1f%% │ %5.1fs  │ %5d │ %5.0f │%n",
                        spec.name, r.winRate * 100, r.avgGameSec, r.chalErrors, weighted);
            }
            System.out.println("└" + "─".repeat(66) + "┘");
            System.out.println();
        }

        long totalElapsed = System.currentTimeMillis() - totalT0;
        double aggregatedElo = totalWeight > 0 ? (totalScore / totalWeight) * 1000 : 0;

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  Score agrégé (ELO 0-1000) : %4.0f  %-30s ║%n",
                aggregatedElo, interpretEloScore(aggregatedElo));
        System.out.printf("║  Durée totale              : %5.1f min %29s ║%n",
                totalElapsed / 60_000.0, "");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }

    // ========================================================================
    // EXÉCUTION D'UN MATCH
    // ========================================================================
    private static MatchResult runMatch(Class<? extends Strategy> chalKlass,
                                        Class<? extends Strategy> oppKlass,
                                        Board.Size size,
                                        int numGames,
                                        int timeoutSec) throws Exception {
        int chalWins = 0, oppWins = 0;
        int chalErrors = 0;
        long t0 = System.currentTimeMillis();

        for (int g = 0; g < numGames; g++) {
            boolean chalFirst = (g % 2 == 0);

            Strategy chalInstance = chalKlass.getDeclaredConstructor().newInstance();
            Strategy oppInstance  = oppKlass.getDeclaredConstructor().newInstance();

            Player chalPlayer = new Player("CHAL_" + chalKlass.getSimpleName(), chalInstance);
            Player oppPlayer  = new Player("OPP_"  + oppKlass.getSimpleName(),  oppInstance);

            Board board = new Board(size);
            Game game = chalFirst
                    ? new Game(board, chalPlayer, oppPlayer, timeoutSec,
                               Game.DEFAULT_NUMBER_OF_WINNING_ROUNDS,
                               Game.DEFAULT_SKIP_LIMIT, Game.DEFAULT_SKIP_PENALTY)
                    : new Game(board, oppPlayer, chalPlayer, timeoutSec,
                               Game.DEFAULT_NUMBER_OF_WINNING_ROUNDS,
                               Game.DEFAULT_SKIP_LIMIT, Game.DEFAULT_SKIP_PENALTY);

            final Player[] winnerHolder = new Player[1];
            game.addGameListener(new GameListener() {
                @Override public void onGameBegins(GameEvent e) { }
                @Override public void onGameEnds(GameEvent e)   { winnerHolder[0] = e.winner; }
            });

            final int[] errCounts = new int[1];
            final Player chal = chalPlayer;
            game.addMisdesignListener(new MisdesignListener() {
                @Override public void onTimeout(MisdesignEvent e)       { if (e.player == chal) errCounts[0]++; }
                @Override public void onException(MisdesignEvent e)     { if (e.player == chal) errCounts[0]++; }
                @Override public void onInvalidPiece(MisdesignEvent e)  { if (e.player == chal) errCounts[0]++; }
                @Override public void onInvalidAction(MisdesignEvent e) { if (e.player == chal) errCounts[0]++; }
            });

            game.run();

            Player winner = winnerHolder[0];
            if (winner == chalPlayer) chalWins++;
            else if (winner == oppPlayer) oppWins++;
            chalErrors += errCounts[0];
        }

        long elapsed = System.currentTimeMillis() - t0;
        double winRate = (double) chalWins / numGames;
        double avgGameSec = elapsed / 1000.0 / numGames;
        return new MatchResult(winRate, avgGameSec, chalErrors);
    }

    // ========================================================================
    // CLASSES INTERNES & HELPERS
    // ========================================================================
    private static class OpponentSpec {
        final String name;
        final Class<? extends Strategy> klass;
        /** Poids dans le score ELO agrégé (battre Champion vaut plus que battre Random). */
        final int eloWeight;
        OpponentSpec(String name, Class<? extends Strategy> klass, int eloWeight) {
            this.name = name; this.klass = klass; this.eloWeight = eloWeight;
        }
    }

    private static class MatchResult {
        final double winRate;
        final double avgGameSec;
        final int    chalErrors;
        MatchResult(double w, double t, int e) { winRate = w; avgGameSec = t; chalErrors = e; }
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private static String interpretEloScore(double elo) {
        if (elo < 100)  return "(débutant)";
        if (elo < 300)  return "(faible)";
        if (elo < 500)  return "(moyen)";
        if (elo < 700)  return "(solide)";
        if (elo < 850)  return "(fort)";
        return "(excellent)";
    }
}
