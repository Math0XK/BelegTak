package be.heh.math.core.move;

import be.belegkarnil.game.board.tak.Piece;
import be.heh.math.core.state.BoardState;

import java.awt.Color;

public final class MoveValidator {

    private MoveValidator() {
    }

    public static boolean canPlace(BoardState board, Piece piece, int row, int col) {
        return piece != null && board.inBounds(row, col) && board.isFree(row, col);
    }

    public static boolean canMove(BoardState board, Color player,
                                  int srcRow, int srcCol, int[] amount,
                                  int dstRow, int dstCol) {
        if (!board.inBounds(srcRow, srcCol) || !board.inBounds(dstRow, dstCol)) return false;
        if (!board.isUnderControl(player, srcRow, srcCol)) return false;
        if (srcRow != dstRow && srcCol != dstCol) return false;
        if (srcRow == dstRow && srcCol == dstCol) return false;
        if (amount == null || amount.length < 1) return false;

        int total = 0;
        for (int drop : amount) {
            if (drop < 1) return false;
            total += drop;
        }
        if (total > board.getLoadLimit() || board.stackHeight(srcRow, srcCol) < total) {
            return false;
        }

        int distance = Math.abs(dstRow - srcRow) + Math.abs(dstCol - srcCol);
        if (distance != amount.length) {
            return false;
        }

        int rowStep = Integer.compare(dstRow, srcRow);
        int colStep = Integer.compare(dstCol, srcCol);
        for (int step = 1; step < distance; step++) {
            int row = srcRow + rowStep * step;
            int col = srcCol + colStep * step;
            if (!board.isFree(row, col) && !board.getTop(row, col).isDolmen()) {
                return false;
            }
        }

        return canLandOnDestination(board, dstRow, dstCol, amount, board.getTop(srcRow, srcCol).isCapstone());
    }

    private static boolean canLandOnDestination(BoardState board, int row, int col,
                                                int[] amount, boolean topIsCapstone) {
        if (board.isFree(row, col)) {
            return true;
        }

        Piece top = board.getTop(row, col);
        if (top.isDolmen()) {
            return true;
        }

        return top.isMenhir() && amount[amount.length - 1] == 1 && topIsCapstone;
    }
}
