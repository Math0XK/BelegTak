package be.heh.math.core.move;

import be.belegkarnil.game.board.tak.Action;
import be.belegkarnil.game.board.tak.Constants;
import be.belegkarnil.game.board.tak.Piece;
import be.heh.math.core.state.BoardState;
import be.heh.math.core.state.GameState;

import java.awt.Color;
import java.awt.Point;
import java.util.Stack;

public final class MoveApplier {

    private MoveApplier() {
    }

    public static Color apply(GameState state, Action action) {
        return apply(state.getBoard(), action);
    }

    public static Color apply(BoardState board, Action action) {
        if (action == null || action.isSkip()) return null;
        if (action.isPlace()) return applyPlace(board, action.piece, action.position);
        if (action.isMove()) return applyMove(board, action.position, action.getAmount(), action.destination);
        return null;
    }

    private static Color applyPlace(BoardState board, Piece piece, Point position) {
        board.push(position.y, position.x, piece);
        if (board.pathExists(position.y, position.x, piece.color)) {
            return piece.color;
        }

        Color opponent = opponentOf(piece.color);
        return board.pathExists(position.y, position.x, opponent) ? opponent : null;
    }

    private static Color applyMove(BoardState board, Point source, int[] amount, Point destination) {
        int deltaX = source.y == destination.y ? Integer.signum(destination.x - source.x) : 0;
        int deltaY = source.x == destination.x ? Integer.signum(destination.y - source.y) : 0;

        Color player = board.getTop(source.y, source.x).color;
        Color opponent = opponentOf(player);

        int totalCarry = 0;
        for (int drop : amount) {
            totalCarry += drop;
        }

        Stack<Piece> carry = new Stack<>();
        for (int i = 0; i < totalCarry; i++) {
            carry.push(board.pop(source.y, source.x));
        }

        Color firstCompleted = null;
        int row = source.y;
        int col = source.x;

        for (int i = 0; i < amount.length; i++) {
            row += deltaY;
            col += deltaX;
            boolean lastCell = i == amount.length - 1;

            if (lastCell && shouldFlattenMenhir(board, row, col, carry, amount[i])) {
                Piece menhir = board.pop(row, col);
                board.push(row, col, menhir == Piece.MENHIR_BLACK ? Piece.DOLMEN_BLACK : Piece.DOLMEN_WHITE);
                board.push(row, col, carry.pop());
            } else {
                for (int c = 0; c < amount[i]; c++) {
                    board.push(row, col, carry.pop());
                }
            }

            if (firstCompleted == null && board.pathExists(row, col, player)) {
                firstCompleted = player;
            }
            if (firstCompleted == null && board.pathExists(row, col, opponent)) {
                firstCompleted = opponent;
            }
        }

        return firstCompleted;
    }

    private static boolean shouldFlattenMenhir(BoardState board, int row, int col,
                                               Stack<Piece> carry, int lastDrop) {
        return lastDrop == 1
                && carry.size() == 1
                && carry.peek().isCapstone()
                && !board.isFree(row, col)
                && board.getTop(row, col).isMenhir();
    }

    private static Color opponentOf(Color color) {
        return color == Constants.BLACK_PLAYER ? Constants.WHITE_PLAYER : Constants.BLACK_PLAYER;
    }
}
