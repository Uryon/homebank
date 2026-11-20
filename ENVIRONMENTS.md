# Environnements — HomeBank

Le backend dispose maintenant de **deux environnements distincts**, pilotés par les profils Spring :

## 1. Environnement "réel" (par défaut — celui du `.jar`)

C'est l'environnement inchangé, celui que tu utilises déjà : base **PostgreSQL**, avec tes vraies données.

```bash
cd backend
mvn clean package
java -jar target/homebank-1.0.0.jar
```

Aucun profil à préciser : c'est le comportement par défaut (`application.properties`). La configuration
de connexion (`DB_URL`, `DB_USER`, `DB_PASSWORD`) reste celle que tu as déjà en place.

## 2. Environnement de test (profil `test`, base H2 en mémoire + données de démo)

Un second environnement, totalement isolé de ta vraie base, avec des **données de démonstration**
générées automatiquement à chaque démarrage (`TestDataSeeder`) : quelques comptes, une hiérarchie de
catégories, des tiers, quelques dizaines de transactions réparties sur les derniers mois (dont certaines
"en attente" et certaines marquées "dépense périodique"), un virement interne et un prêt immobilier
d'exemple. Le détail exact est affiché dans le journal au démarrage (« Données de démonstration
chargées : … »).

Ce profil utilise une base **H2 en mémoire** : elle est recréée vierge à chaque démarrage, son
schéma est créé par les mêmes scripts Flyway que la vraie base, puis elle est repeuplée par
`TestDataSeeder`. Rien de ce qui s'y passe n'affecte jamais ta base
PostgreSQL réelle.

### Lancer ce profil depuis IntelliJ

Configuration composée **« All TEST »** (sélecteur de configurations, en haut à droite) : elle lance
ensemble `Back` (`mvn spring-boot:run` avec le profil `test`, port 8080) et `Front` (`npm run dev`,
http://127.0.0.1:5173). Son pendant **« All PROD »** lance `Back PROD` (vraie base) et le même frontend.
Ces configurations sont enregistrées dans les réglages locaux d'IntelliJ (`.idea/workspace.xml`, non
versionné).

Le raccourci `HomeBank.lnk` (voir README) utilise toujours la vraie base : arrêter « All TEST » avant
de s'en servir, il signale sinon qu'un serveur de test occupe le port 8080.

### Lancer ce profil en ligne de commande (alternative)

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

### Vérifier/consulter les données de test

La console H2 est activée sur ce profil : http://localhost:8080/h2-console
JDBC URL à utiliser : `jdbc:h2:mem:homebank_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=USER`
Utilisateur : `sa`, mot de passe : (vide).

## Résumé

| | Environnement par défaut (`.jar`) | Profil `test` |
|---|---|---|
| Commande | `HomeBank.lnk`, « All PROD », ou `java -jar target/homebank-1.0.0.jar` | « All TEST », ou `-Dspring-boot.run.profiles=test` |
| Base de données | PostgreSQL (tes vraies données) | H2 en mémoire (recréée à chaque démarrage) |
| Données | Les tiennes, persistantes | Jeu de démonstration généré automatiquement |
| Fichier de conf | `application.properties` | `application-test.properties` |
| Impact sur tes vraies données | — | Aucun, totalement isolé |
