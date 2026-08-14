# HomeBank Clone

Application de gestion de finances personnelles inspirée de [HomeBank](http://www.homebank.free.fr/),
avec un backend **Java / Spring Boot** et un frontend **Vue.js 3**.

## Fonctionnalités incluses (v1)

- Gestion des **comptes** (courant, épargne, espèces, carte de crédit, placement) avec calcul du solde courant
- Gestion des **transactions** (CRUD complet : date, tiers, montant, mémo, mode de paiement, tags)
- **Catégories et sous-catégories** (hiérarchie à deux niveaux, comme dans HomeBank)
- **Rapports et graphiques** : répartition des dépenses/revenus par catégorie, évolution du solde dans le temps
- **Import CSV/QIF** :
  - CSV au format d'export HomeBank (`date;paymode;info;payee;memo;amount;category;tags`)
  - QIF (Quicken Interchange Format)

Non inclus dans cette v1 (pistes d'évolution) : budgets mensuels, opérations planifiées/récurrentes,
virements inter-comptes automatiques, multi-utilisateurs.

## Architecture

```
homebank-clone/
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

L'API démarre sur **http://localhost:8080**. Vérifiez avec :

```bash
curl http://localhost:8080/api/accounts
```

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

## Format d'import QIF

Champs reconnus : `D` (date), `T`/`U` (montant), `P` (tiers), `M` (mémo), `L` (catégorie), `^` (fin d'enregistrement).

## Build de production

```bash
# Backend : génère un jar exécutable
cd backend && mvn clean package
java -jar target/homebank-clone-1.0.0.jar

# Frontend : génère les fichiers statiques dans frontend/dist
cd frontend && npm run build
```

Pour la prod, servez `frontend/dist` derrière un reverse proxy (nginx, etc.) qui redirige
`/api/*` vers le backend Spring Boot, ou packagez le build Vue dans
`backend/src/main/resources/static` pour tout servir depuis le même jar.
