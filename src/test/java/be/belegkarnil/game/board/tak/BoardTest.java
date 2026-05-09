package be.belegkarnil.game.board.tak;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Point;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import static org.junit.jupiter.api.Assertions.*;

class BoardTest implements Comparator<Point>{
	@Test
	void reset(){
		for(Board.Size size: Board.Size.values()){
			final Board board = new Board(size);
			final int n = size.length;
			Piece[] pieces = Piece.values();

			Map<Piece,Integer> counters = new HashMap<Piece,Integer>();
			for(Piece p: pieces)
				counters.put(p,Integer.valueOf(board.countTopPieces(p)));

			int i=0;
			for(int row=0; row<n; row++){
				for(int col=0; col<n; col++){
					board.place(pieces[i],new Point(col,row));
					i = (i+1) % pieces.length;
				}
			}
			final int loadLimit = board.getLoadLimit();
			final int boardSize = board.getSize();

			board.reset();
			assertEquals(loadLimit,board.getLoadLimit(),"getLoadLimit");
			assertEquals(boardSize,board.getSize(),"getSize");
			assertEquals(size.countInitialCapstones(),board.countInitialCapstones(),"countInitialCapstones");
			assertEquals(size.countInitialStones(),board.countInitialStones(),"countInitialStones");
			assertEquals(n*n,board.countEmpty(),"countEmpty");
			for(int row=0; row<n; row++){
				for(int col=0; col<n; col++){
					assertTrue(board.isFree(row,col),"isFree("+row+","+col+")");
				}
			}

			for(Piece p: pieces){
				assertEquals(counters.get(p).intValue(), board.countTopPieces(p), "countPieces("+p.toString()+")");
			}
		}
	}

	@Test
	void inBounds(){
		for(Board.Size size:Board.Size.values()){
			final Board board = new Board(size);
			final int n = board.getSize();
			int row, col;

			for(row = 0; row < n ; row++){
				for(col = 0; col < n; col++){
					assertTrue(board.inBounds(new Point(col, row)), "InBounds (row " + row + " col " + col + ") for board " + size.toString());
				}
			}
			for(row = 0; row < n ; row++){
				col = -1;
				assertFalse(board.inBounds(new Point(col,row)),"InBounds (row "+row+" col "+col+") for board "+size.toString());
				col = n;
				assertFalse(board.inBounds(new Point(col,row)),"InBounds (row "+row+" col "+col+") for board "+size.toString());
			}
			for(col = 0; col < n; col++){
				row = -1;
				assertFalse(board.inBounds(new Point(col,row)),"InBounds (row "+row+" col "+col+") for board "+size.toString());
				row = n;
				assertFalse(board.inBounds(new Point(col,row)),"InBounds (row "+row+" col "+col+") for board "+size.toString());
			}
		}
	}

	@Test
	void isFree(){
		Piece[] pieces = Piece.values();
		int[] occurences = new int[pieces.length];
		for(int i = 0; i < occurences.length; i++){
			occurences[i] = i;
		}
		final int mid = pieces.length >> 1;
		for(Board.Size size : Board.Size.values()){
			final Board board = new Board(size);
			int i;
			int r = 0, c = 0;
			for(i = 0; i < mid; i++){
				for(int j = 0; j < occurences[i]; j++){
					board.place(pieces[i], new Point(c, r));
					c++;
					if(c >= board.getSize()){
						c = 0;
						r++;
					}
				}
			}
			for(; i < pieces.length; i++){
				Stack<Piece> stack = new Stack<Piece>();
				for(int j = 0; j < occurences[i]; j++){
					stack.push(pieces[i]);
				}
				placeStack(board, new Point(c, r), stack);
				c++;
				if(c >= board.getSize()){
					c = 0;
					r++;
				}
			}
			for(int col = c; col < board.getSize(); col++)
				assertTrue(board.isFree(r, col), "Row " + r + " must be free from col " + c);
			for(int row = r + 1; row < board.getSize(); row++){
				for(int col = 0; col < board.getSize(); col++)
					assertTrue(board.isFree(row, col), "All places must be free after pieces, row " + r + " col " + c);
			}
			c--;
			if(c < 0){
				r--;
				c = 0;
			}
			while(r >= 0 && c >= 0){
				assertFalse(board.isFree(r, c), "There are some piece at row " + r + " col " + c);
				c--;
				if(c < 0){
					r--;
					c = 0;
				}
			}
		}
	}

	@Test
	void isUnderControl(){
		Piece[] pieces = Piece.values();
		int[] occurences = new int[pieces.length];
		for(int i = 0; i < occurences.length; i++){
			occurences[i] = i;
		}
		final int mid = pieces.length >> 1;
		for(Board.Size size : Board.Size.values()){
			final Board board = new Board(size);
			int i;
			int r = 0, c = 0;
			for(i = 0; i < mid; i++){
				for(int j = 0; j < occurences[i]; j++){
					board.place(pieces[i], new Point(c, r));
					c++;
					if(c >= board.getSize()){
						c = 0;
						r++;
					}
				}
			}
			for(; i < pieces.length; i++){
				Stack<Piece> stack = new Stack<Piece>();
				for(int j = 0; j < occurences[i]; j++){
					stack.push(pieces[i]);
				}
				placeStack(board, new Point(c, r), stack);
				c++;
				if(c >= board.getSize()){
					c = 0;
					r++;
				}
			}

			for(int row=0; row < board.getSize(); row++){
				for(int col=0; col < board.getSize(); col++){
					if(board.isFree(row,col)){
						assertFalse(board.isUnderControl(Constants.BLACK_PLAYER,row,col),"Position is free, black cannot control it");
						assertFalse(board.isUnderControl(Constants.WHITE_PLAYER,row,col),"Position is free, white cannot control it");
					}else{
						Piece top = board.getTop(row,col);
						assertTrue(board.isUnderControl(top.isBlack()?Constants.BLACK_PLAYER:Constants.WHITE_PLAYER,row,col),"Position must be under the control of the player");
						assertFalse(board.isUnderControl(top.isWhite()?Constants.BLACK_PLAYER:Constants.WHITE_PLAYER,row,col),"Position cannot be under the control of the player");
					}
				}
			}
		}
	}

	@Test
	void canPlace(){
		final Board board = new Board();
		final Point position = new Point(0,0);

		for(Piece piece:Piece.values()){
			assertTrue(board.canPlace(piece, position), piece.toString()+" at a free position");
		}

		for(Piece busy:Piece.values()){
			position.translate(1,0);
			board.place(busy, position);
			for(Piece piece : Piece.values()){
				assertFalse(board.canPlace(piece, position), piece.toString() + " at a busy position ("+(busy.toString())+")");
			}
		}
	}

	@Test
	void isCompleted(){
		for(Board.Size size:Board.Size.values()){
			final Board board = new Board(size);
			final int n = board.getSize();
			int row,col;
			assertFalse(board.isCompleted(),"Empty board of size "+size.toString());
			for(row=0; row < n-1; row++){
				for(col=0; col < n; col++){
					board.place(Piece.DOLMEN_BLACK,new Point(col,row));
					assertFalse(board.isCompleted(), (row*n+col+1)+" in board of size "+size.toString());
				}
			}
			for(col=0; col < n-1; col++){
				board.place(Piece.DOLMEN_BLACK,new Point(col,row));
				assertFalse(board.isCompleted(), (row*n+col+1)+" in board of size "+size.toString());
			}
			Board cpy;
			for(Piece piece: Piece.values()){
				cpy = board.clone();
				cpy.place(Piece.DOLMEN_BLACK, new Point(col, row));
				assertTrue(cpy.isCompleted(), (row * n + col + 1) + " in board of size " + size.toString());
			}
		}
	}

	@Test
	void countEmpty(){
		for(Board.Size size:Board.Size.values()){
			final Board board = new Board(size);
			final int n = board.getSize();
			int row,col;
			int empty = n*n;
			assertEquals(empty,board.countEmpty(),"Empty board of size "+size.toString());
			for(row=0; row < n-1; row++){
				for(col=0; col < n; col++){
					board.place(Piece.DOLMEN_BLACK,new Point(col,row));
					empty--;
					assertEquals(empty,board.countEmpty(), (row*n+col+1)+" in board of size "+size.toString());
				}
			}
			for(col=0; col < n-1; col++){
				board.place(Piece.DOLMEN_BLACK,new Point(col,row));
				empty--;
				assertEquals(empty,board.countEmpty(), (row*n+col+1)+" in board of size "+size.toString());
			}
			Board cpy;
			for(Piece piece: Piece.values()){
				cpy = board.clone();
				cpy.place(Piece.DOLMEN_BLACK, new Point(col, row));
				assertEquals(0,cpy.countEmpty(), (row * n + col + 1) + " in board of size " + size.toString());
			}
		}

		Board board = new Board();
		Stack stack = new Stack();
		for(int i=0; i<board.getSize()*board.getSize(); i++){
			stack.push(Piece.DOLMEN_BLACK);
		}
		placeStack(board,new Point(0,0),stack);
		assertEquals(board.getSize()*board.getSize()-1,board.countEmpty(), "Stack, must have " + (board.getSize()*board.getSize()-1)+" free positions");

		board.move(new Point(0,0),new int[]{1},new Point(1,0));
		assertEquals(board.getSize()*board.getSize()-2,board.countEmpty(), "Stack, move 1, must have " + (board.getSize()*board.getSize()-2)+" free positions");
	}

	@Test
	void place(){
		// TODO
	}

	@Test
	void getNeighbors(){
		for(Board.Size size: Board.Size.values()){
			final Board board = new Board(size);
			final int n = board.getSize();
			for(int row=0; row<n; row++){
				for(int col=0; col<n; col++){
					List<Point> expected = new LinkedList<Point>();
					expected.add(new Point(col, row+1));
					expected.add(new Point(col, row-1));
					expected.add(new Point(col+1,row));
					expected.add(new Point(col-1,row));

					for(int i=expected.size()-1; i >=0; i--){
						if(!board.inBounds(expected.get(i)))
							expected.remove(i);
					}

					List<Point> actual = board.getNeighbors(new Point(col,row));

					expected.sort(this);
					actual.sort(this);

					Point[] expectedArray = new Point[expected.size()];
					Point[] actualArray = new Point[actual.size()];
					expectedArray = expected.toArray(expectedArray);
					actualArray = actual.toArray(actualArray);

					assertArrayEquals(expectedArray,actualArray,"Neighbors at row "+row+" col "+col+" for Board "+size.toString());
				}
			}
		}
	}

	@Test
	void pathExists(){
		placeNotPath();
		simpleStraightPaths();
		simpleLShapePaths();
		simpleOShapePaths();
		simpleDiagonalPath();
		simpleStackPath();
		simplePathAndStackedPath("CAPSTONE",true);
		simplePathAndStackedPath("MENHIR",false);
		multiColorPath();
		pathWithMoves();
		doublePath();
	}

	private void placeNotPath(){
		for(Board.Size size: Board.Size.values()){
			Board board = new Board(size);
			Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
			final int mid = board.getSize() >> 1;
			final int end = board.getSize() - 1;

			for(Piece player : players){
				board.reset();
				pathPlace(board, players, player, 0, 0, "place at 0,0");

				board.reset();
				pathPlace(board, players, player, mid, mid, "place at mid,mid");

				board.reset();
				pathPlace(board, players, player, end, end, "place at end,end");

				board.reset();
				pathPlace(board, players, player, 0, end, "place at 0,end");
			}
		}
	}

	private void pathWithMoves(){
		Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
		for(Board.Size size: Board.Size.values()){
			if(size.length >= 5){// TODO check
				for(int playerID = 0; playerID < players.length; playerID++){
					final Piece player = players[playerID];
					final Piece opponent = players[(playerID + 1) % players.length];
					final Board board = new Board(size);
					final int n = board.getSize();
					final int mid = n >> 1;
					final int[] positions = {0,mid,n-1};
					for(int col = 1; col < n - 1; col++){
						if(col != mid)
							pathPlace(board, players, player, mid, col, "pathWithMoves prepare at row " + mid + " col " + col);
					}

					Board cpy;
					List<Piece> allPlayerPieces = new LinkedList<Piece>(Arrays.asList(Piece.values()));
					for(int j = allPlayerPieces.size() - 1; j >= 0; j--){
						if(!allPlayerPieces.get(j).color.equals(player.color)){
							allPlayerPieces.remove(j);
						}
					}
					final int[] amount = {board.getLoadLimit() >> 1};
					for(Piece p : allPlayerPieces){
						for(int col : new int[]{0, mid, n - 1}){
							final Point src = new Point(col, mid + 1);
							final Point dst = new Point(col, mid );
							Map<Point, Color> pathExists = new HashMap<Point, Color>();
							pathExists.put(dst, player.color);
							cpy = board.clone();
							for(int otherCol:positions){
								if(col != otherCol){
									cpy.place(player,new Point(otherCol,mid));
								}
							}
							// Prepare a stack with load-1 adversarial pieces
							Stack<Piece> stack = new Stack<Piece>();
							for(int load = 0; load < board.getLoadLimit() - 1; load++){
								stack.push(opponent);
							}
							stack.push(p);
							placeStack(cpy, new Point(col, mid + 1), stack);
							pathPlace(cpy, players, null, mid + 1, col, "pathWithMoves prepare at row " + mid + " col " + col);
							//pathPlace(cpy, players, p, mid + 1, col, "pathWithMoves prepare at row " + mid + " col " + col);
							pathMove(cpy, players, src, dst, amount, "pathWithMoves", cpy.getTop(src).isMenhir()?null:pathExists);
						}
					}
				}
			}
		}
	}

	private void doublePath(){
		Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
		final int[] amount = {1,1}; // Always stack of size 2, 1 piece

		for(Board.Size size: Board.Size.values()){
			if(size.length >= 5){
				for(int playerID = 0; playerID < players.length; playerID++){
					final Piece player = players[playerID];
					final Piece opponent = players[(playerID + 1) % players.length];
					final Board board = new Board(size);
					final Board adversarial = new Board(size);
					final int n = board.getSize();
					final int mid = n >> 1;
					final Map<Point, Color> pathMustExist = new HashMap<Point, Color>();
					Stack<Piece> stack = new Stack<Piece>();
					final int[] positions = {0,mid,n-1};
					Board cpy;

					// BEGIN 2 x left to right (row mid and mid+1), need to complete at col 0, mid, and n-1
					board.reset();
					adversarial.reset();
					for(int col = 1; col < n - 1; col++){
						if(col != mid){
							pathPlace(board, players, player, mid, col, "multiColorPath prepare at row " + mid + " col " + col);
							pathPlace(board, players, player, mid + 1, col, "multiColorPath prepare at row " + (mid + 1) + " col " + col);

							pathPlace(adversarial, players,player , mid, col, "multiColorPath prepare at row " + mid + " col " + col);
							pathPlace(adversarial, players,opponent , mid + 1, col, "multiColorPath prepare at row " + (mid + 1) + " col " + col);
						}
					}
					// create a stack at row=mid-1 and col in {0, mid-1, n-1}
					for(int col : positions){
						stack.clear();
						stack.push(player);
						stack.push(player);
						placeStack(board, new Point(col, mid - 1), stack);
						pathPlace(board, players, null, mid - 1, col, "multiColorPath prepare at row " + (mid - 1) + " col " + col);

						stack.clear();
						stack.push(player);
						stack.push(opponent);
						placeStack(adversarial, new Point(col, mid - 1), stack);
						pathPlace(adversarial, players, null, mid - 1, col, "multiColorPath prepare at row " + (mid - 1) + " col " + col);
					}
					for(int col : positions){
						pathMustExist.clear();
						cpy = board.clone();
						for(int otherCol:positions){
							if(otherCol != col){
								pathPlace(cpy, players, player, mid, otherCol, "multiColorPath rows");
								pathPlace(cpy, players, player, mid + 1, otherCol, "multiColorPath rows");
							}
						}
						pathMustExist.put(new Point(col, mid), player.color);
						pathMustExist.put(new Point(col, mid + 1), player.color);
						pathMove(cpy, players, new Point(col, mid - 1), new Point(col, mid + 1), amount, "multiColorPath left to right", pathMustExist);
						pathMustExist.clear();
						cpy = adversarial.clone();
						for(int otherCol:positions){
							if(otherCol != col){
								pathPlace(cpy, players, player, mid, otherCol, "multiColorPath rows");
								pathPlace(cpy, players, opponent, mid + 1, otherCol, "multiColorPath rows");
							}
						}
						pathMustExist.put(new Point(col, mid), player.color);
						pathMustExist.put(new Point(col, mid + 1), opponent.color);
						pathMove(cpy, players, new Point(col, mid - 1), new Point(col, mid + 1), amount, "multiColorPath left to right", pathMustExist);
					}
					// END

					// BEGIN 2 x up to down (col mid and mid+1), need to complete at row 0, mid, and n-1
					board.reset();
					adversarial.reset();
					for(int row = 1; row < n - 1; row++){
						if(row != mid){
							pathPlace(board, players, player, row, mid, "multiColorPath prepare at row " + row + " col " + mid);
							pathPlace(board, players, player, row, mid + 1, "multiColorPath prepare at row " + row + " col " + (mid + 1));

							pathPlace(adversarial, players, player, row, mid, "multiColorPath prepare at row " + row + " col " + mid);
							pathPlace(adversarial, players, opponent, row, mid + 1, "multiColorPath prepare at row " + row + " col " + (mid + 1));
						}
					}
					// create a stack at col=mid-1 and row in {0, mid-1, n-1}
					for(int row : positions){
						stack.clear();
						stack.push(player);
						stack.push(player);
						placeStack(board, new Point(mid - 1, row), stack);
						pathPlace(board, players, null, row, mid - 1, "multiColorPath prepare at row " + row + " col " + (mid - 1));

						stack.clear();
						stack.push(player);
						stack.push(opponent);
						placeStack(adversarial, new Point(mid - 1, row), stack);
						pathPlace(adversarial, players, null, row, mid - 1, "multiColorPath prepare at row " + row + " col " + (mid - 1));
					}
					for(int row : positions){
						pathMustExist.clear();
						cpy = board.clone();
						for(int otherRow:positions){
							if(otherRow != row){
								pathPlace(cpy, players, player, otherRow,mid, "multiColorPath cols");
								pathPlace(cpy, players, player, otherRow,mid + 1, "multiColorPath cols");
							}
						}
						pathMustExist.put(new Point(mid, row), player.color);
						pathMustExist.put(new Point(mid + 1, row), player.color);
						pathMove(cpy, players, new Point(mid - 1, row), new Point(mid + 1, row), amount, "multiColorPath bottom to up", pathMustExist);
						pathMustExist.clear();
						cpy = adversarial.clone();
						for(int otherRow:positions){
							if(otherRow != row){
								pathPlace(cpy, players, player, otherRow,mid, "multiColorPath cols");
								pathPlace(cpy, players, opponent, otherRow,mid + 1, "multiColorPath cols");
							}
						}
						pathMustExist.put(new Point(mid, row), player.color);
						pathMustExist.put(new Point(mid + 1, row), opponent.color);
						pathMove(cpy, players, new Point(mid - 1, row), new Point(mid + 1, row), amount, "multiColorPath bottom to up", pathMustExist);
					}
					// END

					// BEGIN cross, horizontal view for player (and opponent is vertical)
					// note that the vertical view is the horizontal for the next player
					// Need to complete the path at each corner (horizontal = player, vertical = opponent)
					//  and at middle positions (row 0 col mid, row mid, col mod, row n-1 col mid)
					board.reset();
					adversarial.reset();
					for(int pos = 1; pos < n - 1; pos++){
						if(pos != mid){
							pathPlace(adversarial, players, opponent, pos, 0, "multiColorPath prepare at row " + pos + " col " + 0);
							pathPlace(adversarial, players, player, 0, pos, "multiColorPath prepare at row " + 0 + " col " + pos);
							pathPlace(adversarial, players, opponent, pos, mid, "multiColorPath prepare at row " + pos + " col " + mid);
							pathPlace(adversarial, players, player, mid, pos, "multiColorPath prepare at row " + mid + " col " + pos);
							pathPlace(adversarial, players, opponent, pos, n - 1, "multiColorPath prepare at row " + pos + " col " + (n - 1));
							pathPlace(adversarial, players, player, n - 1, pos, "multiColorPath prepare at row " + (n - 1) + " col " + pos);

							pathPlace(board, players, player, pos, 0, "multiColorPath prepare at row " + pos + " col " + 0);
							pathPlace(board, players, player, 0, pos, "multiColorPath prepare at row " + 0 + " col " + pos);
							pathPlace(board, players, player, pos, mid, "multiColorPath prepare at row " + pos + " col " + mid);
							pathPlace(board, players, player, mid, pos, "multiColorPath prepare at row " + mid + " col " + pos);
							pathPlace(board, players, player, pos, n - 1, "multiColorPath prepare at row " + pos + " col " + (n - 1));
							pathPlace(board, players, player, n - 1, pos, "multiColorPath prepare at row " + (n - 1) + " col " + pos);
						}
					}
					// TODO stack, duplicate, move, check at each corner (combo colors), and mid positions
					// END
				}
			}
		}
	}

	private void multiColorPath(){
		Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
		for(Board.Size size: Board.Size.values()){
			for(Piece player: players){
				final Board board = new Board(size);
				final int n = board.getSize();
				final int mid = n>>1;
				for(int col=1; col<n-1; col++){
					if(col != mid)
						pathPlace(board, players, player, mid, col, "multiColorPath prepare at row "+mid+" col "+col);
				}
				List<Piece> opponents = new LinkedList<Piece>(Arrays.asList(Piece.values()));
				for(int j=opponents.size()-1; j>=0; j--){
					if(opponents.get(j).color.equals(player.color)){
						opponents.remove(j);
					}
				}
				Board cpy, stacked;
				Stack<Piece> stack = new Stack<Piece>();
				final int[] positions = {0, mid, n-1};
				for(Piece opponent: opponents){
					for(int i=0; i<positions.length; i++){
						final int oPos = positions[i];
						final int[] otherPos = new int[positions.length-1];
						for(int j=0,k=0; j<positions.length; j++){
							if(j != i){
								otherPos[k] = positions[j];
								k++;
							}
						}

						// BEGIN start with opponent at oPos
						cpy	= board.clone();
						stacked	= cpy.clone();
						pathPlace(cpy, players, opponent, mid, oPos, "multiColorPath, start opponent");
						stack.clear();
						stack.push(player);
						stack.push(opponent);
						placeStack(stacked,new Point(oPos,mid),stack);
						pathPlace(stacked, players, null, mid, oPos, "multiColorPath, start opponent (stack)");
						for(int j=1; j<otherPos.length; j++){
							pathPlace(cpy, players, player, mid, otherPos[j], "multiColorPath, start opponent");
							pathPlace(stacked, players, player, mid, otherPos[j], "multiColorPath, start opponent (stack)");
						}
						pathPlace(cpy, players, player, mid, otherPos[0], "multiColorPath, start opponent");
						stack.clear();
						stack.push(opponent);
						stack.push(player);
						placeStack(stacked,new Point(otherPos[0],mid),stack);
						pathPlace(stacked, players, null, mid, otherPos[0], "multiColorPath, start opponent (stack)");
						// END

						// BEGIN mid with opponent at oPos
						cpy	= board.clone();
						for(int j=1; j<otherPos.length; j++){
							pathPlace(cpy, players, player, mid, otherPos[j], "multiColorPath, start opponent");
						}
						stacked	= cpy.clone();
						pathPlace(cpy, players, opponent, mid, oPos, "multiColorPath, start opponent");
						stack.clear();
						stack.push(player);
						stack.push(opponent);
						placeStack(stacked,new Point(oPos,mid),stack);
						pathPlace(stacked, players, null, mid, oPos, "multiColorPath, start opponent (stack)");
						//
						pathPlace(cpy, players, player, mid, otherPos[0], "multiColorPath, start opponent");
						stack.clear();
						stack.push(opponent);
						stack.push(player);
						placeStack(stacked,new Point(otherPos[0],mid),stack);
						pathPlace(stacked, players, null, mid, otherPos[0], "multiColorPath, start opponent (stack)");
						// END

						// BEGIN end with opponent at oPos
						cpy	= board.clone();
						for(int j=1; j<otherPos.length; j++){
							pathPlace(cpy, players, player, mid, otherPos[j], "multiColorPath, start opponent");
						}
						stacked	= cpy.clone();
						pathPlace(cpy, players, player, mid, otherPos[0], "multiColorPath, start opponent");
						stack.clear();
						stack.push(opponent);
						stack.push(player);
						placeStack(stacked,new Point(otherPos[0],mid),stack);
						pathPlace(stacked, players, null, mid, otherPos[0], "multiColorPath, start opponent (stack)");
						//
						pathPlace(cpy, players, opponent, mid, oPos, "multiColorPath, start opponent");
						// Cannot otherwise path is completed: pathUtil(stack, players, player, mid, oPos, "multiColorPath, start opponent (stack)");
						pathPlace(stacked, players, opponent, mid, oPos, "multiColorPath, start opponent (stack)");
						// END
					}
				}
			}
		}
	}

	private void simplePathAndStackedPath(String piecePefix, boolean expectedResult){
		Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
		for(Board.Size size: Board.Size.values()){
			final Board board = new Board(size);
			final int n = board.getSize();
			final int mid = n>>1;
			Board cpy;
			for(Piece player : players){
				final Piece last = Piece.valueOf(piecePefix+ (player.isBlack() ? "_BLACK" : "_WHITE"));
				board.reset();
				for(int col = 1; col < n-1; col++){
					if(col != mid)
						pathPlace(board, players, player, mid, col, "capstoneSimpleAndStackPath at row "+mid+" col "+col);
				}

				// finish at start
				cpy = board.clone();
				pathPlace(cpy, players, player, mid, mid, "capstoneSimpleAndStackPath at start");
				pathPlace(cpy, players, player, mid, n-1, "capstoneSimpleAndStackPath at start");
				pathPlace(cpy, players, last, mid, 0, "capstoneSimpleAndStackPath at start",expectedResult);

				// finish at middle
				cpy = board.clone();
				pathPlace(cpy, players, player, mid, 0, "capstoneSimpleAndStackPath at middle");
				pathPlace(cpy, players, player, mid, n-1, "capstoneSimpleAndStackPath at middle");
				pathPlace(cpy, players, last, mid, mid, "capstoneSimpleAndStackPath at middle",expectedResult);

				// finish at end
				cpy = board.clone();
				pathPlace(cpy, players, player, mid, mid, "capstoneSimpleAndStackPath at end");
				pathPlace(cpy, players, player, mid, 0, "capstoneSimpleAndStackPath at end");
				pathPlace(cpy, players, last, mid, n-1, "capstoneSimpleAndStackPath at end",expectedResult);

				final Piece opponent = player.isBlack() ? Piece.DOLMEN_WHITE : Piece.DOLMEN_BLACK;
				Stack<Piece> stack = new Stack<Piece>();

				// stack finish at start
				cpy = board.clone();
				stack.clear();
				stack.push(opponent);
				stack.push(player);
				placeStack(cpy,new Point(mid,mid),stack);
				pathPlace(cpy, players, null, mid, mid, "capstoneSimpleAndStackPath at start");
				pathPlace(cpy, players, player, mid, n - 1, "capstoneSimpleAndStackPath at start");
				pathPlace(cpy, players, last, mid, 0, "capstoneSimpleAndStackPath at start", expectedResult);

				// stack finish at middle
				cpy = board.clone();
				stack.clear();
				stack.push(opponent);
				stack.push(player);
				placeStack(cpy,new Point(0,mid),stack);
				pathPlace(cpy, players, null, mid, 0, "capstoneSimpleAndStackPath at middle");
				pathPlace(cpy, players, player, mid, n - 1, "capstoneSimpleAndStackPath at middle");
				pathPlace(cpy, players, last, mid, mid, "capstoneSimpleAndStackPath at middle", expectedResult);

				// stack finish at end
				cpy = board.clone();
				stack.clear();
				stack.push(opponent);
				stack.push(player);
				placeStack(cpy,new Point(mid,mid),stack);
				pathPlace(cpy, players, null, mid, mid, "capstoneSimpleAndStackPath at end");
				pathPlace(cpy, players, player, mid, 0, "capstoneSimpleAndStackPath at end");
				pathPlace(cpy, players, last, mid, n - 1, "capstoneSimpleAndStackPath at end", expectedResult);
			}
		}
	}

	private void simpleStackPath(){
		for(Board.Size size : Board.Size.values()){
			Board board = new Board(size);
			final int n = board.getSize();
			final int row = n>>1;
			Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
			for(Piece player : players){
				board.reset();
				Stack<Piece> stack = new Stack<Piece>();
				stack.push(player);
				stack.push(player);
				placeStack(board,new Point(1,row),stack);
				pathPlace(board, players, null, row, 1, "Stack");
				for(int i = 2; i < n; i++)
					pathPlace(board, players, player, row, i, "Stack");
				pathPlace(board, players, player, row, 0, "Stack", true);
			}
		}
	}
	private void simpleDiagonalPath(){
		for(Board.Size size: Board.Size.values()){
			Board board = new Board(size);
			final int n = board.getSize();
			Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
			for(Piece player: players){
				// Backslash
				board.reset();
				for(int i=1; i < n; i++)
					pathPlace(board,players,player,i,i,"Diagonal backslash");
				pathPlace(board,players,player,0,0,"Diagonal backslash");
				// Slash
				board.reset();
				for(int i=1; i < n; i++)
					pathPlace(board,players,player,i,n-i-1,"Diagonal slash");
				pathPlace(board,players,player,0,n-1,"Diagonal slash");
			}
		}
	}

	private void simpleLShapePaths(){
		for(Board.Size size: Board.Size.values()){
			Board board = new Board(size);
			Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
			for(Piece player: players){
				// Horizontal
				board.reset();
				for(int col=1; col < board.getSize(); col++)
					pathPlace(board,players,player,0,col,"LShape");
				pathPlace(board,players,player,1,0,"LShape");
				pathPlace(board,players,player,1,1,"LShape",true);
				// Vertical
				board.reset();
				for(int row=1; row < board.getSize(); row++)
					pathPlace(board,players,player,row,0,"LShape");
				pathPlace(board,players,player,0,1,"LShape");
				pathPlace(board,players,player,1,1,"LShape",true);
			}
		}
	}

	private void simpleOShapePaths(){
		for(Board.Size size: Board.Size.values()){
			Board board = new Board(size);
			final int n = board.getSize();
			final int mid = n >> 1;
			Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
			for(Piece player: players){
				if(n >= 5){
					// Center O shape
					board.reset();
					for(int col=mid-1; col<=mid+1; col++){
						pathPlace(board, players, player, mid-1, col, "OShape");
						pathPlace(board, players, player, mid+1, col, "OShape");
					}
					for(int col=1; col<=mid-1; col++){
						pathPlace(board, players, player, mid, col, "OShape");
					}
					for(int col=mid+1; col<n; col++){
						pathPlace(board, players, player, mid, col, "OShape");
					}
					pathPlace(board, players, player, mid, 0, "OShape", true);
				}
				// Border O shape
				board.reset();
				for(int i=1; i<n-1; i++){
					pathPlace(board, players, player, 0, i, "OShape");
					pathPlace(board, players, player, n-1, i, "OShape");
					pathPlace(board, players, player, i, 0, "OShape");
					pathPlace(board, players, player, i, n-1, "OShape");
				}
				pathPlace(board, players, player, 0, 0, "OShape",false);
				pathPlace(board, players, player, n-1, n-1, "OShape",false);
				pathPlace(board, players, player, 0, n-1, "OShape",true);
				pathPlace(board, players, player, n-1, 0, "OShape",true);
			}
		}
	}

	private void simpleStraightPaths(){
		for(Board.Size size: Board.Size.values()){
			Board board = new Board(size);
			Piece[] players = new Piece[]{Piece.DOLMEN_WHITE,Piece.DOLMEN_BLACK};
			Board cpy;
			Point p;

			final int mid = board.getSize()>>1;
			for(Piece player: players){
				for(int j:new int[]{0, mid,board.getSize()-1}){
					// Horizontal at row j
					board.reset();
					for(int i = 1; i < board.getSize() - 1; i++){
						if(i != mid) pathPlace(board,players,player,j,i,"horizontal");
					}

					// Win at Last position
					cpy = board.clone();
					pathPlace(cpy,players,player,j,mid,"horizontal");
					pathPlace(cpy,players,player,j,0,"horizontal");
					pathPlace(cpy,players,player,j,board.getSize()-1,"horizontal",true);

					// Win at First position
					cpy = board.clone();
					pathPlace(cpy,players,player,j,mid,"horizontal");
					pathPlace(cpy,players,player,j,board.getSize()-1,"horizontal");
					pathPlace(cpy,players,player,j,0,"horizontal",true);

					// Win at mid position
					cpy = board.clone();
					pathPlace(cpy,players,player,j,0,"horizontal");
					pathPlace(cpy,players,player,j,board.getSize()-1,"horizontal");
					pathPlace(cpy,players,player,j,mid,"horizontal",true);

					// Vertical at row j
					board.reset();
					for(int i = 1; i < board.getSize() - 1; i++){
						if(i != mid) pathPlace(board,players,player,i,j,"vertical");
					}

					// Win at Last position
					cpy = board.clone();
					pathPlace(cpy,players,player,mid,j,"vertical");
					pathPlace(cpy,players,player,0,j,"vertical");
					pathPlace(cpy,players,player,board.getSize()-1,j,"vertical",true);

					// Win at First position
					cpy = board.clone();
					pathPlace(cpy,players,player,mid,j,"vertical");
					pathPlace(cpy,players,player,board.getSize()-1,j,"vertical");
					pathPlace(cpy,players,player,0,j,"vertical",true);

					// Win at mid position
					cpy = board.clone();
					pathPlace(cpy,players,player,0,j,"vertical");
					pathPlace(cpy,players,player,board.getSize()-1,j,"vertical");
					pathPlace(cpy,players,player,mid,j,"vertical",true);

				}
			}
		}
	}

	private static final int signum(int value){
		if(value < 0) return -1;
		return value > 0 ? 1 : 0;
	}
	private static List<Point> getPathMove(final Point src,final Point dst){
		if(src.x != dst.x && src.y != dst.y) return null;
		if(src.x == dst.x && src.y == dst.y) return null;
		final int dx = signum(dst.x - src.x);
		final int dy = signum(dst.y - src.y);
		List<Point> path = new LinkedList<Point>();
		path.addLast(src);
		while(!path.getLast().equals(dst)){
			Point p = path.getLast();
			path.addLast(new Point(p.x+dx,p.y+dy));
		}
		return path;
	}
	private void pathMove(Board board, Piece[] players, Point src, Point dst, int[] amount, String mode, Map<Point,Color> pathExists){
		final List<Point> path = getPathMove(src,dst);

		if(pathExists == null) pathExists = new HashMap<Point,Color>(0);
		if(path == null) fail("Bad parameters in pathMove(), src="+src.toString()+" and dst="+dst.toString()+" create a null path in getPathMove(src,dst)");
		board.move(src,amount,dst);

		for(Point pos:pathExists.keySet()){
			if(!path.contains(pos)){
				fail("Bad parameters in pathMove(), "+pos.toString()+" not in computed path getPathMove(src,dst)");
			}
		}

		for(Point pos:path){
			if(!pathExists.containsKey(pos)){
				for(Piece player: players){
					assertFalse(board.pathExists(pos, player.color), "Player " + player.color + " for "+mode+" at row " + pos.y + " col " + pos.x);
				}
			}else{
				final Color winner = pathExists.get(pos);
				if(winner == null) fail("Bad parameters in pathMove(), winner is null at "+pos.toString());
				for(Piece player: players){
					if(player.color.equals(winner)){
						assertTrue(board.pathExists(pos, player.color), "Player " + player.color + " for "+mode+" at row " + pos.y + " col " + pos.x);
					}else{
						assertFalse(board.pathExists(pos, player.color), "Player " + player.color + " for "+mode+" at row " + pos.y + " col " + pos.x);
					}
				}
			}
		}
	}

	private static void pathPlace(Board board, Piece[] players, Piece piece, int row, int col, String mode){
		pathPlace(board,players,piece,row,col,mode, false);
	}
	private static void pathPlace(Board board, Piece[] players, Piece piece, int row, int col, String mode, boolean pathExists){
		final Point p = new Point(col, row);
		if(piece != null){
			if(!board.isFree(p)) fail("Cannot stack pieces");
			board.place(piece, p);
		}
		if(pathExists){
			for(Piece player: players){
				if(piece.color.equals(player.color)){
					assertTrue(board.pathExists(p, player.color), "Player " + player.color + " for "+mode+" at row " + row + " col " + col);
				}else{
					assertFalse(board.pathExists(p, player.color), "Player " + player.color + " for "+mode+" at row " + row + " col " + col);
				}
			}
			return;
		}
		for(Piece player: players)
			assertFalse(board.pathExists(p, player.color), "Player "+player.color+" for "+mode+" at row " + row+" col "+col);
	}

	private static void placeStack(Board board, Point pos, Stack<Piece> stack){
		if(!board.isFree(pos)) fail("Cannot create a stack for testing, pos"+pos.toString()+" is not free");
		Piece[] pieces = new Piece[stack.size()];
		for(int i=0; i<pieces.length; i++){
			pieces[i] = stack.pop();
		}
		for(int i=pieces.length-1; i>=0; i--){
			board.place(pieces[i],pos);
		}
	}

	@Test
	void canMove(){
		Stack<Piece> stack = new Stack<Piece>();

		Piece[] players = new Piece[]{Piece.DOLMEN_WHITE, Piece.DOLMEN_BLACK};
		final Point src = new Point(0,0);

		for(Board.Size size: Board.Size.values()){
			for(int playerID = 0; playerID < players.length; playerID++){
				final Piece player = players[playerID];
				final Piece opponent = players[(playerID + 1) % players.length];
				final Board board = new Board(size);
				final List<Piece> allPlayerPieces = new LinkedList<Piece>(Arrays.asList(Piece.values()));
				for(int j=allPlayerPieces.size()-1; j>=0; j--){
					if(! allPlayerPieces.get(j).color.equals(player.color)){
						allPlayerPieces.remove(j);
					}
				}

				stack.clear();
				stack.push(player);
				board.reset();
				placeStack(board,src,stack);
				assertTrue(board.canMove(player.color,src,new int[]{1},new Point(0,1)),"Valid move of length 1");

				stack.clear();
				stack.push(player);
				stack.push(player);
				board.reset();
				placeStack(board,src,stack);
				assertTrue(board.canMove(player.color,src,new int[]{1},new Point(0,1)),"Valid move of length 2, move 1");
				assertTrue(board.canMove(player.color,src,new int[]{2},new Point(0,1)),"Valid move of length 2, move 2");

				assertFalse(board.canMove(player.color,src,new int[]{},new Point(0,1)),"Invalid move of length 0");
				assertFalse(board.canMove(player.color,src,new int[]{2},new Point(1,1)),"Invalid move of length 2");

				board.reset();
				stack.clear();
				stack.push(player);
				stack.push(player);
				stack.push(player);
				placeStack(board,src,stack);
				assertFalse(board.canMove(player.color,src,new int[]{0,1},new Point(2,0)),"Invalid amount in move of length 2");
				assertFalse(board.canMove(player.color,src,new int[]{1,0},new Point(2,0)),"Invalid amount in move of length 2");
				if(board.getSize() > 3){
					assertFalse(board.canMove(player.color,src,new int[]{1,0,1},new Point(3,0)),"Invalid amount in move of length 2");
					assertFalse(board.canMove(player.color,src,new int[]{1,0,1},new Point(3,0)),"Invalid amount in move of length 2");
				}

				for(Piece p:allPlayerPieces){
					board.reset();
					stack.clear();
					stack.push(player);
					stack.push(player);
					stack.push(p);
					placeStack(board,src,stack);
					assertTrue(board.canMove(player.color,src,new int[]{1,1},new Point(2,0)),"Piece "+p+" with amount {1,1}");
					assertTrue(board.canMove(player.color,src,new int[]{1,2},new Point(2,0)),"Piece "+p+" with amount {1,1}");
					assertTrue(board.canMove(player.color,src,new int[]{2,1},new Point(2,0)),"Piece "+p+" with amount {1,1}");
				}

				board.reset();
				stack.clear();
				for(int load=0; load<board.getLoadLimit(); load++)
					stack.push(player);
				stack.push(player);
				placeStack(board,src,stack);
				assertFalse(board.canMove(player.color,src,new int[]{board.getLoadLimit()+1},new Point(1,0)),"Move more than load limit");
				assertTrue(board.canMove(player.color,src,new int[]{board.getLoadLimit()},new Point(1,0)),"Move load limit");

				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(0,-1)),"Move at negative row");
				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(-1,0)),"Move at negative col");

				board.reset();
				stack.clear();
				stack.push(player);
				final int lastPos = board.getSize()-1;
				placeStack(board,new Point(lastPos, lastPos),stack);
				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(lastPos,lastPos+1)),"Move at row bigger than board");
				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(lastPos+1,lastPos)),"Move at col bigger than board");

				board.reset();
				stack.clear();
				stack.push(opponent);
				placeStack(board,src,stack);
				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(1,0)),"Move an invalid color");

				board.reset();
				stack.clear();
				stack.push(player);
				stack.push(opponent);
				placeStack(board,src,stack);
				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(1,0)),"Move an invalid color over a valid color");

				board.reset();
				stack.clear();
				stack.push(player);
				placeStack(board,src,stack);
				board.place(Piece.MENHIR_BLACK, new Point(1,0));
				board.place(Piece.CAPSTONE_BLACK, new Point(0,1));
				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(1,0)),"Cannot put over a black menhir");
				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(0,1)),"Cannot put over a black capstone");

				board.reset();
				stack.clear();
				stack.push(player);
				placeStack(board,src,stack);
				board.place(Piece.MENHIR_WHITE, new Point(1,0));
				board.place(Piece.CAPSTONE_WHITE, new Point(0,1));
				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(1,0)),"Cannot put over a white menhir");
				assertFalse(board.canMove(player.color,src,new int[]{1},new Point(0,1)),"Cannot put over a white capstone");

				board.reset();
				stack.clear();
				stack.push(player);
				stack.push(player);
				stack.push(Piece.valueOf("CAPSTONE_" + (player.isBlack() ? "BLACK": "WHITE")));
				placeStack(board,src,stack);
				board.place(Piece.valueOf("MENHIR_"+ (opponent.isBlack() ? "BLACK": "WHITE")), new Point(2,0));
				assertFalse(board.canMove(player.color,src,new int[]{1,2},new Point(2,0)),"Put a capstone and a piece over a menhir during last move");
				assertTrue(board.canMove(player.color,src,new int[]{1,1},new Point(2,0)),"Put a capstone only over a menhir during last move");
			}
		}
	}

	@Test
	void computeAmount(){
		assertEquals(Board.INVALID_AMOUNT, Board.computeAmount(new int[0]),"Length is 0");
		assertEquals(Board.INVALID_AMOUNT, Board.computeAmount(new int[]{0}),"Length is 1, amount of 0");
		assertEquals(Board.INVALID_AMOUNT, Board.computeAmount(new int[]{0,1,1}),"Length is 3, starting amount of 0");
		assertEquals(Board.INVALID_AMOUNT, Board.computeAmount(new int[]{1,0,1}),"Length is 3, amount of 0");
		assertEquals(Board.INVALID_AMOUNT, Board.computeAmount(new int[]{1,1,0}),"Length is 3, ending amount of 0");

		assertEquals(10, Board.computeAmount(new int[]{10}),"Length is 1");
		assertEquals(20, Board.computeAmount(new int[]{10,10}),"Length is 2");
		assertEquals(30, Board.computeAmount(new int[]{10,10,10}),"Length is 3");
	}

	@Test
	void isStraightPath(){
		final Point src = new Point(1,1);
		int[] delta = {-1,0,1};
		for(int dx:delta){
			for(int dy:delta){
				final Point dst = new Point(src.x+dx, src.y+dy);
				if(dx * dy == 0){
					assertTrue(Board.isStraightPath(src,dst),"isStraightPath straight for src="+src.toString()+" and dst="+dst.toString());
				}else{
					assertFalse(Board.isStraightPath(src,dst),"isStraightPath not straight for src="+src.toString()+" and dst="+dst.toString());
				}
			}
		}
	}

	@Test
	void move(){
		/*
				TODO coupole transforme un menhir en dolmen !
		*/
	}

	@Test
	void getLoadLimit(){
		for(Board.Size size:Board.Size.values()){
			int expected = size.length;
			assertEquals(expected, new Board(size).getLoadLimit(), "Load limit for "+size.toString());
		}
	}

	@Test
	void getSize(){
		for(Board.Size size:Board.Size.values()){
			final Board board = new Board(size);
			assertEquals(size.length,board.getSize(),"Size for "+size.toString());
		}
	}

	@Test
	void countTopPieces(){
		Piece[] pieces		= Piece.values();
		int[] occurences	= new int[pieces.length];
		for(int i=0; i<occurences.length; i++){
			occurences[i]=i;
		}
		final int mid = pieces.length>>1;
		for(Board.Size size:Board.Size.values()){
			final Board board = new Board(size);
			int i;
			int r=0,c=0;
			for(i=0; i<mid; i++){
				for(int j=0; j<occurences[i]; j++){
					board.place(pieces[i],new Point(c,r));
					c++;
					if(c >= board.getSize()){
						c = 0;
						r++;
					}
				}
			}
			for(; i<pieces.length; i++){
				Stack<Piece> stack = new Stack<Piece>();
				for(int j=0; j<occurences[i]; j++){
					stack.push(pieces[i]);
				}
				placeStack(board,new Point(c,r),stack);
				c++;
				if(c >= board.getSize()){
					c = 0;
					r++;
				}
			}
			for(int id=0; id<pieces.length; id++){
				assertEquals(id < mid ? occurences[id] : 1, board.countTopPieces(pieces[id]), "count " + pieces[id].toString() + " differs");
			}
		}
	}

	@Test
	void getStack(){
		for(Board.Size size: Board.Size.values()){
			final Board board = new Board(size);
			final Point position = new Point(board.getSize()-1, board.getSize()-1);
			final int BOTTOM = 0, TOP = 1;

			assertEquals(0, board.getStack(position).length, "Empty stack");

			board.place(Piece.DOLMEN_BLACK, position);
			assertEquals(1, board.getStack(position).length, "Stack of 1 element (length)");
			assertEquals(board.getStack(position)[BOTTOM], Piece.DOLMEN_BLACK, "Stack of 1 element");

			board.place(Piece.DOLMEN_WHITE, position);
			assertEquals(2, board.getStack(position).length, "Stack of 2 elements (length)");
			assertEquals(board.getStack(position)[BOTTOM], Piece.DOLMEN_BLACK, "Stack of 2 elements (bottom)");
			assertEquals(board.getStack(position)[TOP], Piece.DOLMEN_WHITE, "Stack of 2 elements (top)");
		}
	}

	@Test
	void getTop(){
		for(Board.Size size: Board.Size.values()){
			final Board board = new Board(size);
			final Point position = new Point(board.getSize()-1, board.getSize()-1);

			assertNull(board.getTop(position), "Empty stack");

			board.place(Piece.DOLMEN_BLACK, position);
			assertEquals(board.getTop(position), Piece.DOLMEN_BLACK, "Stack of 1 element");

			board.place(Piece.DOLMEN_WHITE, position);
			assertEquals(board.getTop(position), Piece.DOLMEN_WHITE, "Stack of 2 elements");
		}
		final Point position = new Point(0,0);
		Piece[] pieces = Piece.values();
		for(int shift=0; shift<pieces.length-1;shift++){
			final Board board = new Board(Board.Size.TINY);
			for(Piece p:pieces) board.place(p,position);
			assertEquals(board.getTop(position), pieces[pieces.length-1], "Stack of all elements (shift="+shift+")");
			// shift 1
			Piece newLast = pieces[0];
			for(int i=1; i<pieces.length; i++) pieces[i-1]=pieces[i];
			pieces[pieces.length-1]=newLast;
		}
	}

	@Test
	void countInitialCapstones(){
		for(Board.Size size:Board.Size.values()){
			int expected = -1;
			switch(size){
				case TINY:		expected = 0; break;
				case SMALL:		expected = 0; break;
				case MEDIUM:	expected = 1; break;
				case LARGE:		expected = 1; break;
				case HUGE:		expected = 2; break;
				default: fail("Invalid board size");
			}
			assertEquals(expected, new Board(size).countInitialCapstones(), "Initial capstones for "+size.toString());
		}
	}

	@Test
	void countInitialStones(){
		for(Board.Size size:Board.Size.values()){
			int expected = -1;
			switch(size){
				case TINY:		expected = 10; break;
				case SMALL:		expected = 15; break;
				case MEDIUM:	expected = 21; break;
				case LARGE:		expected = 30; break;
				case HUGE:		expected = 50; break;
				default: fail("Invalid board size");
			}
			assertEquals(expected, new Board(size).countInitialStones(), "Initial stones for "+size.toString());
		}
	}

	@Test
	void testClone(){
		Piece[] pieces = Piece.values();
		for(Board.Size size: Board.Size.values()){
			final Board baseline		= new Board(size);
			final Board duplicate	= new Board(size);
			final int n = size.length;
			int i = 0;
			for(int row = 0; row < n; row++){
				for(int col = 0; col < n; col++){
					baseline.place(pieces[i], new Point(col, row));
					duplicate.place(pieces[i], new Point(col, row));
					i = (i + 1) % pieces.length;
				}
			}
			// Create a stack at 0,0
			baseline.place(pieces[0], new Point(0,0));
			duplicate.place(pieces[0], new Point(0, 0));

			final Board duplicated = duplicate.clone();
			assertEquals(baseline.getSize(),duplicated.getSize(),"getSize");
			assertEquals(baseline.getLoadLimit(),duplicated.getLoadLimit(),"getLoadLimit");
			assertEquals(baseline.countEmpty(),duplicated.countEmpty(),"countEmpty");
			assertEquals(baseline.isCompleted(),duplicated.isCompleted(),"isCompleted");
			assertEquals(baseline.countInitialCapstones(),duplicated.countInitialCapstones(),"countInitialCapstones");
			assertEquals(baseline.countInitialStones(),duplicated.countInitialStones(),"countInitialStones");

			for(Piece piece: pieces)
				assertEquals(baseline.countTopPieces(piece),duplicated.countTopPieces(piece),"("+piece.toString()+")");

			for(int row=0; row<n; row++){
				for(int col=0; col<n; col++){
					Piece[] expected	= baseline.getStack(row,col);
					Piece[] actual		= duplicated.getStack(row,col);
					assertArrayEquals(expected,actual,"Stack at row="+row+" col="+col);
				}
			}
		}
	}

	@Override
	public int compare(Point a, Point b){
		return a.y != b.y ? a.y - b.y :  a.x - b.x;
	}
}