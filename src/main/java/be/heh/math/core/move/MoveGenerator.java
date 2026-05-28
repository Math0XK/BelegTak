package be.heh.math.core.move;

import be.belegkarnil.game.board.tak.Action;
import be.belegkarnil.game.board.tak.Board;
import be.belegkarnil.game.board.tak.Constants;
import be.belegkarnil.game.board.tak.Piece;
import be.belegkarnil.game.board.tak.Player;
import be.heh.math.core.state.BoardState;

import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public final class MoveGenerator {

    private static final Point[] DIRECTIONS = {
            new Point(1, 0),
            new Point(-1, 0),
            new Point(0, 1),
            new Point(0, -1)
    };

    private MoveGenerator() {
    }

    public static List<Action> generateLegal(Board board, Player myself, Player opponent, boolean firstAction) {
        return generateLegal(new BoardState(board), myself, opponent, firstAction);
    }

    public static List<Action> generateLegal(BoardState board, Player myself, Player opponent, boolean firstAction) {
        if (firstAction) {
            return firstPlacementActions(board, opponent.getColor());
        }

        return generateLegal(
                board,
                myself.getColor(),
                myself.countStones(),
                myself.countCapstones(),
                opponent.getColor(),
                false);
    }

    public static List<Action> generateLegal(BoardState board, Color me, int myStones, int myCapstones,
                                             Color opponentColor, boolean firstAction) {
        if (firstAction) {
            return firstPlacementActions(board, opponentColor);
        }

        List<Action> actions = new ArrayList<>(256);
        addPlacementActions(actions, board, me, myStones, myCapstones);
        addStackMoveActions(actions, board, me);
        return actions;
    }

    private static List<Action> firstPlacementActions(BoardState board, Color opponentColor) {
        List<Action> actions = new ArrayList<>(board.getSize() * board.getSize());
        Piece opponentDolmen = dolmenOf(opponentColor);
        for (int row = 0; row < board.getSize(); row++) {
            for (int col = 0; col < board.getSize(); col++) {
                if (board.isFree(row, col)) {
                    actions.add(new Action(opponentDolmen, new Point(col, row)));
                }
            }
        }
        return actions;
    }

    private static void addPlacementActions(List<Action> actions, BoardState board,
                                            Color color, int stones, int capstones) {
        List<Piece> available = availablePieces(color, stones, capstones);
        if (available.isEmpty()) {
            return;
        }

        for (int row = 0; row < board.getSize(); row++) {
            for (int col = 0; col < board.getSize(); col++) {
                if (!board.isFree(row, col)) continue;

                Point position = new Point(col, row);
                for (Piece piece : available) {
                    if (board.canPlace(piece, position)) {
                        actions.add(new Action(piece, position));
                    }
                }
            }
        }
    }

    private static void addStackMoveActions(List<Action> actions, BoardState board, Color color) {
        int size = board.getSize();
        int loadLimit = board.getLoadLimit();

        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                if (!board.isUnderControl(color, row, col)) continue;

                int maxCarry = Math.min(loadLimit, board.stackHeight(row, col));
                for (Point direction : DIRECTIONS) {
                    addMovesInDirection(actions, board, color, row, col, direction, maxCarry);
                }
            }
        }
    }

    private static void addMovesInDirection(List<Action> actions, BoardState board, Color color,
                                            int row, int col, Point direction, int maxCarry) {
        for (int distance = 1; distance <= board.getLoadLimit(); distance++) {
            int dstRow = row + direction.y * distance;
            int dstCol = col + direction.x * distance;
            if (!board.inBounds(dstRow, dstCol)) {
                break;
            }

            for (int total = distance; total <= maxCarry; total++) {
                for (int[] amount : compositions(total, distance)) {
                    if (board.canMove(color, row, col, amount, dstRow, dstCol)) {
                        actions.add(new Action(new Point(col, row), new Point(dstCol, dstRow), amount));
                    }
                }
            }
        }
    }

    private static List<Piece> availablePieces(Color color, int stones, int capstones) {
        List<Piece> pieces = new LinkedList<>();
        if (stones > 0) {
            pieces.add(dolmenOf(color));
            pieces.add(menhirOf(color));
        }
        if (capstones > 0) {
            pieces.add(capstoneOf(color));
        }
        return pieces;
    }

    private static Piece dolmenOf(Color color) {
        return color == Constants.BLACK_PLAYER ? Piece.DOLMEN_BLACK : Piece.DOLMEN_WHITE;
    }

    private static Piece menhirOf(Color color) {
        return color == Constants.BLACK_PLAYER ? Piece.MENHIR_BLACK : Piece.MENHIR_WHITE;
    }

    private static Piece capstoneOf(Color color) {
        return color == Constants.BLACK_PLAYER ? Piece.CAPSTONE_BLACK : Piece.CAPSTONE_WHITE;
    }

    static List<int[]> compositions(int total, int parts) {
        List<int[]> out = new ArrayList<>();
        if (parts <= 0 || total < parts) return out;

        int[] buffer = new int[parts];
        composeRec(total, parts, 0, buffer, out);
        return out;
    }

    private static void composeRec(int remaining, int parts, int index, int[] buffer, List<int[]> out) {
        if (index == parts - 1) {
            buffer[index] = remaining;
            out.add(Arrays.copyOf(buffer, buffer.length));
            return;
        }

        int maxHere = remaining - (parts - 1 - index);
        for (int value = 1; value <= maxHere; value++) {
            buffer[index] = value;
            composeRec(remaining - value, parts, index + 1, buffer, out);
        }
    }
}
