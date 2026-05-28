package be.heh.math.core.path;

import be.belegkarnil.game.board.tak.Piece;
import be.heh.math.core.state.BoardState;

import java.awt.Color;
import java.awt.Point;
import java.util.LinkedList;
import java.util.Queue;

public final class PathFinder {

    private PathFinder() {
    }

    public static boolean pathExists(BoardState board, int row, int col, Color color) {
        if (!board.inBounds(row, col)) {
            return false;
        }

        boolean linkLeft = false;
        boolean linkRight = false;
        boolean linkTop = false;
        boolean linkBottom = false;
        int size = board.getSize();
        int last = size - 1;
        boolean[][] visited = new boolean[size][size];

        Queue<Point> frontier = new LinkedList<>();
        frontier.add(new Point(col, row));

        while (!frontier.isEmpty()) {
            Point current = frontier.poll();
            if (visited[current.y][current.x] || board.isFree(current.y, current.x)) {
                continue;
            }

            Piece top = board.getTop(current.y, current.x);
            if (!top.color.equals(color) || top.isMenhir()) {
                continue;
            }

            visited[current.y][current.x] = true;
            if (current.x == 0) linkLeft = true;
            if (current.x == last) linkRight = true;
            if (current.y == 0) linkTop = true;
            if (current.y == last) linkBottom = true;

            addIfInBounds(board, frontier, current.y + 1, current.x);
            addIfInBounds(board, frontier, current.y - 1, current.x);
            addIfInBounds(board, frontier, current.y, current.x + 1);
            addIfInBounds(board, frontier, current.y, current.x - 1);
        }

        return (linkTop && linkBottom) || (linkLeft && linkRight);
    }

    public static boolean anyPathExists(BoardState board, Color color) {
        for (int row = 0; row < board.getSize(); row++) {
            for (int col = 0; col < board.getSize(); col++) {
                if (board.isUnderControl(color, row, col)
                        && !board.getTop(row, col).isMenhir()
                        && pathExists(board, row, col, color)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addIfInBounds(BoardState board, Queue<Point> frontier, int row, int col) {
        if (board.inBounds(row, col)) {
            frontier.add(new Point(col, row));
        }
    }
}
