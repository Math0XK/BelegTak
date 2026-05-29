package be.heh.math.core.eval;

import be.belegkarnil.game.board.tak.Constants;
import be.belegkarnil.game.board.tak.Piece;
import be.belegkarnil.game.board.tak.Player;
import be.heh.math.core.state.BoardState;

import java.awt.Color;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Queue;

public final class EvaluatorV2 {

    public static final int WIN_BONUS = 100_000;
    public static final int W_COMPONENT = 100;
    public static final int W_SPREAD = 80;
    public static final int W_DOLMEN_TOP = 30;
    public static final int W_CAPSTONE_HAND = 20;
    public static final int W_TILE_CONTROL = 8;
    public static final int W_CENTER_BONUS = 2;
    public static final int W_CAPSTONE_ON_BOARD = 25;
    public static final int W_ROAD = 2000;

    private EvaluatorV2() {
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
        score += pathScore(board, myColor, opponentColor);
        return score;
    }

    private static int cellCostFor(BoardState board, Color color, int row, int col) {
        if (board.isFree(row, col)) return 1;
        Piece top = board.getTop(row, col);
        if (top.color == color) {
            return top.isMenhir() ? 3 : 0;
        }
        else {
            if (top.isMenhir()) return 999;
            return top.isCapstone() ? 999 : 3;
        }
    }

    private static int shortestPathToWin(BoardState board, Color me, Color opp, boolean horizontal) {
        int size = board.getSize();
        int[][] dist = new int[size][size];
        boolean[][] visited = new boolean[size][size];

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                dist[i][j] = Integer.MAX_VALUE;
            }
        }

        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));

        if(horizontal) {
            for (int row = 0; row < size; row++) {
                int cost = cellCostFor(board, me, row, 0);
                if(cost < 999) {
                    dist[row][0] = cost;
                    pq.add(new int[]{cost, row, 0});
                }
            }
        } else {
            for (int col = 0; col < size; col++) {
                int cost = cellCostFor(board, me, 0, col);
                if(cost < 999) {
                    dist[0][col] = cost;
                    pq.add(new int[]{cost, 0, col});
                }
            }
        }

        while(!pq.isEmpty()) {
            int[] entry = pq.poll();
            int d = entry[0];
            int r = entry[1];
            int c = entry[2];

            if (visited[r][c]) continue;
            visited[r][c] = true;

            if (horizontal && c == size - 1) return d;
            if (!horizontal && r == size - 1) return d;

            for (int[] dir : DIRECTIONS) {
                int nr = r + dir[0];
                int nc = c + dir[1];
                
                if(!board.inBounds(nr, nc) || visited[nr][nc]) continue;
                
                int stepCost = cellCostFor(board, me, nr, nc);
                if(stepCost == 999) continue;

                int newDist = d + stepCost;
                if(newDist < dist[nr][nc]) {
                    dist[nr][nc] = newDist;
                    pq.add(new int[]{newDist, nr, nc});
                }
            }
        }
        return Integer.MAX_VALUE; // No path found
    }

    private static int pathScore(BoardState board, Color myColor, Color opponentColor) {
        int myHorizontal = shortestPathToWin(board, myColor, opponentColor, true);
        int myVertical = shortestPathToWin(board, myColor, opponentColor, false);
        int oppHorizontal = shortestPathToWin(board, opponentColor, myColor, true);
        int oppVertical = shortestPathToWin(board, opponentColor, myColor, false);

        int myBest = Math.min(myHorizontal, myVertical);
        int oppBest = Math.min(oppHorizontal, oppVertical);

        if (myBest == Integer.MAX_VALUE) return -WIN_BONUS;
        if (oppBest == Integer.MAX_VALUE) return WIN_BONUS;

        return W_ROAD * (oppBest - myBest);
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

    private static final int[][] DIRECTIONS = {
        {1, 0},
        {-1, 0},
        {0, 1},
        {0, -1}
    };
}
