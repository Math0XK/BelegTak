/*
 *  Copyright 2025 Belegkarnil
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 *  associated documentation files (the “Software”), to deal in the Software without restriction,
 *  including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 *  and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do
 *  so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all copies or substantial
 *  portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 *  FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 *  OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 *  WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 *  CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package be.belegkarnil.game.board.tak;

import be.belegkarnil.game.board.tak.strategy.Strategy;
import be.belegkarnil.game.board.tak.strategy.StrategyAdapter;

import java.awt.Color;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.HashMap;
import java.util.Map;

/**
 * This class represents a Tak player that use a {@link Strategy} and owns some {@link Piece}s.
 * It also maintains state information like the player name, the current (round) score, the number of round won during a {@link Game}, the number of time the player skip his turn (during a round).
 *
 * @author Belegkarnil
 */
public class Player implements Externalizable{
	private String name;
	private int score;
	private int win, skip;
	private Strategy strategy;
	private int capstones;
	private int stones;
	private Color color;
	private boolean firstTurn;

	/**
	 * Construct a new player
	 *
	 * @param name     The player's name
	 * @param strategy The strategy used by the player
	 */
	public Player(String name, Strategy strategy){
		this.name = name;
		this.strategy = strategy;
		this.win		= 0;
		firstTurn	= false;
		initialize(0,0);
	}

	/**
	 * Know if the player owns a specific {link @Piece}
	 *
	 * @param piece the piece to know if the player owns
	 * @return true iff the player owns the piece
	 */
	public final boolean hasPiece(Piece piece){
		if(firstTurn){
			return piece.isDolmen() && !piece.color.equals(color);
		}
		if(!piece.color.equals(color)) return false;
		if(piece.isCapstone()) return this.capstones > 0;
		return this.stones > 0;
	}

	/**
	 * Get the player's name
	 *
	 * @return The player's name
	 */
	public String getName(){
		return name;
	}

	/**
	 * Get the current (round) score
	 *
	 * @return the score
	 */
	public int getScore(){
		return score;
	}

	/**
	 * Count the number of times the player won a round during a game
	 *
	 * @return The number of times the player won a round
	 */
	public int countWin(){
		return win;
	}

	/**
	 * Count the number of times that the player skip his turn during a round
	 *
	 * @return the number of times that the player skip his turn
	 */
	public int countSkip(){
		return skip;
	}

	void setScore(int score){
		this.score = score;
	}

	/**
	 * Ask the player to use his {@link Strategy} and select an {@link Action} to play
	 *
	 * @param board    The current board
	 * @param opponent The current opponent
	 * @return The action selection by the strategy
	 */
	Action behaves(Board board, Player opponent){
		return strategy.plays(this, board, opponent);
	}

	/**
	 * Count the stones {@link Piece}s owned by the player
	 *
	 * @return the number of MENHIR and DOLMEN
	 */
	public int countStones(){
		return this.stones;
	}
	/**
	 * Count the capstones {@link Piece}s owned by the player
	 *
	 * @return the number of DOME
	 */
	public int countCapstones(){
		return this.capstones;
	}

	/**
	 * Get a dictionary of amount of {@link Piece}s that the player has.
	 * @return a {@link Map<Piece,Integer>} of key {@link Piece} and an {@link Integer} amount which is how much pieces the player has.
	 */
	public Map<Piece,Integer> getPieces(){
		final Map<Piece,Integer> pieces;
		final boolean isBlack = getColor().equals(Constants.BLACK_PLAYER);
		if(firstTurn){
			pieces = new HashMap<Piece,Integer>(1);
			pieces.put(isBlack?Piece.DOLMEN_WHITE:Piece.DOLMEN_BLACK,Integer.valueOf(this.stones));
		}else{
			pieces = new HashMap<Piece,Integer>(3);
			if(isBlack){
				pieces.put(Piece.DOLMEN_BLACK,Integer.valueOf(this.stones));
				pieces.put(Piece.MENHIR_BLACK,Integer.valueOf(this.stones));
				pieces.put(Piece.CAPSTONE_BLACK,Integer.valueOf(this.capstones));
			}else{
				pieces.put(Piece.DOLMEN_WHITE,Integer.valueOf(this.stones));
				pieces.put(Piece.MENHIR_WHITE,Integer.valueOf(this.stones));
				pieces.put(Piece.CAPSTONE_WHITE,Integer.valueOf(this.capstones));
			}
		}
		return pieces;
	}

	/**
	 * Know if the player has at least one {@link Piece}
	 *
	 * @return true iff the player has at least one {@link Piece}
	 */
	public boolean hasPieces(){
		return this.stones>0 || this.capstones >0;
	}

	/**
	 * Get the strategy used by the player
	 *
	 * @return The strategy
	 */
	public Strategy getStrategy(){
		return strategy;
	}

	void win(){
		win++;
	}

	void skip(){
		skip++;
	}

	void initialize(final int stones, final int capstones){
		this.color	= null;
		this.skip	= 0;
		this.score	= 0;
		this.stones	= stones;
		this.capstones = capstones;
		this.firstTurn	= true;
	}

	/**
	 * Used by {@link Externalizable}
	 *
	 * @param out see {@link Externalizable}
	 * @throws IOException see {@link Externalizable}
	 */
	@Override
	public void writeExternal(ObjectOutput out) throws IOException{
		out.writeUTF(name);
		out.writeInt(win);
		out.writeInt(skip);
		out.writeInt(score);
		out.writeInt(stones);
		out.writeInt(capstones);
	}

	/**
	 * Used by {@link Externalizable}
	 *
	 * @param in see {@link Externalizable}
	 * @throws IOException            see {@link Externalizable}
	 * @throws ClassNotFoundException see {@link Externalizable}
	 */
	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException{
		this.name	= in.readUTF();
		this.win		= in.readInt();
		this.skip	= in.readInt();
		this.score	= in.readInt();
		this.stones	= in.readInt();
		this.capstones = in.readInt();

		this.strategy = new StrategyAdapter(){
			@Override
			public Action plays(Player myself, Board board, Player opponent){
				return null;
			}
		};
	}

	void plays(Piece piece){
		firstTurn = false;
		if(piece.isCapstone()){
			this.capstones--;
			return;
		}
		this.stones--;
	}

	/**
	 * Count the number of pieces the player owns
	 *
	 * @return the number of pieces the player has in its hands
	 */
	public int countPieces(){
		return this.stones + this.capstones;
	}

	/**
	 * Get the current assigned color of the player
	 * @return the assigned color (see {@link Constants})
	 */
	public Color getColor(){
		return this.color;
	}
	/**
	 * Assign a color to the player
	 * @param color the assigned color (see {@link Constants})
	 */
	void setColor(Color color){
		this.color = color;
	}
}