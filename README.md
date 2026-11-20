# HomeBank

Application de gestion de finances personnelles inspirée de [HomeBank](http://www.homebank.free.fr/),
avec un backend **Java / Spring Boot** et un frontend **Vue.js 3**.

## Fonctionnalités

- **Comptes** (courant, épargne, espèces, carte de crédit, placement) : solde courant, opérations
  à traiter par compte, archivage,
  virements entre comptes, rapprochement avec un relevé et ajustement de solde
- **Transactions** : saisie et modification directement dans la liste, solde après chaque opération
  (liste filtrée sur un compte), duplication (bouton ou
  touche `D`, nouvelle transaction pré-remplie datée du jour), division en deux parties,
  modification en masse, statut « à traiter » / validée, dépenses périodiques et transactions
  exceptionnelles (exclues des tableaux de bord), avertissement en cas de double saisie
- **Annuler la dernière action** (bandeau de 10 s, ou `Ctrl+Z`) sur toutes les pages : création,
  modification, suppression, division, virement, archivage, rapprochement, ajustement de solde,
  suggestions périodiques et doublons ignorés, catégorisation automatique des tiers, fusions de tiers
  et de catégories
- **Recherche** : tiers, catégorie, mémo, libellé du relevé importé ou montant, plusieurs critères séparés par `;`,
  correspondance exacte entre guillemets, comparaison de montant (`> 100`, `>= 100`, `< -20`, `<= -20`,
  `= 1 042,50`)
- **Catégories et sous-catégories** (deux niveaux) : catégories masquées, catégories annuelles
  (lissées sur 12 mois), fusion, accès direct aux transactions de chaque catégorie
- **Tiers** : reconnaissance par motif à l'import, catégorie par défaut, catégorisation automatique, fusion,
  filtre des tiers inutilisés et suppression groupée ; le nombre de transactions ouvre leur liste ;
  nettoyage avec aperçu des faux tiers copiés d'un libellé de virement ou d'ajustement
- **Prêts** : échéancier d'amortissement, capital restant dû, intérêts totaux
- **Tableau de bord** : évolution et prévision du solde, épargne mensuelle, répartition des dépenses,
  analyse mensuelle par catégorie avec comparaison aux mêmes 12 mois de l'année précédente (colonnes
  « 12 mois préc. » et « Évol. », ligne « vs … » des cartes du haut), export PDF
- **Export CSV** des transactions affichées (filtres et recherche compris), au format d'import
- **Import CSV/QIF** avec détection des doublons et bouton « Annuler cet import » (les transactions
  créées sont supprimées, le relevé peut être réimporté) :
  - CSV au format d'export HomeBank (`date;paymode;info;payee;memo;amount;category;tags`, tags ignorés)
  - CSV brut téléchargé depuis une banque, colonnes reconnues par leur nom
    (`Date;Libellé;Montant` ou `Date;Date de valeur;Débit;Crédit;Libellé;Solde`, par exemple)
  - QIF (Quicken Interchange Format)
  - Encodages reconnus : UTF-8, Windows-1252 et UTF-16 (avec sa marque en début de fichier)
- Page **Doublons** et suggestions de **transactions périodiques**
- Palette de commandes (`Ctrl+K`), raccourcis clavier (`?`), thèmes clair/sombre

Non inclus (pistes d'évolution) : budgets mensuels, opérations planifiées générées automatiquement,
multi-devises dans les virements, multi-utilisateurs.

Deux comptes ne peuvent pas porter le même nom (casse ignorée).
Une transaction saisie ne peut pas avoir un montant nul (une ligne importée à 0 reste modifiable).
Les champs texte (noms, mémos, notes) sont limités à 255 caractères ; à l'import, un mémo ou un
libellé plus long est tronqué plutôt que de faire rejeter la ligne.

## Architecture

```
homebank/
├── backend/     Spring Boot 3 (Java 21), API REST, PostgreSQL via JPA/Hibernate
└── frontend/    Vue 3 + Vite, Chart.js pour les graphiques
```

## Prérequis

- Java 21+ et Maven (ou utilisez le wrapper `mvnw` si vous en ajoutez un)
- Node.js 18+ et npm
- PostgreSQL 14+ (local ou distant)

## 1. Préparer la base de données

```sql
CREATE DATABASE homebank;
CREATE USER homebank WITH PASSWORD 'homebank';
GRANT ALL PRIVILEGES ON DATABASE homebank TO homebank;
```

Le schéma des tables est créé automatiquement au démarrage du backend
(`spring.jpa.hibernate.ddl-auto=update` dans `application.properties`).

Vous pouvez surcharger la connexion via variables d'environnement :

```bash
export DB_URL=jdbc:postgresql://localhost:5432/homebank
export DB_USER=homebank
export DB_PASSWORD=homebank
```

## 2. Lancer le backend

```bash
cd backend
mvn spring-boot:run
```

L'API démarre sur **http://127.0.0.1:8080**. Vérifiez avec :

```bash
curl http://127.0.0.1:8080/api/accounts
```

Elle n'écoute que sur la boucle locale : l'API n'a pas d'authentification, et un autre appareil
du réseau ne doit pas pouvoir lire les comptes. Elle refuse aussi toute requête dont l'en-tête
`Host` n'est pas local, pour qu'un site web ne puisse pas l'atteindre par « DNS rebinding ». Pour
l'ouvrir volontairement au réseau, définir `SERVER_ADDRESS=0.0.0.0` avant de lancer le backend
(ce contrôle est alors désactivé).

## 3. Lancer le frontend

```bash
cd frontend
npm install
npm run dev
```

L'application est disponible sur **http://localhost:5173** (le proxy Vite redirige
automatiquement `/api/*` vers le backend sur le port 8080).

## 4. Premiers pas dans l'application

1. Onglet **Comptes** : créez au moins un compte (nom, type, solde initial).
2. Onglet **Catégories** : créez vos catégories et sous-catégories (ex : "Alimentation" > "Supermarché").
3. Onglet **Transactions** : ajoutez vos opérations, ou passez par **Import CSV/QIF**
   pour charger un relevé exporté depuis HomeBank ou votre banque.
4. Onglet **Rapports** : filtrez par période et par compte pour visualiser vos dépenses/revenus.

## Format d'import CSV (HomeBank)

Chaque ligne (séparateur `;`) :

```
date;paymode;info;payee;memo;amount;category;tags
14-08-26;1;;Supermarché Dupont;Courses semaine;-54.30;Alimentation:Supermarché;
```

- `date` : `jj-mm-aa`, `jj-mm-aaaa` ou `jj/mm/aaaa`
- `amount` : négatif pour une dépense, positif pour un revenu (`,` ou `.` acceptés comme séparateur décimal)
- `category` : `Parent:Enfant` pour cibler une sous-catégorie (créée automatiquement si elle n'existe pas)

Le séparateur est détecté sur la première ligne : `;` par défaut, sinon tabulation ou `,` (montants
à virgule décimale alors entre guillemets). Le bouton **Exporter en CSV** de la page Transactions
produit ce même format (colonne `info` = nom du compte), réimportable tel quel.

## Format d'import QIF

Champs reconnus : `D` (date), `T`/`U` (montant), `P` (tiers), `M` (mémo), `L` (catégorie), `^` (fin d'enregistrement),
dans les sections `!Type:Bank`, `!Type:CCard` (carte de crédit), `!Type:Cash`, `!Type:Oth A` et `!Type:Oth L`
(les autres sections, catégories ou placements, sont ignorées).

## Build de production

```bash
# Backend : génère un jar exécutable
cd backend && mvn clean package
java -jar target/homebank-1.0.0.jar

# Frontend : génère les fichiers statiques dans frontend/dist
cd frontend && npm run build
```

Pour la prod, servez `frontend/dist` derrière un reverse proxy (nginx, etc.) qui redirige
`/api/*` vers le backend Spring Boot, ou packagez le build Vue dans
`backend/src/main/resources/static` pour tout servir depuis le même jar.

## Lancement par raccourci (`HomeBank.lnk` → `HomeBank.vbs`)

Le raccourci ouvre HomeBank dans une fenêtre Edge dédiée et gère les serveurs tout seul :

- **Fenêtre immédiate** : elle s'ouvre dès le clic, sur la page d'attente
  (`frontend/public/demarrage.html`) lue directement comme fichier, sans attendre aucun serveur ni
  aucune compilation. La page passe sur Vite dès qu'il répond, puis sur l'appli dès que le serveur
  est prêt. Si le lancement échoue (PostgreSQL arrêté, port occupé…), la fenêtre se referme et un
  message explique pourquoi.
- **Version de production** : le frontend est servi compilé (Vite preview avec
  `vite.preview.config.mjs`, environ deux fois plus rapide à l'usage que `npm run dev`), recompilé
  (`vite build`) quand son code a changé, en parallèle de la compilation du backend : chaque serveur
  démarre dès la fin de la sienne ; en cas d'échec de compilation, repli sur `npm run dev`.
  Les configurations IntelliJ gardent le serveur de dev.
- **Démarrage rapide** : le serveur est lancé en premier, en parallèle de Vite et de la fenêtre, depuis
  ses classes compilées et ses bibliothèques extraites du jar (pas le jar exécutable, ~2 s plus lent),
  avec une archive CDS de la JVM (classes des bibliothèques préchargées), le compilateur rapide seul et
  ses dépôts préparés en parallèle. Le tout dans `backend/target/demarrage-rapide/`. L'archive ne
  dépend que des bibliothèques : elle reste valable quand le code change. Elle est préparée à la
  fermeture (~40 s, fenêtre déjà fermée) seulement s'il n'y en a pas, si le `pom.xml` a changé ou si
  Java l'a rejetée (mise à jour de Java : rejet noté dans `logs/cds.log`).
- **Toujours à jour** : si le code du backend a changé depuis le dernier lancement, les classes déjà
  compilées par IntelliJ ou `mvn` (`backend/target/classes`) sont reprises telles quelles quand elles
  sont à jour (chaque source a sa classe plus récente qu'elle, aucune classe sans source) ; sinon il
  est compilé en arrière-plan, pendant la compilation du frontend et l'ouverture de la fenêtre : par `javac`
  directement (~3,5 s, classes identiques à celles de Maven, avec le classpath que Maven a noté dans
  `backend/target/classpath-compilation.txt`), sinon par `mvn -Dmaven.test.skip=true compile`
  (~6,5 s), ou `package` si le `pom.xml` a changé. Le serveur lit une copie des
  classes compilées, à l'abri d'une compilation IntelliJ ou `mvn` en cours. En cas d'échec, l'ancienne
  version est utilisée et un message l'indique.
- **Compatible IntelliJ** : un backend de production ou un Vite déjà lancés (ex : « All PROD ») sont
  réutilisés et ne sont pas arrêtés à la fermeture. Un backend de **test** (« All TEST », données de
  démonstration) est refusé avec un message, pour ne jamais confondre les deux.
- **Une seule instance** : relancer le raccourci ramène la fenêtre existante au premier plan, restaurée
  si elle était réduite (`HomeBank-fenetre.ps1`), y compris sur un double-clic (verrou
  `logs/lanceur.lock`). Dans l'appli, une seule fenêtre est active à la fois : une deuxième (Ctrl+N,
  lien ouvert dans une nouvelle fenêtre…) affiche « HomeBank est déjà ouvert » sans rien charger,
  prend le relais dès que l'autre est fermée, ou tout de suite avec « Utiliser cette fenêtre »
  (`frontend/src/services/singleWindow.js`).
- **Relance pendant une fermeture** : rouvrir juste après avoir fermé (pendant l'export PDF) ouvre la
  fenêtre tout de suite ; la fermeture en cours finit son export puis laisse ses serveurs au nouveau
  lancement au lieu de les arrêter (appli affichée en ~2 s, sans redémarrage). Un second clic pendant
  cette attente ramène la même fenêtre au lieu d'en ouvrir une autre ; une fenêtre fermée pendant
  l'attente abandonne le lancement. Si Edge n'affiche pas la fenêtre (ouverture confiée à une
  fenêtre en train de se fermer), elle est rouverte une fois.
- **Serveur lancé en premier** : il démarre avant même la vérification des ports (requête WMI de 0,3 à
  0,7 s). Si un autre serveur occupait déjà le port (IntelliJ), celui du raccourci est annulé et
  l'autre réutilisé.
- **À la fermeture** : export PDF du tableau de bord, puis arrêt des seuls serveurs démarrés par le raccourci.

En cas de souci, consulter `logs/lanceur.log` (journal horodaté de chaque lancement), puis
`logs/backend.log`, `logs/frontend.log`, `logs/build.log`, `logs/build-frontend.log`,
`logs/extraction-jar.log`, `logs/demarrage-rapide.log` et `logs/cds.log`.
