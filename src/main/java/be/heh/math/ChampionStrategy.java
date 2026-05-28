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
import be.heh.math.core.eval.Evaluator;
import be.heh.math.core.move.MoveApplier;
import be.heh.math.core.move.MoveGenerator;
import be.heh.math.core.state.BoardState;

import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ChampionStrategy — l'étalon de benchmark.
 *
 * Combine TOUTES les optimisations classiques d'un agent minimax fort :
 *   - Alpha-beta pruning
 *   - Profondeur récursive
 *   - Iterative deepening (depth 1, 2, ..., MAX_DEPTH)
 *   - Move ordering (TT bestMove + killer moves)
 *   - Killer moves (2 par niveau de profondeur)
 *   - Transposition table avec hash FNV (cf. BoardState.hash())
 *   - Tracking précis des pièces en main
 *   - Préférence victoires rapides / défaites lentes
 *   - Budget temps configurable
 *
 * Usage typique : faire jouer ses propres IA contre ChampionStrategy pour
 * mesurer leur niveau. Voir BenchmarkRunner.
 *
 * Trois niveaux ajustables via les constantes statiques :
 *   - TIME_BUDGET_MS : temps max par coup
 *   - MAX_DEPTH      : profondeur cible iterative deepening
 *
 * Niveaux recommandés :
 *   EASY  → TIME_BUDGET_MS=100,   MAX_DEPTH=2
 *   MED   → TIME_BUDGET_MS=2000,  MAX_DEPTH=4
 *   HARD  → TIME_BUDGET_MS=15000, MAX_DEPTH=6
 *
 * @author be.heh.math
 */
public class ChampionStrategy implements Strategy, RoundListener {

    // ========================================================================
    // CONFIG (niveau HARD par défaut)
    // ========================================================================
    public static long TIME_BUDGET_MS = 59_000;
    public static int  MAX_DEPTH      = 6;
    /** Taille de la transposition table : 2^TT_BITS entrées. */
    public static int  TT_BITS        = 20; // ~1M entrées

    public static final int WIN_REWARD   =  1_000_000;
    public static final int LOSS_PENALTY = -1_000_000;

    // ========================================================================
    // ÉTAT
    // ========================================================================
    private boolean firstAction = false;
    private long deadline;
    private boolean timedOut;
    private Action[][] killerMoves;
    private TTEntry[] tt;
    private int ttMask;

    public ChampionStrategy() {
        this.ttMask = (1 << TT_BITS) - 1;
    }

    private static class TTEntry {
        long   hash;
        int    value;
        int    depth;
        byte   type;       // 0 = EXACT, 1 = LOWER (≥beta), 2 = UPPER (≤alpha)
        Action bestMove;
    }

    // ========================================================================
    // ENTRY POINT
    // ========================================================================
    @Override
    public Action plays(Player myself, Board board, Player opponent) {
        if (firstAction) {
            firstAction = false;
            return placeOpponentDolmenNearCenter(board, opponent);
        }

        Color myColor  = myself.getColor();
        Color oppColor = opponent.getColor();

        List<Action> legal = MoveGenerator.generateLegal(board, myself, opponent, false);
        if (legal.isEmpty()) return new Action();

        deadline     = System.currentTimeMillis() + TIME_BUDGET_MS;
        timedOut     = false;
        killerMoves  = new Action[MAX_DEPTH + 2][2];
        if (tt == null) tt = new TTEntry[ttMask + 1];

        BoardState rootSim = new BoardState(board);
        int myStones  = myself.countStones();
        int myCaps    = myself.countCapstones();
        int oppStones = opponent.countStones();
        int oppCaps   = opponent.countCapstones();

        Action bestAction = legal.get(0);
        int    bestScore  = Integer.MIN_VALUE;

        // ITERATIVE DEEPENING
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            if (timeUp()) break;

            Action depthBest = null;
            int    depthBestScore = Integer.MIN_VALUE;
            int    alpha = Integer.MIN_VALUE + 1;
            int    beta  = Integer.MAX_VALUE - 1;

            // Move ordering : prev best d'abord
            List<Action> ordered = new ArrayList<>(legal);
            int prevIdx = indexOfAction(ordered, bestAction);
            if (prevIdx > 0) {
                ordered.remove(prevIdx);
                ordered.add(0, bestAction);
            }

            boolean completed = true;
            for (Action a : ordered) {
                if (timeUp()) { timedOut = true; completed = false; break; }

                BoardState sim = new BoardState(rootSim);
                Color winner = MoveApplier.apply(sim, a);
                if (winner == myColor) return a;
                if (winner == oppColor) continue;

                int nMyS = myStones, nMyC = myCaps;
                if (a.isPlace()) {
                    if (a.piece.isCapstone()) nMyC--; else nMyS--;
                }

                int score = alphaBeta(sim, depth - 1, alpha, beta, false,
                                      myColor, oppColor,
                                      nMyS, nMyC, oppStones, oppCaps);
                if (timedOut) { completed = false; break; }

                if (score > depthBestScore) {
                    depthBestScore = score;
                    depthBest = a;
                }
                if (score > alpha) alpha = score;
            }

            if (completed && depthBest != null) {
                bestAction = depthBest;
                bestScore  = depthBestScore;
            }
            if (timedOut) break;
            if (bestScore >= WIN_REWARD / 2) break;
        }

        return bestAction;
    }

    // ========================================================================
    // ALPHA-BETA RÉCURSIF + TT + KILLER MOVES
    // ========================================================================
    private int alphaBeta(BoardState state, int depth, int alpha, int beta, boolean maximizing,
                          Color myColor, Color oppColor,
                          int myStones, int myCaps, int oppStones, int oppCaps) {

        if (timeUp()) { timedOut = true; return Evaluator.evaluate(state, myColor); }

        // === TT LOOKUP ===
        long hash = state.hash();
        int ttIdx = (int)(hash & ttMask);
        TTEntry entry = tt[ttIdx];
        Action ttMove = null;
        if (entry != null && entry.hash == hash) {
            ttMove = entry.bestMove;
            if (entry.depth >= depth) {
                if (entry.type == 0) return entry.value;                              // EXACT
                if (entry.type == 1 && entry.value >= beta)  return entry.value;      // LOWER bound
                if (entry.type == 2 && entry.value <= alpha) return entry.value;      // UPPER bound
            }
        }

        if (depth <= 0) {
            int v = Evaluator.evaluate(state, myColor);
            storeTT(ttIdx, hash, v, 0, (byte)0, null);
            return v;
        }

        Color current   = maximizing ? myColor : oppColor;
        Color other     = maximizing ? oppColor : myColor;
        int   curStones = maximizing ? myStones : oppStones;
        int   curCaps   = maximizing ? myCaps   : oppCaps;

        List<Action> legal = MoveGenerator.generateLegal(state, current, curStones, curCaps, other, false);
        if (legal.isEmpty()) return Evaluator.evaluate(state, myColor);

        List<Action> ordered = orderMoves(legal, depth, ttMove);

        int originalAlpha = alpha;
        int value;
        Action bestLocal = null;

        if (maximizing) {
            value = Integer.MIN_VALUE + 1;
            for (Action a : ordered) {
                if (timeUp()) { timedOut = true; break; }
                BoardState next = new BoardState(state);
                Color winner = MoveApplier.apply(next, a);
                if (winner == myColor) {
                    int v = WIN_REWARD - (MAX_DEPTH - depth);
                    storeTT(ttIdx, hash, v, depth, (byte)0, a);
                    return v;
                }
                if (winner == oppColor) continue;

                int nMyS = myStones, nMyC = myCaps;
                if (a.isPlace()) {
                    if (a.piece.isCapstone()) nMyC--; else nMyS--;
                }
                int childValue = alphaBeta(next, depth - 1, alpha, beta, false,
                                           myColor, oppColor,
                                           nMyS, nMyC, oppStones, oppCaps);
                if (childValue > value) { value = childValue; bestLocal = a; }
                if (value > alpha)      alpha = value;
                if (alpha >= beta) {
                    storeKiller(depth, a);
                    break;
                }
            }
        } else {
            value = Integer.MAX_VALUE - 1;
            for (Action a : ordered) {
                if (timeUp()) { timedOut = true; break; }
                BoardState next = new BoardState(state);
                Color winner = MoveApplier.apply(next, a);
                if (winner == oppColor) {
                    int v = LOSS_PENALTY + (MAX_DEPTH - depth);
                    storeTT(ttIdx, hash, v, depth, (byte)0, a);
                    return v;
                }
                if (winner == myColor) continue;

                int nOpS = oppStones, nOpC = oppCaps;
                if (a.isPlace()) {
                    if (a.piece.isCapstone()) nOpC--; else nOpS--;
                }
                int childValue = alphaBeta(next, depth - 1, alpha, beta, true,
                                           myColor, oppColor,
                                           myStones, myCaps, nOpS, nOpC);
                if (childValue < value) { value = childValue; bestLocal = a; }
                if (value < beta)       beta = value;
                if (alpha >= beta) {
                    storeKiller(depth, a);
                    break;
                }
            }
        }

        // === TT STORE ===
        byte type;
        if (value <= originalAlpha)      type = 2;  // UPPER bound
        else if (value >= beta)          type = 1;  // LOWER bound
        else                             type = 0;  // EXACT
        storeTT(ttIdx, hash, value, depth, type, bestLocal);

        return value;
    }

    // ========================================================================
    // MOVE ORDERING
    // ========================================================================
    private List<Action> orderMoves(List<Action> legal, int depth, Action ttMove) {
        Action k0 = (depth < killerMoves.length) ? killerMoves[depth][0] : null;
        Action k1 = (depth < killerMoves.length) ? killerMoves[depth][1] : null;
        if (ttMove == null && k0 == null && k1 == null) return legal;

        ArrayList<Action> head = new ArrayList<>(3);
        ArrayList<Action> tail = new ArrayList<>(legal.size());
        Action fT = null, f0 = null, f1 = null;
        for (Action a : legal) {
            if      (ttMove != null && fT == null && actionsEqual(a, ttMove)) fT = a;
            else if (k0     != null && f0 == null && actionsEqual(a, k0))     f0 = a;
            else if (k1     != null && f1 == null && actionsEqual(a, k1))     f1 = a;
            else tail.add(a);
        }
        if (fT != null) head.add(fT);
        if (f0 != null) head.add(f0);
        if (f1 != null) head.add(f1);
        head.addAll(tail);
        return head;
    }

    private void storeKiller(int depth, Action a) {
        if (depth < 0 || depth >= killerMoves.length) return;
        if (actionsEqual(killerMoves[depth][0], a)) return;
        killerMoves[depth][1] = killerMoves[depth][0];
        killerMoves[depth][0] = a;
    }

    // ========================================================================
    // TT STORE
    // ========================================================================
    private void storeTT(int idx, long hash, int value, int depth, byte type, Action bestMove) {
        TTEntry e = tt[idx];
        if (e == null) {
            e = new TTEntry();
            tt[idx] = e;
        } else if (e.depth > depth && e.hash != hash) {
            // déjà occupé avec une entrée plus profonde et différente → on ne remplace pas
            return;
        }
        e.hash = hash;
        e.value = value;
        e.depth = depth;
        e.type = type;
        e.bestMove = bestMove;
    }

    // ========================================================================
    // UTILITAIRES
    // ========================================================================
    private boolean timeUp() {
        return System.currentTimeMillis() >= deadline;
    }

    private static int indexOfAction(List<Action> list, Action target) {
        if (target == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (actionsEqual(list.get(i), target)) return i;
        }
        return -1;
    }

    private static boolean actionsEqual(Action a, Action b) {
        if (a == null || b == null) return a == b;
        if (a.isSkip()  != b.isSkip())  return false;
        if (a.isPlace() != b.isPlace()) return false;
        if (a.isMove()  != b.isMove())  return false;
        if (a.isSkip()) return true;
        if (a.isPlace()) {
            return a.piece == b.piece && pointEq(a.position, b.position);
        }
        if (!pointEq(a.position, b.position))    return false;
        if (!pointEq(a.destination, b.destination)) return false;
        int[] aa = a.getAmount(), bb = b.getAmount();
        if (aa.length != bb.length) return false;
        for (int i = 0; i < aa.length; i++) if (aa[i] != bb[i]) return false;
        return true;
    }

    private static boolean pointEq(Point p, Point q) {
        return p.x == q.x && p.y == q.y;
    }

    private Action placeOpponentDolmenNearCenter(Board board, Player opponent) {
        Piece dolmen = (opponent.getColor() == Constants.BLACK_PLAYER)
                ? Piece.DOLMEN_BLACK : Piece.DOLMEN_WHITE;
        int size = board.getSize();
        int center = size / 2;
        if (board.isFree(center, center)) {
            return new Action(dolmen, new Point(center, center));
        }
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (board.isFree(row, col)) {
                    return new Action(dolmen, new Point(col, row));
                }
            }
        }
        return new Action();
    }

    // ========================================================================
    // STRATEGY LIFECYCLE
    // ========================================================================
    @Override public void register(Game game)   { game.addRoundListener(this); }
    @Override public void unregister(Game game) { game.removeRoundListener(this); }

    @Override
    public void onRoundBegins(RoundEvent event) {
        firstAction = true;
        // La couleur de myself change entre rounds → on invalide la TT
        tt = null;
    }
    @Override public void onRoundEnds(RoundEvent event) { /* rien */ }
}
