package be.heh.math.core.state;

import be.belegkarnil.game.board.tak.Board;
import be.belegkarnil.game.board.tak.Constants;
import be.belegkarnil.game.board.tak.Player;

import java.awt.Color;

public class GameState {

    private final BoardState board;

    private int whiteStones;
    private int blackStones;
    private int whiteCaps;
    private int blackCaps;
    private Color sideToMove;
    private int ply;

    public GameState(Board board, Player white, Player black) {
        this.board = new BoardState(board);
        this.whiteStones = white.countStones();
        this.blackStones = black.countStones();
        this.whiteCaps = white.countCapstones();
        this.blackCaps = black.countCapstones();
        this.sideToMove = white.getColor();
        this.ply = 0;
    }

    public GameState(GameState other) {
        this.board = new BoardState(other.board);
        this.whiteStones = other.whiteStones;
        this.blackStones = other.blackStones;
        this.whiteCaps = other.whiteCaps;
        this.blackCaps = other.blackCaps;
        this.sideToMove = other.sideToMove;
        this.ply = other.ply;
    }

    public BoardState getBoard() {
        return board;
    }

    public Color getSideToMove() {
        return sideToMove;
    }

    public void setSideToMove(Color sideToMove) {
        this.sideToMove = sideToMove;
    }

    public int getPly() {
        return ply;
    }

    public void advancePly() {
        ply++;
        sideToMove = sideToMove == Constants.BLACK_PLAYER ? Constants.WHITE_PLAYER : Constants.BLACK_PLAYER;
    }

    public int stones(Color color) {
        return color == Constants.BLACK_PLAYER ? blackStones : whiteStones;
    }

    public int capstones(Color color) {
        return color == Constants.BLACK_PLAYER ? blackCaps : whiteCaps;
    }

    public void consumePlacedPiece(Color color, boolean capstone) {
        if (color == Constants.BLACK_PLAYER) {
            if (capstone) blackCaps--;
            else blackStones--;
        } else {
            if (capstone) whiteCaps--;
            else whiteStones--;
        }
    }
}
