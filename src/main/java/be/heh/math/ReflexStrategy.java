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

    static final int MAX_DEPTH = 4;

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
            return placeOpponentDolmenNearCenter(board, opponent);
        }

        return chooseAction(board, myself, opponent, MAX_DEPTH);

    }

    private Action placeOpponentDolmenNearCenter(Board board, Player opponent) {
        Piece dolmen = (opponent.getColor() == Constants.BLACK_PLAYER)
                ? Piece.DOLMEN_BLACK
                : Piece.DOLMEN_WHITE;
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

    private static String describe(Action a) {
        if(a == null) return "null";
        if(a.isSkip()) return "SKIP";
        if(a.isPlace()) return "PLACE" + a.piece + "@" + a.position;
        if(a.isMove()) return "MOVE" + a.piece + "@" + a.position + "→" + a.destination
                + " amount=" + Arrays.toString(a.getAmount());
        return "UNKNOWN_ACTION_TYPE";
    }

    private Action chooseAction(Board board, Player myself, Player opponent, int maxDepth) {

        List<Action> legal = MoveGenerator.generateLegal(board, myself, opponent, false);
        killerMoves = new Action[MAX_DEPTH + 2][2];

        if (tt == null) {
            tt = new TTEntry[1 << 20];
            ttMask = (1 << 20) - 1;
        }

        if(legal.isEmpty()) return new Action();

        Color myColor = myself.getColor();
        Color oppColor = opponent.getColor();

        Action bestAction = legal.get(0);
        BoardState rootSim = new BoardState(board);

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (timeUp) break;

            Action depthBest = null;
            int depthBestScore = Integer.MIN_VALUE;
            int alpha = Integer.MIN_VALUE;
            int beta = Integer.MAX_VALUE;
            boolean completedDepth = true;

            List<Action> ordered = orderWithKillersAndTT(legal, depth, null);
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
                Color winnerAfterA = MoveApplier.apply(sim, a);
                if(winnerAfterA == myColor) return a;
                if (winnerAfterA == oppColor) continue;

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
            }
        }
        return bestAction;
    }

    private int alphaBeta(BoardState sim, int depth, int alpha, int beta, boolean maximizing, 
                            Color myColor, Color oppColor, 
                            int myStones, int myCaps, 
                            int oppStones, int oppCaps,
                            long deadline) {

        if(timeUp()) {
            timeUp = true;
            return Evaluator.evaluate(sim, myColor);
        }

        long hash = hashPosition(sim, maximizing, myStones, myCaps, oppStones, oppCaps);
        int ttIndex = (int) (hash & ttMask);
        TTEntry entry = tt[ttIndex];
        Action ttMove = null;
        int originalAlpha = alpha;
        if (entry != null && entry.hash == hash) {
            ttMove = entry.bestMove;
            if (entry.depth >= depth) {
                if (entry.type == Bound.TYPE_EXACT) return entry.value;
                if (entry.type == Bound.TYPE_LOWER && entry.value >= beta) return entry.value;
                if (entry.type == Bound.TYPE_UPPER && entry.value <= alpha) return entry.value;
            }
        }

        if (depth <= 0) {
            int value = Evaluator.evaluate(sim, myColor);
            storeTT(ttIndex, hash, value, depth, Bound.TYPE_EXACT, ttMove);
            return value;
        }

        Color currentPlayer = maximizing ? myColor : oppColor;
        Color opponentPlayer = maximizing ? oppColor : myColor;
        int currentStones = maximizing ? myStones : oppStones;
        int currentCaps = maximizing ? myCaps : oppCaps;

        List<Action> legal = MoveGenerator.generateLegal(sim, currentPlayer, currentStones, currentCaps, opponentPlayer, false);
        if (legal.isEmpty() || System.currentTimeMillis() > deadline) return Evaluator.evaluate(sim, myColor);
        
        List<Action> ordered = orderWithKillersAndTT(legal, depth, ttMove);
        Action bestLocalAction = null;

        if(maximizing) {
            int value = Integer.MIN_VALUE;
            for (Action a : ordered) {
                if(timeUp()) {
                    timeUp = true;
                    break;
                }

                BoardState next = new BoardState(sim);
                Color winnerAfterA = MoveApplier.apply(next, a);
                if(winnerAfterA == myColor) {
                    int win = WIN_REWARD - (MAX_DEPTH - depth);
                    storeTT(ttIndex, hash, win, depth, Bound.TYPE_EXACT, a);
                    return win;
                }
                if(winnerAfterA == oppColor) {
                    int loss = LOSS_PENALTY + (MAX_DEPTH - depth);
                    storeTT(ttIndex, hash, loss, depth, Bound.TYPE_EXACT, a);
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
            storeTT(ttIndex, hash, value, depth, type, bestLocalAction);
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
                Color winnerAfterA = MoveApplier.apply(next, a);
                if(winnerAfterA == oppColor) {
                    int loss = LOSS_PENALTY + (MAX_DEPTH - depth);
                    storeTT(ttIndex, hash, loss, depth, Bound.TYPE_EXACT, a);
                    return loss; 
                }
                if(winnerAfterA == myColor) {
                    int win = WIN_REWARD - (MAX_DEPTH - depth);
                    storeTT(ttIndex, hash, win, depth, Bound.TYPE_EXACT, a);
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
            storeTT(ttIndex, hash, value, depth, type, bestLocalAction);
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
}
