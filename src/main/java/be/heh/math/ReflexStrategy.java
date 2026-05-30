package be.heh.math;

import be.belegkarnil.game.board.tak.Action;
import be.belegkarnil.game.board.tak.Board;
import be.belegkarnil.game.board.tak.Constants;
import be.belegkarnil.game.board.tak.Game;
import be.belegkarnil.game.board.tak.Piece;
import be.belegkarnil.game.board.tak.Player;
import be.belegkarnil.game.board.tak.event.RoundEvent;
import be.belegkarnil.game.board.tak.event.RoundListener;
import be.belegkarnil.game.board.tak.strategy.Strategy;
import be.heh.math.core.eval.EvaluatorV2;
import be.heh.math.core.move.MoveApplier;
import be.heh.math.core.move.MoveGenerator;
import be.heh.math.core.state.BoardState;

import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ReflexStrategy — minimax 2-ply avec threat detection implicite.
 *
 * Étapes accumulées :
 *   1. Énumération des coups légaux
 *   2. Détection victoire/défaite immédiate (WIN_REWARD / LOSS_PENALTY)
 *   3. Evaluator pour les coups neutres
 *   4. Pré-détection de la menace adverse
 *   5. BLOCK_BONUS pour neutraliser une menace
 *   6. Minimax 2-ply (worstOpponentReward)
 *
 * Étapes 8-11 à venir (cf. REFLEX_OPTIMIZATIONS.md).
 *
 * @author be.heh.math
 */
public class ReflexStrategy implements Strategy, RoundListener {

    static final int WIN_REWARD   =  1_000_000;
    static final int LOSS_PENALTY = -1_000_000;
    static final int BLOCK_BONUS  =      50_000;

    static final int TIME_BUDGET_MS = 59_500; // 1s margin for move generation + overhead

    static final int MAX_DEPTH = 15;

    static final int TT_BITS = 26; // 64-bit hash, 2^26 entries = 512MB if each entry is 16 bytes

    // ========================================================================
    // DEBUG INSTRUMENTATION
    //   Mettre DEBUG = true pour activer compteurs + chronos par tour.
    //   Quand DEBUG = false, l'overhead est nul (JIT élimine les blocs).
    // ========================================================================
    public static boolean DEBUG = true;

    // Compteurs (par tour de plays)
    private long statAlphaBetaCalls;
    private long statTTLookups;
    private long statTTHashHits;
    private long statTTCutoffs;
    private long statTTStores;
    private long statKillerHits;
    private long statTTMoveHits;
    private long statLeafEvals;
    private long statWinShortCircuits;
    private long statLossSkips;
    private long statMoveGenCalls;
    private long statApplyCalls;
    private long statEvalCalls;

    // Chronos en nanosecondes (par tour)
    private long nsEvaluator;
    private long nsMoveGenerator;
    private long nsMoveApplier;
    private long nsTTLookup;
    private long nsTTStore;
    private long nsHashPosition;
    private long nsOrderWithKillers;

    // Stats par profondeur d'iterative deepening
    private long[] statDepthCompleted;
    private int[] statDepthBestScore;
    private int statDeepestCompleted;
    private long debugStartMs;
    // ========================================================================
    // 
    //  Fin de la section de DEBUG INSTRUMENTATION
    // 
    // ========================================================================

    private boolean firstAction = false;
    private long deadline = 0L;
    private boolean timeUp = false;

    private Action [][] killerMoves; // Pour stocker des moves suceptibles d'être bons à chaque profondeur (pour optimisation alpha-beta)

    private TTEntry[] tt;
    private int ttMask;

    public ReflexStrategy() {
    }

    @Override
    public Action plays(Player myself, Board board, Player opponent) {
        deadline = System.currentTimeMillis() + TIME_BUDGET_MS;
        timeUp = false;
        if (firstAction) {
            firstAction = false;
            return smartFirstAction(board, opponent);
        }

        return chooseAction(board, myself, opponent, MAX_DEPTH);

    }

    private Action smartFirstAction(Board board, Player opponent) {
        Piece dolmen = (opponent.getColor() == Constants.BLACK_PLAYER)
                ? Piece.DOLMEN_BLACK
                : Piece.DOLMEN_WHITE;
        int size = board.getSize();
        Point[] candidates = new Point[] {
                new Point(0, 0),
                new Point(size - 1, size - 1),
                new Point(0, size - 1),
                new Point(size - 1, 0)
        };
        int selectedIndex = Math.abs(board.hashCode()) % 4;
        if(board.isFree(candidates[selectedIndex].x, candidates[selectedIndex].y)) {
            return new Action(dolmen, candidates[selectedIndex]);
        }
         // Si la position choisie est occupée (peu probable), choisir la suivante disponible dans la liste
        for(int i = 1; i < candidates.length; i++) {
            int idx = (selectedIndex + i) % candidates.length;
            if(board.isFree(candidates[idx].x, candidates[idx].y)) {
                return new Action(dolmen, candidates[idx]);
            }
        }
        return new Action();
    }

    private static String describe(Action a) {
        if(a == null) return "null";
        if(a.isSkip()) return "SKIP";
        if(a.isPlace()) return "PLACE" + a.piece + "@" + a.position;
        if(a.isMove()) return "MOVE" + a.piece + "@" + a.position + "→" + a.destination
                + " amount=" + Arrays.toString(a.getAmount());
        return "UNKNOWN_ACTION_TYPE";
    }

    private Action chooseAction(Board board, Player myself, Player opponent, int maxDepth) {

        if (DEBUG) resetDebugStats(maxDepth);

        long t0 = DEBUG ? System.nanoTime() : 0;
        List<Action> legal = MoveGenerator.generateLegal(board, myself, opponent, false);
        if (DEBUG) { nsMoveGenerator += System.nanoTime() - t0; statMoveGenCalls++; }

        killerMoves = new Action[MAX_DEPTH + 2][2];

        if (tt == null) {
            tt = new TTEntry[1 << TT_BITS];
            ttMask = (1 << TT_BITS) - 1;
        }

        if(legal.isEmpty()) {
            if (DEBUG) printDebugStats(0);
            return new Action();
        }

        Color myColor = myself.getColor();
        Color oppColor = opponent.getColor();

        Action bestAction = legal.get(0);
        int bestRootScore = Integer.MIN_VALUE;
        BoardState rootSim = new BoardState(board);

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (timeUp) break;

            Action depthBest = null;
            int depthBestScore = Integer.MIN_VALUE;
            int alpha = Integer.MIN_VALUE;
            int beta = Integer.MAX_VALUE;
            boolean completedDepth = true;

            long tOrd = DEBUG ? System.nanoTime() : 0;
            List<Action> ordered = orderWithKillersAndTT(legal, depth, null);
            if (DEBUG) nsOrderWithKillers += System.nanoTime() - tOrd;

            int prevBestIdx = ordered.indexOf(bestAction);

            if (prevBestIdx > 0) {
                // Move previous best to front for better alpha-beta pruning
                Action prevBest = ordered.remove(prevBestIdx);
                ordered.add(0, prevBest);
            }

            for (Action a : ordered) {
                if (System.currentTimeMillis() > deadline) {
                    timeUp = true;
                    completedDepth = false;
                    break;
                }
                BoardState sim = new BoardState(rootSim);
                long tApp = DEBUG ? System.nanoTime() : 0;
                Color winnerAfterA = MoveApplier.apply(sim, a);
                if (DEBUG) { nsMoveApplier += System.nanoTime() - tApp; statApplyCalls++; }
                if(winnerAfterA == myColor) {
                    if (DEBUG) { statWinShortCircuits++; printDebugStats(depth); }
                    return a;
                }
                if (winnerAfterA == oppColor) {
                    if (DEBUG) statLossSkips++;
                    continue;
                }

                int nMyS = myself.countStones(), nMyC = myself.countCapstones();
                if(a.isPlace()) {
                    if(a.piece.isCapstone()) nMyC--;
                    else nMyS--;
                }

                int score = alphaBeta(sim, depth - 1, alpha, beta, false, myColor, oppColor,
                        nMyS, nMyC, opponent.countStones(), opponent.countCapstones(), deadline);
                if(System.currentTimeMillis() > deadline) {
                    timeUp = true;
                    break;
                }
                if(score > depthBestScore) {
                    depthBestScore = score;
                    depthBest = a;
                }
                if(score > alpha) {
                    alpha = score;
                }
                if(timeUp) break;
                if(depthBestScore >= WIN_REWARD / 2) break; // Early stop if we find a very good move
            }
            if(completedDepth && depthBest != null) {
                bestAction = depthBest;
                bestRootScore = depthBestScore;
                if (DEBUG) {
                    statDepthCompleted[depth] = 1;
                    statDepthBestScore[depth] = depthBestScore;
                    statDeepestCompleted = depth;
                }
            }
        }
        if (DEBUG) printDebugStats(bestRootScore);
        return bestAction;
    }

    private int alphaBeta(BoardState sim, int depth, int alpha, int beta, boolean maximizing,
                            Color myColor, Color oppColor,
                            int myStones, int myCaps,
                            int oppStones, int oppCaps,
                            long deadline) {

        if (DEBUG) statAlphaBetaCalls++;

        if(timeUp()) {
            timeUp = true;
            long t = DEBUG ? System.nanoTime() : 0;
            int v = EvaluatorV2.evaluate(sim, myColor);
            if (DEBUG) { nsEvaluator += System.nanoTime() - t; statEvalCalls++; }
            return v;
        }

        long tHash = DEBUG ? System.nanoTime() : 0;
        long hash = hashPosition(sim, maximizing, myStones, myCaps, oppStones, oppCaps);
        if (DEBUG) nsHashPosition += System.nanoTime() - tHash;

        long tLookup = DEBUG ? System.nanoTime() : 0;
        int ttIndex = (int) (hash & ttMask);
        TTEntry entry = tt[ttIndex];
        Action ttMove = null;
        int originalAlpha = alpha;
        if (DEBUG) statTTLookups++;
        if (entry != null && entry.hash == hash) {
            if (DEBUG) statTTHashHits++;
            ttMove = entry.bestMove;
            if (entry.depth >= depth) {
                if (entry.type == Bound.TYPE_EXACT) {
                    if (DEBUG) { statTTCutoffs++; nsTTLookup += System.nanoTime() - tLookup; }
                    return entry.value;
                }
                if (entry.type == Bound.TYPE_LOWER && entry.value >= beta) {
                    if (DEBUG) { statTTCutoffs++; nsTTLookup += System.nanoTime() - tLookup; }
                    return entry.value;
                }
                if (entry.type == Bound.TYPE_UPPER && entry.value <= alpha) {
                    if (DEBUG) { statTTCutoffs++; nsTTLookup += System.nanoTime() - tLookup; }
                    return entry.value;
                }
            }
        }
        if (DEBUG) nsTTLookup += System.nanoTime() - tLookup;

        if (depth <= 0) {
            long tEval = DEBUG ? System.nanoTime() : 0;
            int value = EvaluatorV2.evaluate(sim, myColor);
            if (DEBUG) { nsEvaluator += System.nanoTime() - tEval; statEvalCalls++; statLeafEvals++; }
            long tStore = DEBUG ? System.nanoTime() : 0;
            storeTT(ttIndex, hash, value, depth, Bound.TYPE_EXACT, ttMove);
            if (DEBUG) { nsTTStore += System.nanoTime() - tStore; statTTStores++; }
            return value;
        }

        Color currentPlayer = maximizing ? myColor : oppColor;
        Color opponentPlayer = maximizing ? oppColor : myColor;
        int currentStones = maximizing ? myStones : oppStones;
        int currentCaps = maximizing ? myCaps : oppCaps;

        long tGen = DEBUG ? System.nanoTime() : 0;
        List<Action> legal = MoveGenerator.generateLegal(sim, currentPlayer, currentStones, currentCaps, opponentPlayer, false);
        if (DEBUG) { nsMoveGenerator += System.nanoTime() - tGen; statMoveGenCalls++; }

        if (legal.isEmpty() || System.currentTimeMillis() > deadline) {
            long tE = DEBUG ? System.nanoTime() : 0;
            int v = EvaluatorV2.evaluate(sim, myColor);
            if (DEBUG) { nsEvaluator += System.nanoTime() - tE; statEvalCalls++; }
            return v;
        }

        long tOrd = DEBUG ? System.nanoTime() : 0;
        List<Action> ordered = orderWithKillersAndTT(legal, depth, ttMove);
        if (DEBUG) nsOrderWithKillers += System.nanoTime() - tOrd;
        Action bestLocalAction = null;

        if(maximizing) {
            int value = Integer.MIN_VALUE;
            for (Action a : ordered) {
                if(timeUp()) {
                    timeUp = true;
                    break;
                }

                BoardState next = new BoardState(sim);
                long tApp = DEBUG ? System.nanoTime() : 0;
                Color winnerAfterA = MoveApplier.apply(next, a);
                if (DEBUG) { nsMoveApplier += System.nanoTime() - tApp; statApplyCalls++; }
                if(winnerAfterA == myColor) {
                    int win = WIN_REWARD - (MAX_DEPTH - depth);
                    long tS = DEBUG ? System.nanoTime() : 0;
                    storeTT(ttIndex, hash, win, depth, Bound.TYPE_EXACT, a);
                    if (DEBUG) { nsTTStore += System.nanoTime() - tS; statTTStores++; statWinShortCircuits++; }
                    return win;
                }
                if(winnerAfterA == oppColor) {
                    int loss = LOSS_PENALTY + (MAX_DEPTH - depth);
                    long tS = DEBUG ? System.nanoTime() : 0;
                    storeTT(ttIndex, hash, loss, depth, Bound.TYPE_EXACT, a);
                    if (DEBUG) { nsTTStore += System.nanoTime() - tS; statTTStores++; statLossSkips++; }
                    continue;
                };

                int nMyS = myStones, nMyC = myCaps;
                if(a.isPlace()) {
                    if(a.piece.isCapstone()) nMyC--;
                    else nMyS--;
                }

                int childValue = alphaBeta(next, depth - 1, alpha, beta, false, myColor, oppColor, nMyS, nMyC, oppStones, oppCaps, deadline);

                if(childValue > value) {
                    value = childValue;
                    bestLocalAction = a;
                }
                if(value > alpha) alpha = value;
                if(alpha >= beta) {
                    storeKiller(depth, a); // Stocker ce move comme "killer" pour cette profondeur
                    break; // beta cut-off
                }
            }

            byte type = Bound.TYPE_EXACT;
            if (value <= originalAlpha) type = Bound.TYPE_UPPER;
            else if (value >= beta) type = Bound.TYPE_LOWER;
            long tS = DEBUG ? System.nanoTime() : 0;
            storeTT(ttIndex, hash, value, depth, type, bestLocalAction);
            if (DEBUG) { nsTTStore += System.nanoTime() - tS; statTTStores++; }
            return value;
        }
        else {
            int value = Integer.MAX_VALUE;
            for (Action a : ordered) {
                if(timeUp()) {
                    timeUp = true;
                    break;
                }
                BoardState next = new BoardState(sim);
                long tApp = DEBUG ? System.nanoTime() : 0;
                Color winnerAfterA = MoveApplier.apply(next, a);
                if (DEBUG) { nsMoveApplier += System.nanoTime() - tApp; statApplyCalls++; }
                if(winnerAfterA == oppColor) {
                    int loss = LOSS_PENALTY + (MAX_DEPTH - depth);
                    long tS2 = DEBUG ? System.nanoTime() : 0;
                    storeTT(ttIndex, hash, loss, depth, Bound.TYPE_EXACT, a);
                    if (DEBUG) { nsTTStore += System.nanoTime() - tS2; statTTStores++; statLossSkips++; }
                    return loss;
                }
                if(winnerAfterA == myColor) {
                    int win = WIN_REWARD - (MAX_DEPTH - depth);
                    long tS2 = DEBUG ? System.nanoTime() : 0;
                    storeTT(ttIndex, hash, win, depth, Bound.TYPE_EXACT, a);
                    if (DEBUG) { nsTTStore += System.nanoTime() - tS2; statTTStores++; statWinShortCircuits++; }
                    continue;
                }

                int nOppS = oppStones, nOppC = oppCaps;
                if(a.isPlace()) {
                    if(a.piece.isCapstone()) nOppC--;
                    else nOppS--;
                }

                int childValue = alphaBeta(next, depth - 1, alpha, beta, true, myColor, oppColor, myStones, myCaps, nOppS, nOppC, deadline);

                if(childValue < value) {
                    value = childValue;
                    bestLocalAction = a;
                }
                if(value < beta) beta = value;
                if(alpha >= beta) {
                    storeKiller(depth, a); // Stocker ce move comme "killer" pour cette profondeur
                    break; // alpha cut-off
                }
            }

            byte type = Bound.TYPE_EXACT;
            if (value <= originalAlpha) type = Bound.TYPE_UPPER;
            else if (value >= beta) type = Bound.TYPE_LOWER;
            long tS3 = DEBUG ? System.nanoTime() : 0;
            storeTT(ttIndex, hash, value, depth, type, bestLocalAction);
            if (DEBUG) { nsTTStore += System.nanoTime() - tS3; statTTStores++; }
            return value;
        }
    }

    private void storeKiller(int depth, Action a) {
        if (sameAction(killerMoves[depth][0], a)) return; // Already stored as best move for this depth
        else {
            killerMoves[depth][1] = killerMoves[depth][0]; // Demote previous best to second-best
            killerMoves[depth][0] = a; // Store new best move for this depth
        }
    }

    private List<Action> orderWithKillersAndTT(List<Action> legal, int depth, Action ttMove) {
        Action k0 = (depth >= 0 && depth < killerMoves.length) ? killerMoves[depth][0] : null;
        Action k1 = (depth >= 0 && depth < killerMoves.length) ? killerMoves[depth][1] : null;
        
        if(k0 == null && k1 == null) return legal; // No killer moves for this depth, return original order

        boolean foundTT = false, foundK0 = false, foundK1 = false;
        List<Action> head = new ArrayList<>(2);
        List<Action> tail = new ArrayList<>(legal.size());

        for(Action a : legal) {
            if(!foundTT && ttMove != null && sameAction(a, ttMove)) {
                head.add(a);
                foundTT = true;
            }
            else if(!foundK0 && k0 != null && sameAction(a, k0)) {
                if(!foundTT || !sameAction(ttMove, a)) {
                    head.add(a);
                    foundK0 = true;
                }
            }
            else if(!foundK1 && k1 != null && sameAction(a, k1)) {
                if(!foundTT || !sameAction(ttMove, a)) {
                    head.add(a);
                    foundK1 = true;
                }
            }
            else tail.add(a);
        }
        if (DEBUG) {
            if (foundTT) statTTMoveHits++;
            if (foundK0) statKillerHits++;
            if (foundK1) statKillerHits++;
        }
        head.addAll(tail);
        return head;
    }

    private boolean sameAction(Action a1, Action a2) {
        if(a1 == null && a2 == null) return true;
        if(a1 == null || a2 == null) return false;
        if(a1.isSkip() && a2.isSkip()) return true;
        if(a1.isPlace() && a2.isPlace()) {
            return a1.piece == a2.piece && a1.position.equals(a2.position);
        }
        if(a1.isMove() && a2.isMove()) {
            return a1.piece == a2.piece && a1.position.equals(a2.position)
                    && a1.destination.equals(a2.destination) && Arrays.equals(a1.getAmount(), a2.getAmount());
        }
        return false;
    }

    private void storeTT(int index, long hash, int value, int depth, byte type, Action bestMove) {
        TTEntry entry = tt[index];
        if (entry == null) {
            entry = new TTEntry();
            tt[index] = entry;
        } else if (entry.hash != hash && entry.depth > depth) {
            return;
        }
        entry.hash = hash;
        entry.value = value;
        entry.depth = depth;
        entry.type = type;
        entry.bestMove = bestMove;
    }

    private long hashPosition(BoardState state, boolean maximizing,
                              int myStones, int myCaps, int oppStones, int oppCaps) {
        long hash = state.hash();
        hash ^= maximizing ? 0x9E3779B97F4A7C15L : 0xC2B2AE3D27D4EB4FL;
        hash = mix(hash ^ myStones);
        hash = mix(hash ^ ((long) myCaps << 8));
        hash = mix(hash ^ ((long) oppStones << 16));
        hash = mix(hash ^ ((long) oppCaps << 24));
        return hash;
    }

    private long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    private boolean timeUp() {
        return System.currentTimeMillis() >= deadline;
    }

    @Override
    public void register(Game game) {
        game.addRoundListener(this);
    }

    @Override
    public void unregister(Game game) {
        game.removeRoundListener(this);
    }

    @Override
    public void onRoundBegins(RoundEvent event) {
        firstAction = true;
        tt = null;
    }

    @Override
    public void onRoundEnds(RoundEvent event) {
    }

    private static final class TTEntry {
        long hash;
        int value;
        int depth;
        byte type;
        Action bestMove;
    }

    private static final class Bound {
        static final byte TYPE_EXACT = 0;
        static final byte TYPE_LOWER = 1;
        static final byte TYPE_UPPER = 2;
    }

    // ========================================================================
    // DEBUG : reset/print stats
    // ========================================================================
    private void resetDebugStats(int maxDepth) {
        statAlphaBetaCalls = 0L;
        statTTLookups = 0L;
        statTTHashHits = 0L;
        statTTCutoffs = 0L;
        statTTStores = 0L;
        statKillerHits = 0L;
        statTTMoveHits = 0L;
        statLeafEvals = 0L;
        statWinShortCircuits = 0L;
        statLossSkips = 0L;
        statMoveGenCalls = 0L;
        statApplyCalls = 0L;
        statEvalCalls = 0L;
        nsEvaluator = 0L;
        nsMoveGenerator = 0L;
        nsMoveApplier = 0L;
        nsTTLookup = 0L;
        nsTTStore = 0L;
        nsHashPosition = 0L;
        nsOrderWithKillers = 0L;
        statDepthCompleted = new long[maxDepth + 2];
        statDepthBestScore = new int[maxDepth + 2];
        Arrays.fill(statDepthBestScore, Integer.MIN_VALUE);
        statDeepestCompleted = 0;
        debugStartMs = System.currentTimeMillis();
    }

    private void printDebugStats(int finalScore) {
        long totalMs = System.currentTimeMillis() - debugStartMs;
        long safeTotal = Math.max(1, totalMs);
        long instrumentedMs =
                (nsEvaluator + nsMoveGenerator + nsMoveApplier
                 + nsTTLookup + nsTTStore + nsHashPosition + nsOrderWithKillers) / 1_000_000L;
        long otherMs = Math.max(0L, totalMs - instrumentedMs);

        StringBuilder sb = new StringBuilder(2048);
        sb.append('\n');
        sb.append("======================== ReflexStrategy DEBUG ========================\n");
        sb.append(String.format("  Tour total            : %6d ms  (budget = %d ms)%n", totalMs, TIME_BUDGET_MS));
        sb.append(String.format("  Profondeur atteinte   : %d / %d%n", statDeepestCompleted, MAX_DEPTH));
        sb.append(String.format("  Score final racine    : %d%n", finalScore));
        sb.append('\n');
        sb.append("  === Compteurs ===\n");
        sb.append(String.format("    alphaBeta calls         : %,15d%n", statAlphaBetaCalls));
        sb.append(String.format("    leaf evals (depth=0)    : %,15d%n", statLeafEvals));
        sb.append(String.format("    EvaluatorV2 calls       : %,15d%n", statEvalCalls));
        sb.append(String.format("    MoveGenerator calls     : %,15d%n", statMoveGenCalls));
        sb.append(String.format("    MoveApplier calls       : %,15d%n", statApplyCalls));
        sb.append(String.format("    win short-circuits      : %,15d%n", statWinShortCircuits));
        sb.append(String.format("    loss skips              : %,15d%n", statLossSkips));
        sb.append('\n');
        sb.append("  === Transposition Table ===\n");
        long hitPct    = statTTLookups   == 0 ? 0 : (100L * statTTHashHits / statTTLookups);
        long cutoffPct = statTTHashHits  == 0 ? 0 : (100L * statTTCutoffs  / statTTHashHits);
        sb.append(String.format("    TT lookups              : %,15d%n", statTTLookups));
        sb.append(String.format("    TT hash hits            : %,15d   (%d%% lookups)%n", statTTHashHits, hitPct));
        sb.append(String.format("    TT cutoffs              : %,15d   (%d%% hits)%n", statTTCutoffs, cutoffPct));
        sb.append(String.format("    TT stores               : %,15d%n", statTTStores));
        sb.append('\n');
        sb.append("  === Move ordering ===\n");
        sb.append(String.format("    TT bestMove hits        : %,15d%n", statTTMoveHits));
        sb.append(String.format("    Killer hits             : %,15d%n", statKillerHits));
        sb.append('\n');
        sb.append("  === Temps cumulés (ms / %) ===\n");
        sb.append(String.format("    EvaluatorV2.evaluate    : %6d ms  (%5.1f%%)%n",
                nsEvaluator / 1_000_000L, 100.0 * nsEvaluator / 1_000_000.0 / safeTotal));
        sb.append(String.format("    MoveGenerator           : %6d ms  (%5.1f%%)%n",
                nsMoveGenerator / 1_000_000L, 100.0 * nsMoveGenerator / 1_000_000.0 / safeTotal));
        sb.append(String.format("    MoveApplier.apply       : %6d ms  (%5.1f%%)%n",
                nsMoveApplier / 1_000_000L, 100.0 * nsMoveApplier / 1_000_000.0 / safeTotal));
        sb.append(String.format("    hashPosition            : %6d ms  (%5.1f%%)%n",
                nsHashPosition / 1_000_000L, 100.0 * nsHashPosition / 1_000_000.0 / safeTotal));
        sb.append(String.format("    TT lookup               : %6d ms  (%5.1f%%)%n",
                nsTTLookup / 1_000_000L, 100.0 * nsTTLookup / 1_000_000.0 / safeTotal));
        sb.append(String.format("    TT store                : %6d ms  (%5.1f%%)%n",
                nsTTStore / 1_000_000L, 100.0 * nsTTStore / 1_000_000.0 / safeTotal));
        sb.append(String.format("    orderWithKillersAndTT   : %6d ms  (%5.1f%%)%n",
                nsOrderWithKillers / 1_000_000L, 100.0 * nsOrderWithKillers / 1_000_000.0 / safeTotal));
        sb.append(String.format("    autre (recursion + GC)  : %6d ms  (%5.1f%%)%n",
                otherMs, 100.0 * otherMs / safeTotal));
        sb.append('\n');
        sb.append("  === Iterative deepening (profondeurs complétées) ===\n");
        if (statDepthCompleted != null) {
            for (int d = 1; d < statDepthCompleted.length; d++) {
                if (statDepthCompleted[d] > 0) {
                    sb.append(String.format("    depth %d : bestScore=%d%n", d, statDepthBestScore[d]));
                }
            }
        }
        sb.append("======================================================================\n");
        System.out.print(sb);
        System.out.flush();
    }
}
