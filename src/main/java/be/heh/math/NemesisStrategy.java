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
import java.util.Comparator;
import java.util.List;

/**
 * NemesisStrategy - agent anti-Reflex.
 *
 * Reflex cherche surtout une bonne reponse a profondeur fixe. Nemesis lui
 * oppose un alpha-beta plus agressif : transposition table, killer moves,
 * ordering tactique et evaluation orientee "course de route".
 */
public class NemesisStrategy implements Strategy, RoundListener {

    public static long TIME_BUDGET_MS = 59_500;
    public static int MAX_DEPTH = 5;
    public static int TT_BITS = 21;

    private static final int WIN_REWARD = 1_000_000;
    private static final int LOSS_PENALTY = -1_000_000;
    private static final int TACTICAL_WIN = 420_000;
    private static final int ROAD_WEIGHT = 12_000;
    private static final int CENTER_FIRST_RADIUS = 1;

    private boolean firstAction;
    private long deadline;
    private boolean timedOut;
    private Action[][] killerMoves;
    private TTEntry[] tt;
    private final int ttMask;

    public NemesisStrategy() {
        ttMask = (1 << TT_BITS) - 1;
    }

    @Override
    public Action plays(Player myself, Board board, Player opponent) {
        if (firstAction) {
            firstAction = false;
            return openingPlacement(board, opponent);
        }

        deadline = System.currentTimeMillis() + TIME_BUDGET_MS;
        timedOut = false;
        killerMoves = new Action[MAX_DEPTH + 2][2];
        if (tt == null) tt = new TTEntry[ttMask + 1];

        Color myColor = myself.getColor();
        Color oppColor = opponent.getColor();
        BoardState root = new BoardState(board);
        int myStones = myself.countStones();
        int myCaps = myself.countCapstones();
        int oppStones = opponent.countStones();
        int oppCaps = opponent.countCapstones();

        List<Action> legal = MoveGenerator.generateLegal(root, myColor, myStones, myCaps, oppColor, false);
        if (legal.isEmpty()) return new Action();

        Action forcedWin = findWinningAction(root, legal, myColor);
        if (forcedWin != null) return forcedWin;

        Action best = legal.get(0);
        int bestScore = Integer.MIN_VALUE + 1;

        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            if (timeUp()) break;

            List<Action> ordered = orderRootMoves(root, legal, best, myColor, oppColor);
            Action depthBest = null;
            int depthBestScore = Integer.MIN_VALUE + 1;
            int alpha = Integer.MIN_VALUE + 1;
            int beta = Integer.MAX_VALUE - 1;
            boolean completed = true;

            for (Action action : ordered) {
                if (timeUp()) {
                    timedOut = true;
                    completed = false;
                    break;
                }

                BoardState next = new BoardState(root);
                Color winner = MoveApplier.apply(next, action);
                if (winner == myColor) return action;
                if (winner == oppColor) continue;

                int nextMyStones = myStones;
                int nextMyCaps = myCaps;
                if (action.isPlace()) {
                    if (action.piece.isCapstone()) nextMyCaps--;
                    else nextMyStones--;
                }

                int score = alphaBeta(next, depth - 1, alpha, beta, false,
                        myColor, oppColor, nextMyStones, nextMyCaps, oppStones, oppCaps);
                if (timedOut) {
                    completed = false;
                    break;
                }

                if (score > depthBestScore) {
                    depthBestScore = score;
                    depthBest = action;
                }
                if (score > alpha) alpha = score;
            }

            if (completed && depthBest != null) {
                best = depthBest;
                bestScore = depthBestScore;
            }
            if (bestScore >= WIN_REWARD / 2) break;
        }

        return best;
    }

    private int alphaBeta(BoardState state, int depth, int alpha, int beta, boolean maximizing,
                          Color myColor, Color oppColor,
                          int myStones, int myCaps, int oppStones, int oppCaps) {
        if (timeUp()) {
            timedOut = true;
            return evaluate(state, myColor, oppColor, myStones, myCaps, oppStones, oppCaps);
        }

        long hash = hashPosition(state, maximizing, myStones, myCaps, oppStones, oppCaps);
        int ttIndex = (int) (hash & ttMask);
        TTEntry entry = tt[ttIndex];
        Action ttMove = null;
        int originalAlpha = alpha;
        if (entry != null && entry.hash == hash) {
            ttMove = entry.bestMove;
            if (entry.depth >= depth) {
                if (entry.type == Bound.EXACT) return entry.value;
                if (entry.type == Bound.LOWER && entry.value >= beta) return entry.value;
                if (entry.type == Bound.UPPER && entry.value <= alpha) return entry.value;
            }
        }

        if (depth <= 0) {
            int value = tacticalEvaluate(state, myColor, oppColor, myStones, myCaps, oppStones, oppCaps);
            storeTT(ttIndex, hash, value, depth, Bound.EXACT, null);
            return value;
        }

        Color current = maximizing ? myColor : oppColor;
        Color other = maximizing ? oppColor : myColor;
        int currentStones = maximizing ? myStones : oppStones;
        int currentCaps = maximizing ? myCaps : oppCaps;
        List<Action> legal = MoveGenerator.generateLegal(state, current, currentStones, currentCaps, other, false);

        if (legal.isEmpty()) {
            return evaluate(state, myColor, oppColor, myStones, myCaps, oppStones, oppCaps);
        }

        List<Action> ordered = orderMoves(state, legal, depth, ttMove, current, other, myColor);
        int value = maximizing ? Integer.MIN_VALUE + 1 : Integer.MAX_VALUE - 1;
        Action bestLocal = null;

        for (Action action : ordered) {
            if (timeUp()) {
                timedOut = true;
                break;
            }

            BoardState next = new BoardState(state);
            Color winner = MoveApplier.apply(next, action);
            if (winner == myColor) {
                int win = WIN_REWARD - (MAX_DEPTH - depth);
                storeTT(ttIndex, hash, win, depth, Bound.EXACT, action);
                return win;
            }
            if (winner == oppColor) {
                int loss = LOSS_PENALTY + (MAX_DEPTH - depth);
                storeTT(ttIndex, hash, loss, depth, Bound.EXACT, action);
                return loss;
            }

            int nextMyStones = myStones;
            int nextMyCaps = myCaps;
            int nextOppStones = oppStones;
            int nextOppCaps = oppCaps;
            if (action.isPlace()) {
                if (maximizing) {
                    if (action.piece.isCapstone()) nextMyCaps--;
                    else nextMyStones--;
                } else {
                    if (action.piece.isCapstone()) nextOppCaps--;
                    else nextOppStones--;
                }
            }

            int child = alphaBeta(next, depth - 1, alpha, beta, !maximizing,
                    myColor, oppColor, nextMyStones, nextMyCaps, nextOppStones, nextOppCaps);

            if (maximizing) {
                if (child > value) {
                    value = child;
                    bestLocal = action;
                }
                if (value > alpha) alpha = value;
            } else {
                if (child < value) {
                    value = child;
                    bestLocal = action;
                }
                if (value < beta) beta = value;
            }

            if (alpha >= beta) {
                storeKiller(depth, action);
                break;
            }
        }

        byte type = Bound.EXACT;
        if (value <= originalAlpha) type = Bound.UPPER;
        else if (value >= beta) type = Bound.LOWER;
        storeTT(ttIndex, hash, value, depth, type, bestLocal);
        return value;
    }

    private int tacticalEvaluate(BoardState state, Color myColor, Color oppColor,
                                 int myStones, int myCaps, int oppStones, int oppCaps) {
        int value = evaluate(state, myColor, oppColor, myStones, myCaps, oppStones, oppCaps);

        if (hasImmediateWin(state, myColor, myStones, myCaps, oppColor)) {
            value += TACTICAL_WIN;
        }
        if (hasImmediateWin(state, oppColor, oppStones, oppCaps, myColor)) {
            value -= TACTICAL_WIN;
        }

        return value;
    }

    private int evaluate(BoardState state, Color myColor, Color oppColor,
                         int myStones, int myCaps, int oppStones, int oppCaps) {
        int score = Evaluator.evaluate(state, myColor);
        score += 18 * (myStones - oppStones);
        score += 120 * (myCaps - oppCaps);

        int myRoad = bestRoadDistance(state, myColor);
        int oppRoad = bestRoadDistance(state, oppColor);
        score += ROAD_WEIGHT * (oppRoad - myRoad);
        score += stackMobility(state, myColor) - stackMobility(state, oppColor);

        return score;
    }

    private List<Action> orderRootMoves(BoardState state, List<Action> legal, Action previousBest,
                                        Color current, Color opponent) {
        return orderMoves(state, legal, MAX_DEPTH + 1, previousBest, current, opponent, current);
    }

    private List<Action> orderMoves(BoardState state, List<Action> legal, int depth, Action preferred,
                                    Color current, Color opponent, Color perspective) {
        ArrayList<ScoredAction> scored = new ArrayList<>(legal.size());
        Action killer0 = depth < killerMoves.length ? killerMoves[depth][0] : null;
        Action killer1 = depth < killerMoves.length ? killerMoves[depth][1] : null;

        for (Action action : legal) {
            int score = 0;
            if (actionsEqual(action, preferred)) score += 900_000;
            if (actionsEqual(action, killer0)) score += 700_000;
            if (actionsEqual(action, killer1)) score += 650_000;

            BoardState next = new BoardState(state);
            Color winner = MoveApplier.apply(next, action);
            if (winner == current) score += 1_200_000;
            else if (winner == opponent) score -= 1_200_000;

            score += quickActionScore(state, next, action, current, opponent, perspective);
            scored.add(new ScoredAction(action, score));
        }

        scored.sort(Comparator.comparingInt((ScoredAction sa) -> sa.score).reversed());
        ArrayList<Action> ordered = new ArrayList<>(scored.size());
        for (ScoredAction entry : scored) ordered.add(entry.action);
        return ordered;
    }

    private int quickActionScore(BoardState before, BoardState after, Action action,
                                 Color current, Color opponent, Color perspective) {
        int sign = current == perspective ? 1 : -1;
        int score = sign * 1_500 * (bestRoadDistance(before, current) - bestRoadDistance(after, current));
        score += sign * 900 * (bestRoadDistance(after, opponent) - bestRoadDistance(before, opponent));

        if (action.isPlace()) {
            int center = before.getSize() / 2;
            int dist = Math.abs(action.position.x - center) + Math.abs(action.position.y - center);
            score += sign * (90 - 12 * dist);
            if (action.piece.isCapstone()) score += sign * 55;
            if (action.piece.isMenhir()) score += sign * 25;
        } else if (action.isMove()) {
            score += sign * (40 + before.stackHeight(action.position.y, action.position.x) * 8);
        }
        return score;
    }

    private int bestRoadDistance(BoardState state, Color color) {
        int vertical = roadDistance(state, color, true);
        int horizontal = roadDistance(state, color, false);
        return Math.min(vertical, horizontal);
    }

    private int roadDistance(BoardState state, Color color, boolean vertical) {
        int size = state.getSize();
        int[][] dist = new int[size][size];
        boolean[][] done = new boolean[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                dist[row][col] = 1_000_000;
            }
        }

        for (int i = 0; i < size; i++) {
            int row = vertical ? 0 : i;
            int col = vertical ? i : 0;
            dist[row][col] = cellCost(state, color, row, col);
        }

        for (int step = 0; step < size * size; step++) {
            int bestRow = -1;
            int bestCol = -1;
            int best = 1_000_000;
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    if (!done[row][col] && dist[row][col] < best) {
                        best = dist[row][col];
                        bestRow = row;
                        bestCol = col;
                    }
                }
            }
            if (bestRow < 0) break;
            done[bestRow][bestCol] = true;

            relax(state, color, dist, done, bestRow, bestCol, bestRow + 1, bestCol);
            relax(state, color, dist, done, bestRow, bestCol, bestRow - 1, bestCol);
            relax(state, color, dist, done, bestRow, bestCol, bestRow, bestCol + 1);
            relax(state, color, dist, done, bestRow, bestCol, bestRow, bestCol - 1);
        }

        int target = 1_000_000;
        for (int i = 0; i < size; i++) {
            int row = vertical ? size - 1 : i;
            int col = vertical ? i : size - 1;
            target = Math.min(target, dist[row][col]);
        }
        return Math.min(target, size + 4);
    }

    private void relax(BoardState state, Color color, int[][] dist, boolean[][] done,
                       int fromRow, int fromCol, int toRow, int toCol) {
        if (!state.inBounds(toRow, toCol) || done[toRow][toCol]) return;
        int next = dist[fromRow][fromCol] + cellCost(state, color, toRow, toCol);
        if (next < dist[toRow][toCol]) dist[toRow][toCol] = next;
    }

    private int cellCost(BoardState state, Color color, int row, int col) {
        if (state.isFree(row, col)) return 1;
        Piece top = state.getTop(row, col);
        if (top.color == color) return top.isMenhir() ? 3 : 0;
        return top.isMenhir() ? 2 : 5;
    }

    private int stackMobility(BoardState state, Color color) {
        int score = 0;
        for (int row = 0; row < state.getSize(); row++) {
            for (int col = 0; col < state.getSize(); col++) {
                if (!state.isUnderControl(color, row, col)) continue;
                Piece top = state.getTop(row, col);
                score += 10 + state.stackHeight(row, col) * 6;
                if (top.isCapstone()) score += 35;
                if (top.isMenhir()) score -= 12;
            }
        }
        return score;
    }

    private boolean hasImmediateWin(BoardState state, Color player, int stones, int caps, Color opponent) {
        List<Action> legal = MoveGenerator.generateLegal(state, player, stones, caps, opponent, false);
        return findWinningAction(state, legal, player) != null;
    }

    private Action findWinningAction(BoardState state, List<Action> legal, Color player) {
        for (Action action : legal) {
            BoardState next = new BoardState(state);
            if (MoveApplier.apply(next, action) == player) return action;
        }
        return null;
    }

    private Action openingPlacement(Board board, Player opponent) {
        Piece dolmen = dolmenOf(opponent.getColor());
        int size = board.getSize();
        int center = size / 2;
        for (int radius = 0; radius <= CENTER_FIRST_RADIUS; radius++) {
            for (int row = center - radius; row <= center + radius; row++) {
                for (int col = center - radius; col <= center + radius; col++) {
                    if (row >= 0 && row < size && col >= 0 && col < size && board.isFree(row, col)) {
                        return new Action(dolmen, new Point(col, row));
                    }
                }
            }
        }
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (board.isFree(row, col)) return new Action(dolmen, new Point(col, row));
            }
        }
        return new Action();
    }

    private void storeKiller(int depth, Action action) {
        if (depth < 0 || depth >= killerMoves.length) return;
        if (actionsEqual(killerMoves[depth][0], action)) return;
        killerMoves[depth][1] = killerMoves[depth][0];
        killerMoves[depth][0] = action;
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

    private static Piece dolmenOf(Color color) {
        return color == Constants.BLACK_PLAYER ? Piece.DOLMEN_BLACK : Piece.DOLMEN_WHITE;
    }

    private static boolean actionsEqual(Action left, Action right) {
        if (left == null || right == null) return left == right;
        if (left.isSkip() != right.isSkip()) return false;
        if (left.isPlace() != right.isPlace()) return false;
        if (left.isMove() != right.isMove()) return false;
        if (left.isSkip()) return true;
        if (left.isPlace()) return left.piece == right.piece && pointsEqual(left.position, right.position);
        if (!pointsEqual(left.position, right.position)) return false;
        if (!pointsEqual(left.destination, right.destination)) return false;
        int[] leftAmount = left.getAmount();
        int[] rightAmount = right.getAmount();
        if (leftAmount.length != rightAmount.length) return false;
        for (int i = 0; i < leftAmount.length; i++) {
            if (leftAmount[i] != rightAmount[i]) return false;
        }
        return true;
    }

    private static boolean pointsEqual(Point left, Point right) {
        return left.x == right.x && left.y == right.y;
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
        static final byte EXACT = 0;
        static final byte LOWER = 1;
        static final byte UPPER = 2;
    }

    private static final class ScoredAction {
        final Action action;
        final int score;

        ScoredAction(Action action, int score) {
            this.action = action;
            this.score = score;
        }
    }
}
