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
        killerMoves = new Action[MAX_DEPTH + 1][];

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

            List<Action> ordered = new ArrayList<>(legal);
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
                if(completedDepth && depthBest != null) {
                    bestAction = depthBest;
                }
                if(timeUp) break;
                if(depthBestScore >= WIN_REWARD / 2) break; // Early stop if we find a very good move
            }
        }
        return bestAction;
    }

    private int alphaBeta(BoardState sim, int depth, int alpha, int beta, boolean maximizing, 
                            Color myColor, Color oppColor, 
                            int myStones, int myCaps, 
                            int oppStones, int oppCaps,
                            long deadline) {
        if (depth == 0) {
            return Evaluator.evaluate(sim, myColor);
        }

        Color currentPlayer = maximizing ? myColor : oppColor;
        Color opponentPlayer = maximizing ? oppColor : myColor;
        int currentStones = maximizing ? myStones : oppStones;
        int currentCaps = maximizing ? myCaps : oppCaps;

        List<Action> legal = MoveGenerator.generateLegal(sim, currentPlayer, currentStones, currentCaps, opponentPlayer, false);
        if (legal.isEmpty() || System.currentTimeMillis() > deadline) return Evaluator.evaluate(sim, myColor);

        if(maximizing) {
            int value = Integer.MIN_VALUE;
            for (Action a : legal) {
                BoardState next = new BoardState(sim);
                Color winnerAfterA = MoveApplier.apply(next, a);
                if(winnerAfterA == myColor) return WIN_REWARD - (MAX_DEPTH - depth);
                if(winnerAfterA == oppColor) continue;

                int nMyS = myStones, nMyC = myCaps;
                if(a.isPlace()) {
                    if(a.piece.isCapstone()) nMyC--;
                    else nMyS--;
                }

                int childValue = alphaBeta(next, depth - 1, alpha, beta, false, myColor, oppColor, nMyS, nMyC, oppStones, oppCaps, deadline);
                
                if(childValue > value) value = childValue;
                if(value > alpha) alpha = value;
                if(alpha >= beta) break; // beta cut-off
            }
            return value;
        }
        else {
            int value = Integer.MAX_VALUE;
            for (Action a : legal) {
                BoardState next = new BoardState(sim);
                Color winnerAfterA = MoveApplier.apply(next, a);
                if(winnerAfterA == oppColor) return LOSS_PENALTY + (MAX_DEPTH - depth);
                if(winnerAfterA == myColor) continue;

                int nOppS = oppStones, nOppC = oppCaps;
                if(a.isPlace()) {
                    if(a.piece.isCapstone()) nOppC--;
                    else nOppS--;
                }

                int childValue = alphaBeta(next, depth - 1, alpha, beta, true, myColor, oppColor, myStones, myCaps, nOppS, nOppC, deadline);
                
                if(childValue < value) value = childValue;
                if(value < beta) beta = value;
                if(alpha >= beta) break; // alpha cut-off
            }
            return value;
        }
    }

    private void storeKiller(int depth, Action a) {
        if (killerMoves[depth][0] == a) return; // Already stored as best move for this depth
        else {
            killerMoves[depth][1] = killerMoves[depth][0]; // Demote previous best to second-best
            killerMoves[depth][0] = a; // Store new best move for this depth
        }
    }

    private List<Action> orderWithKillers(List<Action> legal, int depth) {
        Action k0 = (depth >= 0 && depth < killerMoves.length) ? killerMoves[depth][0] : null;
        Action k1 = (depth >= 0 && depth < killerMoves.length) ? killerMoves[depth][1] : null;
        
        if(k0 == null && k1 == null) return legal; // No killer moves for this depth, return original order

        List<Action> head = new ArrayList<>();
        List<Action> tail = new ArrayList<>();

        for(Action a : legal) {
            if(a == k0 && head.get(0) != k0) head.set(0, a);
            else if(a == k1 && head.get(0) != k1) {
                if(head.get(0) != k0) head.set(0, a);
                else head.set(1, a);
            }
            else tail.add(a);
        }

        head.addAll(tail);
        return head;
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
    }

    @Override
    public void onRoundEnds(RoundEvent event) {
    }
}
