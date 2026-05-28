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
import be.heh.math.core.move.MoveApplier;
import be.heh.math.core.move.MoveGenerator;
import be.heh.math.core.state.BoardState;

import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
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
        if (root == null) {
            root = buildFreshRoot(board, myself, opponent, myColor);
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
        MctsNode best = mostVisitedChild(root);
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
                node.untriedActions = generateActionsFor(node);
                Collections.shuffle(node.untriedActions, random);
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
        Action action = parent.untriedActions.remove(parent.untriedActions.size() - 1);
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
            Action a = legal.get(random.nextInt(legal.size()));
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

    private boolean isTerminal(MctsNode node) {
        return node.state.anyPathExists(Constants.BLACK_PLAYER)
                || node.state.anyPathExists(Constants.WHITE_PLAYER);
    }

    private MctsNode mostVisitedChild(MctsNode root) {
        MctsNode best = null;
        int bestVisits = -1;
        for (MctsNode c : root.children) {
            if (c.visits > bestVisits) {
                bestVisits = c.visits;
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
}
