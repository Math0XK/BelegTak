package be.heh.math.core.eval;

import be.belegkarnil.game.board.tak.Constants;
import be.belegkarnil.game.board.tak.Piece;
import be.belegkarnil.game.board.tak.Player;
import be.heh.math.core.state.BoardState;

import java.awt.Color;
import java.util.LinkedList;
import java.util.Queue;

public final class Evaluator {

    public static final int WIN_BONUS = 100_000;
    public static final int W_COMPONENT = 100;
    public static final int W_SPREAD = 80;
    public static final int W_DOLMEN_TOP = 30;
    public static final int W_CAPSTONE_HAND = 20;
    public static final int W_TILE_CONTROL = 8;
    public static final int W_CENTER_BONUS = 2;
    public static final int W_CAPSTONE_ON_BOARD = 25;

    private Evaluator() {
    }

    public static int evaluate(BoardState board, Player me, Player opponent) {
        int score = evaluate(board, me.getColor());
        score += W_CAPSTONE_HAND * (me.countCapstones() - opponent.countCapstones());
        return score;
    }

    public static int evaluate(BoardState board, Color myColor) {
        Color opponentColor = opponentOf(myColor);
        int score = 0;

        if (board.anyPathExists(myColor)) score += WIN_BONUS;
        if (board.anyPathExists(opponentColor)) score -= WIN_BONUS;

        ComponentStats mine = largestComponent(board, myColor);
        ComponentStats theirs = largestComponent(board, opponentColor);
        score += W_COMPONENT * (mine.size - theirs.size);
        score += W_SPREAD * (mine.spread - theirs.spread);

        Piece myDolmen = dolmenOf(myColor);
        Piece opponentDolmen = dolmenOf(opponentColor);
        score += W_DOLMEN_TOP * (board.countTopPieces(myDolmen) - board.countTopPieces(opponentDolmen));
        score += boardControlScore(board, myColor, opponentColor);

        return score;
    }

    private static int boardControlScore(BoardState board, Color myColor, Color opponentColor) {
        int score = 0;
        int size = board.getSize();
        int center = size / 2;

        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (board.isFree(row, col)) continue;

                Piece top = board.getTop(row, col);
                int distToCenter = Math.abs(row - center) + Math.abs(col - center);
                int tileScore = W_TILE_CONTROL + W_CENTER_BONUS * (size - distToCenter);
                if (top.isCapstone()) {
                    tileScore += W_CAPSTONE_ON_BOARD;
                }

                if (top.color == myColor) {
                    score += tileScore;
                } else if (top.color == opponentColor) {
                    score -= tileScore;
                }
            }
        }

        return score;
    }

    private static ComponentStats largestComponent(BoardState board, Color color) {
        int size = board.getSize();
        boolean[][] visited = new boolean[size][size];
        int bestSize = 0;
        int bestSpread = 0;

        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (visited[row][col] || !isRoadCell(board, color, row, col)) {
                    continue;
                }

                ComponentStats stats = scanComponent(board, color, row, col, visited);
                if (stats.size > bestSize) {
                    bestSize = stats.size;
                    bestSpread = stats.spread;
                }
            }
        }

        return new ComponentStats(bestSize, bestSpread);
    }

    private static ComponentStats scanComponent(BoardState board, Color color,
                                                int startRow, int startCol,
                                                boolean[][] visited) {
        int componentSize = 0;
        int minRow = startRow;
        int maxRow = startRow;
        int minCol = startCol;
        int maxCol = startCol;

        Queue<int[]> queue = new LinkedList<>();
        queue.add(new int[]{startRow, startCol});
        visited[startRow][startCol] = true;

        while (!queue.isEmpty()) {
            int[] current = queue.poll();
            int row = current[0];
            int col = current[1];
            componentSize++;
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);

            addNeighbor(board, color, row + 1, col, visited, queue);
            addNeighbor(board, color, row - 1, col, visited, queue);
            addNeighbor(board, color, row, col + 1, visited, queue);
            addNeighbor(board, color, row, col - 1, visited, queue);
        }

        int spread = Math.max(maxRow - minRow + 1, maxCol - minCol + 1);
        return new ComponentStats(componentSize, spread);
    }

    private static void addNeighbor(BoardState board, Color color, int row, int col,
                                    boolean[][] visited, Queue<int[]> queue) {
        if (!board.inBounds(row, col) || visited[row][col] || !isRoadCell(board, color, row, col)) {
            return;
        }

        visited[row][col] = true;
        queue.add(new int[]{row, col});
    }

    private static boolean isRoadCell(BoardState board, Color color, int row, int col) {
        return board.isUnderControl(color, row, col) && !board.getTop(row, col).isMenhir();
    }

    private static Piece dolmenOf(Color color) {
        return color == Constants.BLACK_PLAYER ? Piece.DOLMEN_BLACK : Piece.DOLMEN_WHITE;
    }

    private static Color opponentOf(Color color) {
        return color == Constants.BLACK_PLAYER ? Constants.WHITE_PLAYER : Constants.BLACK_PLAYER;
    }

    private static final class ComponentStats {
        final int size;
        final int spread;

        ComponentStats(int size, int spread) {
            this.size = size;
            this.spread = spread;
        }
    }
}
