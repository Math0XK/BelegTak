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
import be.heh.math.NemesisStrategy;
import be.heh.math.ReflexStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * BenchmarkRunner — système automatisé pour benchmarker les IA, deux modes :
 *
 *   1) MODE ROUND-ROBIN (par défaut) : toutes les stratégies se rencontrent
 *      deux à deux et on construit une matrice de win rate + un classement.
 *
 *   2) MODE CHALLENGER : une seule stratégie vs toutes les autres, on imprime
 *      une ligne par match avec le résultat.
 *
 * EXÉCUTION PARALLÈLE :
 *   Les matchs (paires en round-robin, ou matches en challenger) sont
 *   exécutés en parallèle. Le nombre de threads par défaut = cores - 1.
 *   Override via -Dbench.threads=N
 *
 *   ATTENTION : avec N parallèles, chaque match partage la CPU. Les
 *   stratégies time-budget travaillent en temps RÉEL, donc elles
 *   "voient" moins de calcul en pratique. Pour des mesures comparables
 *   entre runs, garde un nombre fixe de threads.
 *
 * USAGE EN LIGNE DE COMMANDE :
 *   # Round-robin complet (par défaut)
 *   java ... BenchmarkRunner
 *   java ... BenchmarkRunner ALL [gamesPerPair] [timeoutSec] [sizes...]
 *
 *   # Mode challenger
 *   java ... BenchmarkRunner be.heh.math.ReflexStrategy [gamesPerMatch] [timeoutSec] [sizes...]
 *
 *   # Contrôler le nombre de threads
 *   java -Dbench.threads=4 ... BenchmarkRunner ALL 4 5 MEDIUM
 *
 * @author be.heh.math
 */
public class BenchmarkRunner {

    /** Nombre de matchs en parallèle. Override via -Dbench.threads=N */
    private static final int PARALLELISM = computeParallelism();

    private static int computeParallelism() {
        String prop = System.getProperty("bench.threads");
        if (prop != null) {
            try { return Math.max(1, Integer.parseInt(prop)); } catch (Exception ignored) {}
        }
        // Par défaut : laisse 1 core libre pour le JIT/GC/I-O
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /** Toutes les stratégies à benchmarker. Ordre = ordre dans la matrice. */
    private static final List<OpponentSpec> ALL_STRATEGIES = Arrays.asList(
            new OpponentSpec("Random",   RandomStrategy.class,    10),
            new OpponentSpec("Naive",    NaiveStrategy.class,     20),
            new OpponentSpec("Greedy",   GreedyStrategy.class,    50),
            new OpponentSpec("MCTS",     MctsStrategy.class,      65),
            new OpponentSpec("Reflex",   ReflexStrategy.class,    75),
            new OpponentSpec("Nemesis",  NemesisStrategy.class,   85),
            new OpponentSpec("Champion", ChampionStrategy.class, 100)
    );

    public static void main(String[] args) throws Exception {
        // === Parse arguments ===
        String  mode          = args.length > 0 ? args[0] : "ROBIN";
        int     gamesPerPair  = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int     timeoutSec    = args.length > 2 ? Integer.parseInt(args[2]) : 60;

        List<Board.Size> sizes = new ArrayList<>();
        if (args.length > 3) {
            for (int i = 3; i < args.length; i++) sizes.add(Board.Size.valueOf(args[i]));
        } else {
            sizes.add(Board.Size.HUGE);
        }

        if (mode.equalsIgnoreCase("ALL") || mode.equalsIgnoreCase("ROBIN")) {
            runRoundRobin(gamesPerPair, timeoutSec, sizes);
        } else {
            @SuppressWarnings("unchecked")
            Class<? extends Strategy> challenger = (Class<? extends Strategy>) Class.forName(mode);
            runChallengerMode(challenger, gamesPerPair, timeoutSec, sizes);
        }
    }

    // ========================================================================
    // MODE ROUND-ROBIN : tous contre tous (PARALLÉLISÉ)
    // ========================================================================
    private static void runRoundRobin(int gamesPerPair, int timeoutSec, List<Board.Size> sizes)
            throws Exception {
        int N = ALL_STRATEGIES.size();
        int totalPairs = (N * (N - 1)) / 2;

        printHeader("ROUND-ROBIN TOURNAMENT (parallel)", new String[]{
                "Strategies      : " + N + " (" + joinNames(ALL_STRATEGIES) + ")",
                "Paires uniques  : " + totalPairs + "  ×  " + sizes.size() + " plateau(x)",
                "Games par paire : " + gamesPerPair,
                "Timeout/coup    : " + timeoutSec + " s",
                "Threads parall. : " + PARALLELISM + " matchs simultanés"
        });

        long totalT0 = System.currentTimeMillis();

        for (Board.Size size : sizes) {
            System.out.println();
            System.out.println("Plateau " + size + " (" + size.length + "x" + size.length + ")");
            System.out.println(repeat('-', 70));

            // wins[i][j] = nombre de victoires de la stratégie i contre la j
            int[][] wins = new int[N][N];

            int threads = Math.min(PARALLELISM, totalPairs);
            ExecutorService pool = Executors.newFixedThreadPool(threads,
                    r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
            CompletionService<PairResult> cs = new ExecutorCompletionService<>(pool);

            // Submit toutes les paires
            for (int i = 0; i < N; i++) {
                for (int j = i + 1; j < N; j++) {
                    final int fi = i, fj = j;
                    final Board.Size sz = size;
                    cs.submit(() -> {
                        long t0 = System.currentTimeMillis();
                        MatchPair r = runPair(ALL_STRATEGIES.get(fi).klass,
                                              ALL_STRATEGIES.get(fj).klass,
                                              sz, gamesPerPair, timeoutSec);
                        return new PairResult(fi, fj, r, System.currentTimeMillis() - t0);
                    });
                }
            }

            // Collecte des résultats au fur et à mesure
            for (int k = 1; k <= totalPairs; k++) {
                try {
                    PairResult pr = cs.take().get();
                    wins[pr.i][pr.j] = pr.result.aWins;
                    wins[pr.j][pr.i] = pr.result.bWins;
                    synchronized (System.out) {
                        System.out.printf("  [%2d/%2d] %-10s vs %-10s ... %2d-%-2d (%5.1fs, %d errs)%n",
                                k, totalPairs,
                                ALL_STRATEGIES.get(pr.i).name,
                                ALL_STRATEGIES.get(pr.j).name,
                                pr.result.aWins, pr.result.bWins,
                                pr.elapsedMs / 1000.0, pr.result.totalErrors);
                        System.out.flush();
                    }
                } catch (Exception e) {
                    System.err.println("Match failure: " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.MINUTES);

            printMatrix(wins, gamesPerPair, size);
            printRanking(wins, gamesPerPair, size);
        }

        long totalElapsed = System.currentTimeMillis() - totalT0;
        System.out.println();
        System.out.println(repeat('=', 70));
        System.out.printf("  Durée totale : %d min %d s%n",
                totalElapsed / 60000, (totalElapsed / 1000) % 60);
        System.out.println(repeat('=', 70));
    }

    // ========================================================================
    // MODE CHALLENGER : une stratégie vs toutes les autres (PARALLÉLISÉ)
    // ========================================================================
    private static void runChallengerMode(Class<? extends Strategy> challenger,
                                          int gamesPerMatch,
                                          int timeoutSec,
                                          List<Board.Size> sizes) throws Exception {
        printHeader("CHALLENGER : " + challenger.getSimpleName() + " (parallel)", new String[]{
                "Games par match : " + gamesPerMatch,
                "Timeout/coup    : " + timeoutSec + " s",
                "Plateaux        : " + sizes,
                "Threads parall. : " + PARALLELISM + " matchs simultanés"
        });

        long totalT0 = System.currentTimeMillis();
        double totalScore = 0.0, totalWeight = 0.0;

        for (Board.Size size : sizes) {
            System.out.println();
            System.out.println("Plateau " + size + " (" + size.length + "x" + size.length + ")");
            System.out.println(repeat('-', 70));
            System.out.printf("  %-12s | %6s | %8s | %6s | %6s%n",
                    "Opponent", "Win%", "Time/g", "Errors", "Score");
            System.out.println("  " + repeat('-', 56));

            // Collecte des opponents à benchmarker
            List<OpponentSpec> targets = new ArrayList<>();
            for (OpponentSpec spec : ALL_STRATEGIES) {
                if (!spec.klass.equals(challenger)) targets.add(spec);
            }

            int threads = Math.min(PARALLELISM, targets.size());
            ExecutorService pool = Executors.newFixedThreadPool(threads,
                    r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
            CompletionService<ChallengerResult> cs = new ExecutorCompletionService<>(pool);

            for (OpponentSpec spec : targets) {
                final OpponentSpec s = spec;
                final Board.Size sz = size;
                cs.submit(() -> {
                    MatchResult r = runMatch(challenger, s.klass, sz, gamesPerMatch, timeoutSec);
                    return new ChallengerResult(s, r);
                });
            }

            for (int k = 0; k < targets.size(); k++) {
                try {
                    ChallengerResult cr = cs.take().get();
                    double weighted = cr.result.winRate * cr.spec.eloWeight;
                    totalScore  += weighted;
                    totalWeight += cr.spec.eloWeight;
                    synchronized (System.out) {
                        System.out.printf("  %-12s | %5.1f%% | %6.1fs  | %6d | %6.0f%n",
                                cr.spec.name, cr.result.winRate * 100,
                                cr.result.avgGameSec, cr.result.chalErrors, weighted);
                        System.out.flush();
                    }
                } catch (Exception e) {
                    System.err.println("Match failure: " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.MINUTES);
        }

        long totalElapsed = System.currentTimeMillis() - totalT0;
        double agg = totalWeight > 0 ? (totalScore / totalWeight) * 1000 : 0;

        System.out.println();
        System.out.println(repeat('=', 70));
        System.out.printf("  Score ELO (0-1000) : %4.0f  %s%n", agg, interpretEloScore(agg));
        System.out.printf("  Durée totale       : %d min %d s%n",
                totalElapsed / 60000, (totalElapsed / 1000) % 60);
        System.out.println(repeat('=', 70));
    }

    // ========================================================================
    // EXÉCUTION D'UNE PAIRE (round-robin) — track wins des deux côtés
    // ========================================================================
    private static MatchPair runPair(Class<? extends Strategy> aKlass,
                                     Class<? extends Strategy> bKlass,
                                     Board.Size size,
                                     int numGames,
                                     int timeoutSec) throws Exception {
        int aWins = 0, bWins = 0, totalErrors = 0;
        for (int g = 0; g < numGames; g++) {
            boolean aFirst = (g % 2 == 0);

            Strategy aInstance = aKlass.getDeclaredConstructor().newInstance();
            Strategy bInstance = bKlass.getDeclaredConstructor().newInstance();
            Player aPlayer = new Player("A", aInstance);
            Player bPlayer = new Player("B", bInstance);

            Board board = new Board(size);
            Game game = aFirst
                    ? new Game(board, aPlayer, bPlayer, timeoutSec,
                               Game.DEFAULT_NUMBER_OF_WINNING_ROUNDS,
                               Game.DEFAULT_SKIP_LIMIT, Game.DEFAULT_SKIP_PENALTY)
                    : new Game(board, bPlayer, aPlayer, timeoutSec,
                               Game.DEFAULT_NUMBER_OF_WINNING_ROUNDS,
                               Game.DEFAULT_SKIP_LIMIT, Game.DEFAULT_SKIP_PENALTY);

            final Player[] winnerHolder = new Player[1];
            game.addGameListener(new GameListener() {
                @Override public void onGameBegins(GameEvent e) { }
                @Override public void onGameEnds(GameEvent e)   { winnerHolder[0] = e.winner; }
            });

            final int[] errCounts = new int[1];
            game.addMisdesignListener(new MisdesignListener() {
                @Override public void onTimeout(MisdesignEvent e)       { errCounts[0]++; }
                @Override public void onException(MisdesignEvent e)     { errCounts[0]++; }
                @Override public void onInvalidPiece(MisdesignEvent e)  { errCounts[0]++; }
                @Override public void onInvalidAction(MisdesignEvent e) { errCounts[0]++; }
            });

            game.run();

            Player winner = winnerHolder[0];
            if (winner == aPlayer) aWins++;
            else if (winner == bPlayer) bWins++;
            totalErrors += errCounts[0];
        }
        return new MatchPair(aWins, bWins, totalErrors);
    }

    // ========================================================================
    // EXÉCUTION D'UN MATCH (mode challenger) — track wins/errors du challenger
    // ========================================================================
    private static MatchResult runMatch(Class<? extends Strategy> chalKlass,
                                        Class<? extends Strategy> oppKlass,
                                        Board.Size size,
                                        int numGames,
                                        int timeoutSec) throws Exception {
        int chalWins = 0, chalErrors = 0;
        long t0 = System.currentTimeMillis();

        for (int g = 0; g < numGames; g++) {
            boolean chalFirst = (g % 2 == 0);

            Strategy chalInstance = chalKlass.getDeclaredConstructor().newInstance();
            Strategy oppInstance  = oppKlass.getDeclaredConstructor().newInstance();
            Player chalPlayer = new Player("CHAL", chalInstance);
            Player oppPlayer  = new Player("OPP",  oppInstance);

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

            if (winnerHolder[0] == chalPlayer) chalWins++;
            chalErrors += errCounts[0];
        }

        long elapsed = System.currentTimeMillis() - t0;
        return new MatchResult((double) chalWins / numGames,
                                elapsed / 1000.0 / numGames,
                                chalErrors);
    }

    // ========================================================================
    // AFFICHAGE
    // ========================================================================
    private static void printHeader(String title, String[] info) {
        System.out.println();
        System.out.println(repeat('=', 70));
        System.out.println("  " + title);
        System.out.println(repeat('-', 70));
        for (String line : info) System.out.println("  " + line);
        System.out.println(repeat('=', 70));
    }

    /** Matrice de win rate (ligne BAT colonne). */
    private static void printMatrix(int[][] wins, int gamesPerPair, Board.Size size) {
        int N = ALL_STRATEGIES.size();
        System.out.println();
        System.out.println("MATRICE WIN RATE (" + size + ") — la ligne BAT la colonne :");
        System.out.println(repeat('-', 70));

        // header
        System.out.printf("  %-10s", "");
        for (int j = 0; j < N; j++) {
            System.out.printf(" %8s", abbr(ALL_STRATEGIES.get(j).name, 8));
        }
        System.out.println();

        for (int i = 0; i < N; i++) {
            System.out.printf("  %-10s", ALL_STRATEGIES.get(i).name);
            for (int j = 0; j < N; j++) {
                if (i == j) {
                    System.out.printf(" %8s", "  --  ");
                } else {
                    double rate = 100.0 * wins[i][j] / gamesPerPair;
                    System.out.printf(" %7.1f%%", rate);
                }
            }
            System.out.println();
        }
    }

    /** Classement par win rate global (somme victoires / nombre de parties jouées). */
    private static void printRanking(int[][] wins, int gamesPerPair, Board.Size size) {
        int N = ALL_STRATEGIES.size();
        Integer[] indices = new Integer[N];
        double[]  winPct  = new double[N];

        for (int i = 0; i < N; i++) {
            indices[i] = i;
            int totalWins = 0;
            for (int j = 0; j < N; j++) totalWins += wins[i][j];
            int totalGames = (N - 1) * gamesPerPair;
            winPct[i] = 100.0 * totalWins / totalGames;
        }

        Arrays.sort(indices, Comparator.comparingDouble((Integer i) -> -winPct[i]));

        System.out.println();
        System.out.println("CLASSEMENT (" + size + ") :");
        System.out.println(repeat('-', 70));
        System.out.printf("  %-5s %-12s %8s   %s%n", "Rank", "Strategy", "Win%", "Tier");
        for (int k = 0; k < N; k++) {
            int idx = indices[k];
            System.out.printf("  %2d.   %-12s %7.1f%%   %s%n",
                    k + 1,
                    ALL_STRATEGIES.get(idx).name,
                    winPct[idx],
                    tierFromPct(winPct[idx]));
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================
    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        Arrays.fill(arr, c);
        return new String(arr);
    }

    private static String abbr(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }

    private static String joinNames(List<OpponentSpec> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).name);
        }
        return sb.toString();
    }

    private static String tierFromPct(double pct) {
        if (pct >= 80) return "[EXCELLENT]";
        if (pct >= 65) return "[FORT]";
        if (pct >= 50) return "[SOLIDE]";
        if (pct >= 35) return "[MOYEN]";
        if (pct >= 20) return "[FAIBLE]";
        return "[DEBUTANT]";
    }

    private static String interpretEloScore(double elo) {
        if (elo < 100)  return "[debutant]";
        if (elo < 300)  return "[faible]";
        if (elo < 500)  return "[moyen]";
        if (elo < 700)  return "[solide]";
        if (elo < 850)  return "[fort]";
        return "[excellent]";
    }

    // ========================================================================
    // CLASSES INTERNES
    // ========================================================================
    private static class OpponentSpec {
        final String name;
        final Class<? extends Strategy> klass;
        final int eloWeight;
        OpponentSpec(String n, Class<? extends Strategy> k, int w) {
            this.name = n; this.klass = k; this.eloWeight = w;
        }
    }

    private static class MatchResult {
        final double winRate;
        final double avgGameSec;
        final int    chalErrors;
        MatchResult(double w, double t, int e) { winRate = w; avgGameSec = t; chalErrors = e; }
    }

    private static class MatchPair {
        final int aWins, bWins, totalErrors;
        MatchPair(int a, int b, int e) { this.aWins = a; this.bWins = b; this.totalErrors = e; }
    }

    /** Résultat d'un match round-robin parallélisé : porte les indices i,j et le temps écoulé. */
    private static class PairResult {
        final int i, j;
        final MatchPair result;
        final long elapsedMs;
        PairResult(int i, int j, MatchPair r, long ms) {
            this.i = i; this.j = j; this.result = r; this.elapsedMs = ms;
        }
    }

    /** Résultat d'un match challenger parallélisé : porte le spec de l'opponent. */
    private static class ChallengerResult {
        final OpponentSpec spec;
        final MatchResult  result;
        ChallengerResult(OpponentSpec s, MatchResult r) { this.spec = s; this.result = r; }
    }
}
