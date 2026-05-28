package be.heh.math;

import be.belegkarnil.game.board.tak.Action;
import be.belegkarnil.game.board.tak.Board;
import be.belegkarnil.game.board.tak.Constants;
import be.belegkarnil.game.board.tak.Game;
import be.belegkarnil.game.board.tak.Piece;
import be.belegkarnil.game.board.tak.Player;
import be.belegkarnil.game.board.tak.event.RoundEvent;
import be.belegkarnil.game.board.tak.event.RoundListener;
import be.belegkarnil.game.board.tak.strategy.Strategy;
import be.heh.math.core.eval.Evaluator;
import be.heh.math.core.move.MoveApplier;
import be.heh.math.core.move.MoveGenerator;
import be.heh.math.core.state.BoardState;

import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * ÉTAPE 4 — Monte Carlo Tree Search "vanilla" + Progressive Widening + Tree Reuse.
 *
 * Architecture :
 *   1) treePolicy : descente dans l'arbre via UCB1, avec progressive widening
 *      (un nœud autorise ⌈C_PW · √visites⌉ enfants).
 *   2) expand     : crée un enfant en piochant dans untriedActions.
 *   3) rollout    : simulation ALÉATOIRE UNIFORME jusqu'à victoire OU profondeur max.
 *   4) backprop   : remonte le résultat (du point de vue du décideur de l'action).
 *
 * Tree reuse :
 *   Entre deux tours, on tente de récupérer le sous-arbre exploré.
 *   Si l'adversaire joue une action déjà explorée par MCTS au tour précédent,
 *   le grand-enfant correspondant devient la nouvelle racine.
 *   Sinon, on reconstruit de zéro.
 *
 * Tracking des pièces en main :
 *   Chaque nœud porte les comptes (stones/capstones) BLACK et WHITE de cet état.
 *   Indispensable car BoardState ne sait pas combien de pièces il reste à chacun.
 *
 * Budget de temps :
 *   Constante {@link #TIME_BUDGET_MS} ajustable. 1 s = compromis MEDIUM/LARGE.
 *
 * @author be.heh.math
 */
public class MctsStrategy implements Strategy, RoundListener {

    // ========================================================================
    // CONFIG (publique, ajustable depuis l'extérieur)
    // ========================================================================
    /** Temps alloué par tour, en millisecondes. */
    public static long TIME_BUDGET_MS = 5_000;
    /** Constante d'exploration UCB1 (≈ sqrt(2)). */
    public static double UCB_C = 1.41;
    /** Constante de progressive widening : enfants autorisés = ⌈C·√visites⌉. */
    public static double PW_C = 1.5;
    /** Profondeur max d'un rollout (évite les boucles longues sur partie non terminale). */
    public static int MAX_ROLLOUT_DEPTH = 80;
    public static double ROLLOUT_RANDOMNESS = 0.12;
    public static int ROLLOUT_CANDIDATES = 18;

    private static final int WIN_SCORE = 1_000_000;
    private static final int BLOCK_SCORE = 250_000;
    private static final int ROAD_WEIGHT = 1_200;

    // ========================================================================
    // ÉTAT
    // ========================================================================
    private final Random random = new Random();
    private boolean firstAction = false;

    /** Racine MCTS du tour précédent, pour le tree reuse. */
    private MctsNode lastRoot = null;
    /** Action que NOUS avons jouée au tour précédent (clef du subtree à recoller). */
    private Action lastOurAction = null;

    public MctsStrategy() { }

    // ========================================================================
    // ENTRY POINT
    // ========================================================================
    @Override
    public Action plays(Player myself, Board board, Player opponent) {
        // Premier coup du round : placement déterministe au centre (pas de MCTS)
        if (firstAction) {
            firstAction = false;
            lastRoot = null;
            lastOurAction = null;
            return placeOpponentDolmenNearCenter(board, opponent);
        }

        final Color myColor  = myself.getColor();
        final Color oppColor = opponent.getColor();

        // ----- Construction de la racine : tree reuse OU fresh -----
        MctsNode root = tryReuseRoot(board);
        if (root == null || root.toMove != myColor) {
            root = buildFreshRoot(board, myself, opponent, myColor);
        }

        List<Action> rootActions = generateActionsFor(root);
        if (rootActions.isEmpty()) {
            resetTree();
            return new Action();
        }

        Action winNow = findWinningAction(root.state, rootActions, myColor);
        if (winNow != null) {
            resetTree();
            return winNow;
        }

        Action urgentBlock = findBlockingAction(root.state, rootActions, myColor, oppColor,
                root.blackStones, root.blackCapstones, root.whiteStones, root.whiteCapstones);
        if (urgentBlock != null) {
            lastRoot = root;
            lastOurAction = urgentBlock;
            return urgentBlock;
        }

        // ----- Boucle MCTS sous budget temps -----
        long deadline = System.currentTimeMillis() + TIME_BUDGET_MS;
        int iterations = 0;
        while (System.currentTimeMillis() < deadline) {
            MctsNode leaf = treePolicy(root);
            Color winner = rolloutFrom(leaf, myColor, oppColor);
            backprop(leaf, winner);
            iterations++;
        }

        // ----- Choix du coup : enfant le plus visité -----
        MctsNode best = bestRootChild(root);
        if (best == null) {
            // Aucun enfant exploré (budget trop court ou aucune action légale)
            if (root.untriedActions != null && !root.untriedActions.isEmpty()) {
                Action fallback = root.untriedActions.get(0);
                lastRoot = null;
                lastOurAction = null;
                return fallback;
            }
            lastRoot = null;
            lastOurAction = null;
            return new Action();
        }

        lastRoot = root;
        lastOurAction = best.incomingAction;
        return best.incomingAction;
    }

    // ========================================================================
    // TREE POLICY : selection + expansion (avec progressive widening)
    // ========================================================================
    private MctsNode treePolicy(MctsNode node) {
        while (!isTerminal(node)) {
            if (node.untriedActions == null) {
                node.untriedActions = orderedActionsFor(node);
            }
            // Progressive widening : combien d'enfants autorisés à ce nombre de visites ?
            int allowed = (int) Math.ceil(PW_C * Math.sqrt(node.visits + 1.0));
            if (node.children.size() < allowed && !node.untriedActions.isEmpty()) {
                return expand(node);
            }
            if (node.children.isEmpty()) return node; // pas d'action légale : terminal de facto
            node = bestUCB(node);
        }
        return node;
    }

    private MctsNode expand(MctsNode parent) {
        Action action = parent.untriedActions.remove(0);
        return makeChild(parent, action);
    }

    /** Crée un nœud enfant à partir d'une action appliquée sur l'état parent. */
    private MctsNode makeChild(MctsNode parent, Action action) {
        BoardState newState = new BoardState(parent.state);
        MoveApplier.apply(newState, action);

        int bs = parent.blackStones, bc = parent.blackCapstones;
        int ws = parent.whiteStones, wc = parent.whiteCapstones;
        if (action.isPlace()) {
            boolean blackMoves = (parent.toMove == Constants.BLACK_PLAYER);
            if (action.piece.isCapstone()) {
                if (blackMoves) bc--; else wc--;
            } else {
                if (blackMoves) bs--; else ws--;
            }
        }
        Color next = (parent.toMove == Constants.BLACK_PLAYER)
                ? Constants.WHITE_PLAYER : Constants.BLACK_PLAYER;
        MctsNode child = new MctsNode(parent, action, next, newState, bs, bc, ws, wc);
        parent.children.add(child);
        return child;
    }

    /** Sélection UCB1 parmi les enfants déjà créés. */
    private MctsNode bestUCB(MctsNode node) {
        MctsNode best = null;
        double bestVal = Double.NEGATIVE_INFINITY;
        double lnN = Math.log(Math.max(1, node.visits));
        for (MctsNode c : node.children) {
            double exploitation = (c.visits == 0) ? 0.5 : (c.wins / c.visits);
            double exploration = (c.visits == 0) ? Double.MAX_VALUE
                    : UCB_C * Math.sqrt(lnN / c.visits);
            double ucb = exploitation + exploration;
            if (ucb > bestVal) {
                bestVal = ucb;
                best = c;
            }
        }
        return best;
    }

    // ========================================================================
    // ROLLOUT : simulation ALÉATOIRE UNIFORME
    // ========================================================================
    private Color rolloutFrom(MctsNode leaf, Color myColor, Color oppColor) {
        // Si l'état est déjà terminal, retourner directement
        if (leaf.state.anyPathExists(myColor))  return myColor;
        if (leaf.state.anyPathExists(oppColor)) return oppColor;

        // Cloner l'état pour pouvoir le muter
        BoardState state = new BoardState(leaf.state);
        Color toMove = leaf.toMove;
        int bs = leaf.blackStones, bc = leaf.blackCapstones;
        int ws = leaf.whiteStones, wc = leaf.whiteCapstones;

        for (int depth = 0; depth < MAX_ROLLOUT_DEPTH; depth++) {
            int stones = (toMove == Constants.BLACK_PLAYER) ? bs : ws;
            int caps   = (toMove == Constants.BLACK_PLAYER) ? bc : wc;
            Color other = (toMove == Constants.BLACK_PLAYER)
                    ? Constants.WHITE_PLAYER : Constants.BLACK_PLAYER;
            List<Action> legal = MoveGenerator.generateLegal(state, toMove, stones, caps, other, false);
            if (legal.isEmpty()) {
                return tiebreak(state, myColor, oppColor);
            }
            Action a = chooseRolloutAction(state, legal, toMove, other);
            Color winner = MoveApplier.apply(state, a);
            if (a.isPlace()) {
                boolean black = (toMove == Constants.BLACK_PLAYER);
                if (a.piece.isCapstone()) {
                    if (black) bc--; else wc--;
                } else {
                    if (black) bs--; else ws--;
                }
            }
            if (winner != null) return winner;
            toMove = other;
        }
        // Profondeur max : tie-break sur dolmens (cohérent avec Game.WinningReason.DOLMEN_TIE)
        return tiebreak(state, myColor, oppColor);
    }

    /** Tie-break : qui a le plus de DOLMEN au sommet ? null si égalité (= TIE_SECOND_PLAYER côté Game). */
    private Color tiebreak(BoardState state, Color myColor, Color oppColor) {
        Piece myDolmen  = (myColor  == Constants.BLACK_PLAYER) ? Piece.DOLMEN_BLACK : Piece.DOLMEN_WHITE;
        Piece oppDolmen = (oppColor == Constants.BLACK_PLAYER) ? Piece.DOLMEN_BLACK : Piece.DOLMEN_WHITE;
        int mine = state.countTopPieces(myDolmen);
        int theirs = state.countTopPieces(oppDolmen);
        if (mine > theirs) return myColor;
        if (theirs > mine) return oppColor;
        int eval = Evaluator.evaluate(state, myColor);
        if (eval > 0) return myColor;
        if (eval < 0) return oppColor;
        return null;
    }

    // ========================================================================
    // BACKPROPAGATION
    // ========================================================================
    private void backprop(MctsNode leaf, Color winner) {
        MctsNode node = leaf;
        while (node != null) {
            node.visits++;
            if (node.parent != null) {
                // wins du point de vue du décideur de cette action = parent.toMove
                Color decider = node.parent.toMove;
                if (winner == decider) {
                    node.wins += 1.0;
                } else if (winner == null) {
                    node.wins += 0.5; // demi-point sur égalité
                }
                // sinon (adversaire gagne) : pas d'incrément
            }
            node = node.parent;
        }
    }

    // ========================================================================
    // GÉNÉRATION D'ACTIONS POUR UN NŒUD
    // ========================================================================
    private List<Action> generateActionsFor(MctsNode node) {
        boolean black = (node.toMove == Constants.BLACK_PLAYER);
        int stones = black ? node.blackStones : node.whiteStones;
        int caps   = black ? node.blackCapstones : node.whiteCapstones;
        Color other = black ? Constants.WHITE_PLAYER : Constants.BLACK_PLAYER;
        return MoveGenerator.generateLegal(node.state, node.toMove, stones, caps, other, false);
    }

    private List<Action> orderedActionsFor(MctsNode node) {
        List<Action> legal = generateActionsFor(node);
        Color other = opponentOf(node.toMove);
        ArrayList<ScoredAction> scored = new ArrayList<>(legal.size());
        for (Action action : legal) {
            scored.add(new ScoredAction(action, actionScore(node.state, action, node.toMove, other)));
        }
        scored.sort(Comparator.comparingInt((ScoredAction sa) -> sa.score).reversed());

        ArrayList<Action> ordered = new ArrayList<>(scored.size());
        for (ScoredAction entry : scored) {
            ordered.add(entry.action);
        }
        return ordered;
    }

    private boolean isTerminal(MctsNode node) {
        return node.state.anyPathExists(Constants.BLACK_PLAYER)
                || node.state.anyPathExists(Constants.WHITE_PLAYER);
    }

    private MctsNode bestRootChild(MctsNode root) {
        MctsNode best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (MctsNode c : root.children) {
            double winRate = c.visits == 0 ? 0.0 : c.wins / c.visits;
            double value = Math.sqrt(c.visits) + winRate;
            if (value > bestValue) {
                bestValue = value;
                best = c;
            }
        }
        return best;
    }

    // ========================================================================
    // TREE REUSE
    // ========================================================================
    private MctsNode tryReuseRoot(Board currentBoard) {
        if (lastRoot == null || lastOurAction == null) return null;

        // Étape 1 : trouver l'enfant de lastRoot correspondant à NOTRE dernière action
        MctsNode afterMe = null;
        for (MctsNode c : lastRoot.children) {
            if (actionsEqual(c.incomingAction, lastOurAction)) {
                afterMe = c;
                break;
            }
        }
        if (afterMe == null) return null;

        // Étape 2 : parmi ses enfants (= réponses adverses explorées),
        // trouver celui dont l'état matche le board courant
        for (MctsNode gc : afterMe.children) {
            if (gc.state.matchesBoard(currentBoard)) {
                gc.parent = null; // détache, devient nouvelle racine
                return gc;
            }
        }
        return null;
    }

    private MctsNode buildFreshRoot(Board board, Player me, Player opp, Color myColor) {
        BoardState state = new BoardState(board);
        boolean iAmBlack = (myColor == Constants.BLACK_PLAYER);
        int bs = iAmBlack ? me.countStones()    : opp.countStones();
        int bc = iAmBlack ? me.countCapstones() : opp.countCapstones();
        int ws = iAmBlack ? opp.countStones()   : me.countStones();
        int wc = iAmBlack ? opp.countCapstones(): me.countCapstones();
        return new MctsNode(null, null, myColor, state, bs, bc, ws, wc);
    }

    private Action chooseRolloutAction(BoardState state, List<Action> legal, Color toMove, Color opponent) {
        Action winNow = findWinningAction(state, legal, toMove);
        if (winNow != null) return winNow;

        if (random.nextDouble() < ROLLOUT_RANDOMNESS) {
            return legal.get(random.nextInt(legal.size()));
        }

        int limit = Math.min(ROLLOUT_CANDIDATES, legal.size());
        Action best = legal.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < limit; i++) {
            Action action = legal.get(random.nextInt(legal.size()));
            int score = rolloutActionScore(state, action, toMove, opponent);
            if (score > bestScore) {
                bestScore = score;
                best = action;
            }
        }
        return best;
    }

    private int actionScore(BoardState state, Action action, Color toMove, Color opponent) {
        BoardState next = new BoardState(state);
        Color winner = MoveApplier.apply(next, action);
        if (winner == toMove) return WIN_SCORE;
        if (winner == opponent) return -WIN_SCORE;

        int score = Evaluator.evaluate(next, toMove);
        score += ROAD_WEIGHT * (bestRoadDistance(state, toMove) - bestRoadDistance(next, toMove));
        score += ROAD_WEIGHT * (bestRoadDistance(next, opponent) - bestRoadDistance(state, opponent));

        if (action.isPlace()) {
            int center = state.getSize() / 2;
            int distance = Math.abs(action.position.x - center) + Math.abs(action.position.y - center);
            score += 60 - 10 * distance;
            if (action.piece.isCapstone()) score += 35;
            if (action.piece.isMenhir()) score += 20;
        } else if (action.isMove()) {
            score += 25 + 5 * state.stackHeight(action.position.y, action.position.x);
        }
        return score;
    }

    private int rolloutActionScore(BoardState state, Action action, Color toMove, Color opponent) {
        BoardState next = new BoardState(state);
        Color winner = MoveApplier.apply(next, action);
        if (winner == toMove) return WIN_SCORE;
        if (winner == opponent) return -WIN_SCORE;

        int score = Evaluator.evaluate(next, toMove);
        if (action.isPlace()) {
            int center = state.getSize() / 2;
            int distance = Math.abs(action.position.x - center) + Math.abs(action.position.y - center);
            score += 45 - 8 * distance;
            if (action.piece.isCapstone()) score += 30;
            if (action.piece.isMenhir()) score += 15;
        } else if (action.isMove()) {
            score += 20 + 4 * state.stackHeight(action.position.y, action.position.x);
        }
        return score;
    }

    private Action findWinningAction(BoardState state, List<Action> legal, Color player) {
        for (Action action : legal) {
            BoardState next = new BoardState(state);
            if (MoveApplier.apply(next, action) == player) {
                return action;
            }
        }
        return null;
    }

    private Action findBlockingAction(BoardState state, List<Action> legal, Color player, Color opponent,
                                      int blackStones, int blackCaps, int whiteStones, int whiteCaps) {
        int oppStones = opponent == Constants.BLACK_PLAYER ? blackStones : whiteStones;
        int oppCaps = opponent == Constants.BLACK_PLAYER ? blackCaps : whiteCaps;
        List<Action> opponentActions = MoveGenerator.generateLegal(state, opponent, oppStones, oppCaps, player, false);
        if (findWinningAction(state, opponentActions, opponent) == null) {
            return null;
        }

        Action bestBlock = null;
        int bestScore = Integer.MIN_VALUE;
        for (Action action : legal) {
            BoardState next = new BoardState(state);
            Color winner = MoveApplier.apply(next, action);
            if (winner == player) return action;

            List<Action> replies = MoveGenerator.generateLegal(next, opponent, oppStones, oppCaps, player, false);
            if (findWinningAction(next, replies, opponent) == null) {
                int score = BLOCK_SCORE + actionScore(state, action, player, opponent);
                if (score > bestScore) {
                    bestScore = score;
                    bestBlock = action;
                }
            }
        }
        return bestBlock;
    }

    private int bestRoadDistance(BoardState state, Color color) {
        return Math.min(roadDistance(state, color, true), roadDistance(state, color, false));
    }

    private int roadDistance(BoardState state, Color color, boolean vertical) {
        int size = state.getSize();
        int[][] dist = new int[size][size];
        boolean[][] visited = new boolean[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                dist[row][col] = 1_000_000;
            }
        }

        for (int i = 0; i < size; i++) {
            int row = vertical ? 0 : i;
            int col = vertical ? i : 0;
            dist[row][col] = cellCost(state, color, row, col);
        }

        for (int step = 0; step < size * size; step++) {
            int bestRow = -1;
            int bestCol = -1;
            int best = 1_000_000;
            for (int row = 0; row < size; row++) {
                for (int col = 0; col < size; col++) {
                    if (!visited[row][col] && dist[row][col] < best) {
                        best = dist[row][col];
                        bestRow = row;
                        bestCol = col;
                    }
                }
            }
            if (bestRow < 0) break;
            visited[bestRow][bestCol] = true;
            relax(state, color, dist, visited, bestRow, bestCol, bestRow + 1, bestCol);
            relax(state, color, dist, visited, bestRow, bestCol, bestRow - 1, bestCol);
            relax(state, color, dist, visited, bestRow, bestCol, bestRow, bestCol + 1);
            relax(state, color, dist, visited, bestRow, bestCol, bestRow, bestCol - 1);
        }

        int target = 1_000_000;
        for (int i = 0; i < size; i++) {
            int row = vertical ? size - 1 : i;
            int col = vertical ? i : size - 1;
            target = Math.min(target, dist[row][col]);
        }
        return Math.min(target, size + 4);
    }

    private void relax(BoardState state, Color color, int[][] dist, boolean[][] visited,
                       int fromRow, int fromCol, int toRow, int toCol) {
        if (!state.inBounds(toRow, toCol) || visited[toRow][toCol]) return;
        int next = dist[fromRow][fromCol] + cellCost(state, color, toRow, toCol);
        if (next < dist[toRow][toCol]) {
            dist[toRow][toCol] = next;
        }
    }

    private int cellCost(BoardState state, Color color, int row, int col) {
        if (state.isFree(row, col)) return 1;
        Piece top = state.getTop(row, col);
        if (top.color == color) return top.isMenhir() ? 3 : 0;
        return top.isMenhir() ? 2 : 5;
    }

    private Color opponentOf(Color color) {
        return color == Constants.BLACK_PLAYER ? Constants.WHITE_PLAYER : Constants.BLACK_PLAYER;
    }

    private void resetTree() {
        lastRoot = null;
        lastOurAction = null;
    }

    /** Comparaison structurelle de deux Actions (Action n'override pas equals). */
    private static boolean actionsEqual(Action a, Action b) {
        if (a == null || b == null) return a == b;
        if (a.isSkip()  != b.isSkip())  return false;
        if (a.isPlace() != b.isPlace()) return false;
        if (a.isMove()  != b.isMove())  return false;
        if (a.isSkip()) return true;
        if (a.isPlace()) {
            return a.piece == b.piece && pointEqual(a.position, b.position);
        }
        // MOVE
        if (!pointEqual(a.position, b.position)) return false;
        if (!pointEqual(a.destination, b.destination)) return false;
        int[] aa = a.getAmount(), bb = b.getAmount();
        if (aa.length != bb.length) return false;
        for (int i = 0; i < aa.length; i++) if (aa[i] != bb[i]) return false;
        return true;
    }
    private static boolean pointEqual(Point p, Point q) {
        return p.x == q.x && p.y == q.y;
    }

    // ========================================================================
    // PREMIER COUP DU ROUND
    // ========================================================================
    private Action placeOpponentDolmenNearCenter(Board board, Player opponent) {
        Piece dolmen = (opponent.getColor() == Constants.BLACK_PLAYER)
                ? Piece.DOLMEN_BLACK : Piece.DOLMEN_WHITE;
        int size = board.getSize();
        // Essai du centre d'abord
        int cr = size / 2, cc = size / 2;
        if (board.isFree(cr, cc)) return new Action(dolmen, new Point(cc, cr));
        // Fallback : première case libre
        for (int row = 0; row < size; row++)
            for (int col = 0; col < size; col++)
                if (board.isFree(row, col)) return new Action(dolmen, new Point(col, row));
        return new Action(); // ne devrait jamais arriver (board plein → fin de round)
    }

    // ========================================================================
    // STRATEGY LIFECYCLE
    // ========================================================================
    @Override public void register(Game game)   { game.addRoundListener(this); }
    @Override public void unregister(Game game) { game.removeRoundListener(this); }

    @Override
    public void onRoundBegins(RoundEvent event) {
        firstAction = true;
        lastRoot = null;
        lastOurAction = null;
    }
    @Override
    public void onRoundEnds(RoundEvent event) { /* rien */ }

    // ========================================================================
    // INNER CLASS : nœud de l'arbre MCTS
    // ========================================================================
    private static class MctsNode {
        MctsNode parent;                       // null pour la racine
        final Action incomingAction;           // action qui a mené à ce nœud (null pour racine)
        final Color toMove;                    // qui joue depuis cet état
        final BoardState state;                 // état du plateau à ce nœud

        // Comptes de pièces en main À CET ÉTAT
        final int blackStones, blackCapstones;
        final int whiteStones, whiteCapstones;

        int visits = 0;
        double wins = 0.0;                     // perspective du parent.toMove

        final List<MctsNode> children = new ArrayList<>();
        List<Action> untriedActions = null;    // lazy init

        MctsNode(MctsNode parent, Action incomingAction, Color toMove, BoardState state,
                 int bs, int bc, int ws, int wc) {
            this.parent = parent;
            this.incomingAction = incomingAction;
            this.toMove = toMove;
            this.state = state;
            this.blackStones = bs;
            this.blackCapstones = bc;
            this.whiteStones = ws;
            this.whiteCapstones = wc;
        }
    }

    private static final class ScoredAction {
        final Action action;
        final int score;

        ScoredAction(Action action, int score) {
            this.action = action;
            this.score = score;
        }
    }
}
