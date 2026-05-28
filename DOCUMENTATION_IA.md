# Documentation IA pour BelegTak

Ce document récapitule l'API du projet BelegTak et propose des pistes concrètes pour implémenter une IA basée sur trois familles d'algorithmes : **Markovien / MCTS**, **A\*** et **Greedy**.

---

## 1. Vue d'ensemble du jeu

BelegTak est une variante simplifiée de TAK (deux joueurs, multi-rounds). Le but d'un round est de **créer un chemin continu** de pièces de sa couleur :
- soit du **bord gauche au bord droit**,
- soit du **bord haut au bord bas**.

Les **MENHIR** ne comptent **pas** dans le chemin (ce sont des "murs" qui bloquent les déplacements).
Les **DOLMEN** comptent dans le chemin.
Les **CAPSTONE** comptent et peuvent traverser les MENHIR pour les transformer en DOLMEN.

### Caractéristiques importantes
- Le jeu est en **plusieurs rounds** (par défaut, premier à 2 victoires).
- Chaque tour a un **timeout** (60 s par défaut). Dépasser le timeout = skip + pénalité.
- 3 skips consécutifs = perte du round (`DEFAULT_SKIP_LIMIT = 3`).
- Au **premier tour de chaque round**, chaque joueur place un **DOLMEN de la couleur adverse** (règle de handicap).
- Les couleurs sont stockées en `java.awt.Color`. **Noir** = `Color(87, 227, 137)` (en réalité vert), **Blanc** = `Color.PINK`. Utilisez TOUJOURS les constantes `Constants.BLACK_PLAYER` / `Constants.WHITE_PLAYER`.

### Pièces disponibles (selon taille de plateau)

| Taille | Largeur | Stones | Capstones |
|--------|---------|--------|-----------|
| TINY   |    3    |   10   |     0     |
| SMALL  |    4    |   15   |     0     |
| MEDIUM |    5    |   21   |     1     |
| LARGE  |    6    |   30   |     1     |
| HUGE   |    8    |   50   |     2     |

`stones` = total de DOLMEN + MENHIR (le joueur choisit lequel poser à chaque tour).
`getLoadLimit()` = taille du plateau = nombre maximum de pièces qu'on peut déplacer en un seul mouvement.

---

## 2. API : les classes essentielles

### 2.1 `Strategy` (interface à implémenter)

```java
public interface Strategy {
    Action plays(Player myself, Board board, Player opponent);
    void register(Game game);     // appelée au début d'une partie
    void unregister(Game game);   // appelée à la fin
}
```

Pour la plupart des cas, **étendez `StrategyAdapter`** (classe abstraite qui fournit des implémentations vides de `register`/`unregister`).

Pour bénéficier des événements (réinitialiser un état entre les rounds par exemple), implémentez en plus `RoundListener` ou `TurnListener` et inscrivez-vous dans `register(Game)`.

### 2.2 `Action` — ce que votre IA doit retourner

Trois constructeurs, trois types d'action :

```java
// 1) PLACE : poser une pièce
new Action(Piece piece, Point position);

// 2) MOVE : déplacer une pile
new Action(Point source, Point destination, int[] amount);

// 3) SKIP : passer son tour
new Action();
```

**Attention au `Point`** : `Point.x` = colonne, `Point.y` = ligne. C'est l'inverse de la convention "matrix[row][col]". Tout le code interne utilise `point.y` pour la ligne.

**Tableau `amount[]` pour MOVE** : il décrit combien de pièces déposer à chaque case **sur le chemin**. Sa longueur = distance parcourue (nombre de cases entre source et destination, destination incluse).
- `amount.length` doit être égal à `|dst.x - src.x|` ou `|dst.y - src.y|`.
- `sum(amount)` ≤ `board.getLoadLimit()` (taille du plateau).
- `sum(amount)` ≤ taille de la pile à la source.
- Chaque `amount[i]` ≥ 1.

Exemple : pour déplacer 3 pièces de (2,0) vers (2,2) en déposant 1 puis 2 :
```java
new Action(new Point(0,2), new Point(2,2), new int[]{1,2});
```

### 2.3 `Board` — état du plateau

Méthodes clés (toutes thread-safe par copie, car `Board implements Cloneable`) :

| Méthode | Usage |
|---------|-------|
| `clone()` / `new Board(board)` | **Indispensable** pour simuler des coups sans modifier l'original |
| `getSize()` | Largeur du plateau |
| `getLoadLimit()` | = `getSize()`, limite des piles |
| `inBounds(row, col)` | Position valide ? |
| `isFree(row, col)` | Case vide ? |
| `isUnderControl(color, row, col)` | Top de la pile appartient à ce joueur ? |
| `getTop(row, col)` | Pièce au sommet, `null` si vide |
| `getStack(row, col)` | Copie complète de la pile (bottom à index 0) |
| `getNeighbors(point)` | Voisins valides (4-connexité) |
| `canPlace(piece, row, col)` | Validation place |
| `canMove(color, src, amount, dst)` | Validation move |
| `pathExists(point, color)` | **Détection de victoire** depuis un point |
| `countTopPieces(piece)` | Compte un type de pièce au sommet |
| `countEmpty()` | Cases libres |
| `isCompleted()` | Plateau plein |

**Limitation importante** : `Board#place(...)` et `Board#move(...)` sont **`protected`**. Vous ne pouvez pas appliquer un coup sur le vrai `Board` depuis votre Strategy. Pour la simulation, vous devrez :
- soit cloner le `Board` et écrire votre propre `applyAction(...)` (en passant par réflexion ou en réimplémentant la logique de move/place),
- soit créer une **sous-classe** `SimulatedBoard extends Board` dans votre package qui rend ces méthodes accessibles (impossible si vous êtes hors du package `be.belegkarnil.game.board.tak`).

**La voie pragmatique** : réimplémentez `applyPlace` et `applyMove` côté IA, sur le clone. C'est mécanique (manipulation de `Stack<Piece>`), et c'est ce que les autres bots font. À tester contre la logique réelle pour s'assurer de l'équivalence.

### 2.4 `Piece` (enum)

`DOLMEN_BLACK`, `DOLMEN_WHITE`, `MENHIR_BLACK`, `MENHIR_WHITE`, `CAPSTONE_BLACK`, `CAPSTONE_WHITE`.

Méthodes : `isWhite()`, `isBlack()`, `isMenhir()`, `isDolmen()`, `isCapstone()`, champ `color`.

### 2.5 `Player`

| Méthode | Usage |
|---------|-------|
| `getColor()` | Votre couleur (assignée par le `Game`) |
| `countStones()` | Restants en main (DOLMEN+MENHIR) |
| `countCapstones()` | Restants en main |
| `countPieces()` | Total |
| `hasPiece(Piece)` | Pouvez-vous jouer cette pièce ? |
| `getPieces()` | `Map<Piece, Integer>` des pièces disponibles |
| `getScore()` | Score du round courant |
| `countWin()` | Nombre de rounds gagnés |
| `countSkip()` | Nombre de skips dans le round |

### 2.6 `Game` et événements

Quatre interfaces de listeners :
- `GameListener` : `onGameBegins`, `onGameEnds`
- `RoundListener` : `onRoundBegins`, `onRoundEnds` — **utile pour réinitialiser un état entre rounds**
- `TurnListener` : `onTurnBegins`, `onTurnEnds`
- `MisdesignListener` : `onTimeout`, `onException`, `onInvalidPiece`, `onInvalidAction` — utile en debug

À enregistrer dans votre `register(Game game)` via `game.addRoundListener(this)` etc.

---

## 3. Squelette d'une Strategy

```java
package be.heh.votreNom;  // package détecté par BelegTak.loadStrategies("be.heh")

import be.belegkarnil.game.board.tak.*;
import be.belegkarnil.game.board.tak.event.*;
import be.belegkarnil.game.board.tak.strategy.*;
import java.awt.Point;

public class MyStrategy extends StrategyAdapter implements RoundListener {
    private boolean firstAction;

    public MyStrategy() {
        // CONSTRUCTEUR PAR DÉFAUT OBLIGATOIRE — sinon non chargée
    }

    @Override
    public Action plays(Player myself, Board board, Player opponent) {
        // 1) Premier tour du round : poser un DOLMEN ADVERSE sur une case libre
        if (firstAction) {
            firstAction = false;
            Piece opponentDolmen = (opponent.getColor() == Constants.BLACK_PLAYER)
                ? Piece.DOLMEN_BLACK : Piece.DOLMEN_WHITE;
            return new Action(opponentDolmen, findEmptyCenter(board));
        }

        // 2) Logique principale (à implémenter)
        return chooseBestAction(myself, board, opponent);
    }

    @Override
    public void register(Game game)   { game.addRoundListener(this); }
    @Override
    public void unregister(Game game) { game.removeRoundListener(this); }

    @Override
    public void onRoundBegins(RoundEvent e) { this.firstAction = true; }
    @Override
    public void onRoundEnds(RoundEvent e)   { /* rien */ }

    private Point findEmptyCenter(Board b) { /* ... */ return new Point(0,0); }
    private Action chooseBestAction(Player me, Board b, Player opp) { /* ... */ return new Action(); }
}
```

**Trois pièges classiques** :
1. Pas de constructeur sans argument → la classe n'est pas chargée.
2. Renvoyer une `Action` invalide → skip + pénalité (-30 points) + risque de perdre le round.
3. Dépasser le timeout (60 s) → skip + pénalité. Coupez tôt votre recherche.

---

## 4. Choix algorithmique : Markovien vs A\* vs Greedy

Voici comment chaque approche se mappe sur ce jeu, et ce qu'elle exige concrètement.

### 4.1 Greedy (recommandé pour démarrer)

**Principe** : à chaque tour, énumérer tous les coups légaux, évaluer chacun par une **fonction heuristique**, jouer le meilleur. Aucun lookahead.

**Avantages** : simple, rapide, suffit largement à battre `RandomStrategy`.
**Inconvénients** : court-termiste, manque les pièges en 2-3 coups.

**Heuristique à implémenter (somme pondérée)** :
- `+W1` × longueur du plus long composant connexe de DOLMEN/CAPSTONE à soi (BFS depuis chaque case contrôlée).
- `+W2` × nombre de cases contrôlées sur les rangées/colonnes "presque complètes".
- `+W3` × distance Manhattan minimale entre la composante et le bord opposé (à minimiser).
- `−W4` × symétrique pour l'adversaire.
- `+W5` × bonus énorme si le coup gagne (`pathExists` retourne true).
- `−W6` × malus énorme si le coup permet à l'adversaire de gagner au tour suivant (1 ply de lookahead défensif).

**Pseudo-code** :
```
fonction plays(me, board, opp):
    bestScore = -INF
    bestAction = SKIP
    pour chaque action légale a:
        simBoard = board.clone(); applyAction(simBoard, a, me)
        si simBoard donne la victoire à me: retourner a
        score = evaluate(simBoard, me, opp)
        // 1 ply défensif:
        pour chaque réponse r de opp:
            si applyAction(simBoard.clone(), r, opp) gagne pour opp:
                score -= GROS_MALUS
                break
        si score > bestScore: bestScore = score; bestAction = a
    retourner bestAction
```

**Conseil** : commencez avec uniquement W1, W5, W6. Ajoutez le reste progressivement. Calibrez les poids par tournoi contre `RandomStrategy`.

### 4.2 A\* (le moins adapté, à utiliser ciblé)

A\* est un algorithme de **recherche de chemin** — il trouve la séquence optimale d'états entre un état initial et un but. Sur un jeu à deux joueurs, A\* "classique" ne fonctionne pas tel quel parce que l'adversaire change l'état entre vos coups.

**Deux usages valides** :

1. **A\* "monojoueur" sur le sous-problème de connexion** : ignorer temporairement l'adversaire, et demander "quelle est la séquence minimale de coups pour fermer mon chemin gauche-droite ?". Le résultat sert d'**heuristique** dans une stratégie Greedy ou MCTS : `h(board) = coût A* estimé pour fermer`. Très utile.
   - État : `Board` + pièces restantes en main.
   - Voisins : tous les coups légaux pour moi.
   - But : `board.pathExists(point, maCouleur) == true` pour au moins un point.
   - `g(n)` = nombre de coups joués depuis la racine.
   - `h(n)` = distance Manhattan minimale entre les composantes connexes de ma couleur et les bords opposés.

2. **A\* sur l'arbre minimax avec élagage** : appelé en pratique **MTD(f)** ou **alpha-beta avec table de transposition**. Si vous êtes à l'aise, c'est une amélioration sérieuse, mais ce n'est plus du A\* "pur".

**Si vous présentez un projet académique sur A\*** : utilisez l'option 1 (A* comme calculateur d'heuristique) et expliquez le choix. C'est rigoureux et défendable.

### 4.3 Markovien (MCTS recommandé)

Le jeu est un **MDP à deux joueurs** (Markov Decision Process) avec information complète. Deux familles :

#### 4.3.1 Programmation dynamique / Value Iteration
**Inadapté ici** : l'espace d'états est gigantesque (pile par case, jusqu'à 8×8 cases, pièces multiples). Vous ne calculerez jamais `V(s)` pour tous les états.

#### 4.3.2 Monte Carlo Tree Search (MCTS) — **le bon choix**

MCTS construit progressivement un arbre de recherche en simulant des **rollouts aléatoires** et en remontant les statistiques. Quatre étapes par itération :
1. **Sélection** : descendre dans l'arbre via UCB1 jusqu'à un nœud "à étendre".
2. **Expansion** : ajouter un enfant (un coup légal non encore essayé).
3. **Simulation** : depuis ce nouvel état, jouer aléatoirement (ou avec une "playout policy" légère) jusqu'à la fin du round.
4. **Backpropagation** : remonter le résultat (1 victoire / 0 défaite) le long du chemin.

**Formule UCB1** :
```
UCB(enfant) = w_i / n_i  +  C * sqrt(ln(N_parent) / n_i)
```
- `w_i` : victoires depuis cet enfant.
- `n_i` : visites de cet enfant.
- `N_parent` : visites du parent.
- `C` : constante d'exploration (commencer à `sqrt(2) ≈ 1.41`).

**Avantages sur BelegTak** :
- Pas besoin de fonction d'évaluation experte : les rollouts donnent l'estimation.
- Anytime : on coupe quand on veut (utile face au timeout de 60 s).
- Améliorable avec une "heavy playout" (rollouts non purement aléatoires : utiliser RandomStrategy comme baseline puis biaiser vers les coups capturants ou bloquants).

**Pseudo-code minimaliste** :
```
fonction plays(me, board, opp):
    racine = Node(board.clone(), null, joueurCourant = me)
    deadline = now() + 50_000ms  // ne JAMAIS aller au-delà du timeout
    tant que now() < deadline:
        feuille = select(racine)              // descente UCB1
        si !feuille.terminal:
            feuille = expand(feuille)         // ajoute un enfant
        result = rollout(feuille)             // partie aléatoire jusqu'à la fin
        backprop(feuille, result)             // remonte
    retourner argmax_visites(racine.children).action
```

**Pièges spécifiques à BelegTak pour MCTS** :
- Le **branching factor est ÉNORME** (toutes les positions × toutes les pièces × tous les amounts possibles pour les moves). Sur un plateau HUGE, ça explose.
  - **Solution** : restreindre la génération de coups (seulement les places adjacentes à des pièces existantes, seulement les amounts uniformes, etc.). C'est de la **progressive widening**.
- Les rollouts purement aléatoires sur ce jeu produisent beaucoup de skips → faible signal. Utilisez `RandomStrategy` comme politique de rollout, pas un random pur.
- Réutiliser l'arbre entre les tours (le sous-arbre correspondant à l'action jouée par l'adversaire devient la nouvelle racine).

---

## 5. Recommandations de mise en œuvre

### 5.1 Ordre de travail conseillé

1. **Battre RandomStrategy** : votre premier jalon. Si votre IA ne le bat pas en 8 rounds sur 10, c'est qu'elle n'a pas appris à compléter un chemin.
2. **Mode tournoi automatique** : écrivez un main de test qui fait jouer votre IA contre `RandomStrategy` sur 100 parties et imprime un taux de victoire. Sans ça, vous itérez à l'aveugle.
3. **Génération exhaustive des coups légaux** : tout repose là-dessus. Codez `List<Action> generateLegalActions(Board, Player)` et **testez-la** (vérifiez que toutes les actions générées sont validées par `board.canPlace` / `board.canMove`).
4. **Cloner et simuler** : écrivez `Board applyAction(Board, Action, Color)`. Vérifiez par symétrie que `applyAction` produit le même état que la vraie partie pour quelques scénarios.
5. **Évaluation** : codez et testez `int evaluate(Board, Color me)` indépendamment.
6. **Algorithme** : seulement maintenant choisissez Greedy/A\*/MCTS et branchez-le sur les briques précédentes.

### 5.2 Performance

- Le plateau HUGE (8×8) est piégeur : `generateLegalActions` peut produire des milliers d'actions par tour. Profilez tôt.
- **Évitez `clone()` dans des boucles serrées** : c'est coûteux (allocation + recopie de toutes les piles). Si possible, implémentez `applyAction` / `undoAction` en place.
- Surveillez le **garbage collector** : MCTS crée beaucoup de nœuds. Pré-allouez si vous montez en gamme.
- Le timeout effectif côté production devrait être ~50 s (garde de 10 s pour le retour Action + sérialisation/GC).

### 5.3 Gestion des cas particuliers

- **Premier tour de chaque round** : `Player.hasPiece(piece)` retourne `true` uniquement pour le DOLMEN **adverse**. Tester `myself.countPieces() == initialStones && tour == 0` n'est pas fiable ; utilisez plutôt un `RoundListener` qui pose un flag `firstAction = true`.
- **Tie-break** : si le round se termine sans chemin, le joueur avec **plus de DOLMEN au sommet des piles** gagne. Pondérer légèrement `countTopPieces(MY_DOLMEN)` dans l'éval donne un bonus gratuit.
- **MENHIR vs DOLMEN** : poser un MENHIR ne contribue PAS à votre chemin (cf. `pathExists` qui ignore les menhirs). Posez-en pour **bloquer l'adversaire**, pas pour avancer.
- **CAPSTONE** : seule pièce qui peut transformer un MENHIR en DOLMEN. Utilisation tactique : "écraser" un MENHIR adverse pour ouvrir votre chemin.

### 5.4 Tests et debug

- Écrivez quelques unit tests sur votre `generateLegalActions` et `applyAction` en vous inspirant de `src/test/java/.../BoardTest.java`.
- Branchez un `MisdesignListener` en mode debug qui affiche `e.getStackTrace()` sur `onException` — sinon les exceptions de votre Strategy disparaissent silencieusement (consommées par le `try/catch` dans `Game.executeTurn`).
- Loggez vos décisions : `(tour, action choisie, score d'éval, profondeur atteinte)` est suffisant pour rejouer une partie a posteriori.

---

## 6. Tableau de décision

| Critère | Greedy | A\* (heuristique) | MCTS |
|---------|--------|-------------------|------|
| Effort d'implémentation | Faible | Moyen | Élevé |
| Qualité finale | Moyenne | Bonne si bien combiné | Très bonne |
| Risque de timeout | Très faible | Faible | À surveiller |
| Adapté à ce jeu | Oui (baseline) | Comme module | Oui (cible) |
| Recommandé en premier ? | **Oui** | Non | Non |

**Stratégie gagnante** : commencer par Greedy + 1-ply défensif, valider qu'on bat Random à 95%+, puis passer à MCTS en réutilisant la fonction d'évaluation comme biais des rollouts.

---

## 7. Références dans le code

- Interface à implémenter : `src/main/java/be/belegkarnil/game/board/tak/strategy/Strategy.java`
- Adaptateur conseillé : `.../strategy/StrategyAdapter.java`
- Exemple complet (random) : `.../strategy/RandomStrategy.java`
- Moteur de jeu : `.../Game.java` (voir `executeTurn` pour comprendre le cycle)
- Règles du plateau : `.../Board.java` (voir `canMove`, `pathExists`)
- Détection des coups possibles : `Game.canPlay(Board, Player)` (statique, utile)
- Chargement des strategies : `BelegTak.loadStrategies("be.heh")` → placez vos classes dans un sous-package de `be.heh` pour qu'elles soient détectées automatiquement par l'UI.

Bonne implémentation.
