package be.heh.math.core.state;

import be.belegkarnil.game.board.tak.Board;
import be.belegkarnil.game.board.tak.Piece;
import be.heh.math.core.move.MoveValidator;
import be.heh.math.core.path.PathFinder;

import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class BoardState {

    private final Stack<Piece>[][] cells;
    private final int size;

    @SuppressWarnings("unchecked")
    public BoardState(Board board) {
        this.size = board.getSize();
        this.cells = new Stack[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                cells[row][col] = new Stack<>();
                for (Piece piece : board.getStack(row, col)) {
                    cells[row][col].push(piece);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public BoardState(BoardState other) {
        this.size = other.size;
        this.cells = new Stack[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                cells[row][col] = new Stack<>();
                cells[row][col].addAll(other.cells[row][col]);
            }
        }
    }

    public int getSize() {
        return size;
    }

    public int getLoadLimit() {
        return size;
    }

    public boolean inBounds(int row, int col) {
        return row >= 0 && row < size && col >= 0 && col < size;
    }

    public boolean isFree(int row, int col) {
        return cells[row][col].isEmpty();
    }

    public boolean isUnderControl(Color color, int row, int col) {
        return !isFree(row, col) && cells[row][col].peek().color == color;
    }

    public Piece getTop(int row, int col) {
        return isFree(row, col) ? null : cells[row][col].peek();
    }

    public int stackHeight(int row, int col) {
        return cells[row][col].size();
    }

    public Piece pop(int row, int col) {
        return cells[row][col].pop();
    }

    public void push(int row, int col, Piece piece) {
        cells[row][col].push(piece);
    }

    public int countTopPieces(Piece piece) {
        int count = 0;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (!isFree(row, col) && cells[row][col].peek().equals(piece)) {
                    count++;
                }
            }
        }
        return count;
    }

    public int countEmpty() {
        int count = 0;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (isFree(row, col)) {
                    count++;
                }
            }
        }
        return count;
    }

    public boolean canPlace(Piece piece, int row, int col) {
        return MoveValidator.canPlace(this, piece, row, col);
    }

    public boolean canPlace(Piece piece, Point point) {
        return canPlace(piece, point.y, point.x);
    }

    public boolean canMove(Color player, Point source, int[] amount, Point destination) {
        return canMove(player, source.y, source.x, amount, destination.y, destination.x);
    }

    public boolean canMove(Color player, int srcRow, int srcCol, int[] amount, int dstRow, int dstCol) {
        return MoveValidator.canMove(this, player, srcRow, srcCol, amount, dstRow, dstCol);
    }

    public boolean pathExists(int row, int col, Color color) {
        return PathFinder.pathExists(this, row, col, color);
    }

    public boolean anyPathExists(Color color) {
        return PathFinder.anyPathExists(this, color);
    }

    public List<Point> controlledPositions(Color color) {
        List<Point> positions = new ArrayList<>();
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (isUnderControl(color, row, col)) {
                    positions.add(new Point(col, row));
                }
            }
        }
        return positions;
    }

    public long hash() {
        long hash = 1469598103934665603L;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                Stack<Piece> stack = cells[row][col];
                hash ^= stack.size();
                hash *= 1099511628211L;
                for (Piece piece : stack) {
                    hash ^= piece == null ? 0 : piece.ordinal() + 1;
                    hash *= 1099511628211L;
                }
            }
        }
        return hash;
    }

    public boolean matchesBoard(Board board) {
        if (board.getSize() != size) {
            return false;
        }
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                Piece[] boardStack = board.getStack(row, col);
                Stack<Piece> stateStack = cells[row][col];
                if (boardStack.length != stateStack.size()) {
                    return false;
                }
                for (int i = 0; i < boardStack.length; i++) {
                    if (boardStack[i] != stateStack.get(i)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
