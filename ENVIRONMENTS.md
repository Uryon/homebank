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
générées automatiquement à chaque démarrage : 3 comptes, une hiérarchie de catégories, quelques tiers,
~50 transactions réparties sur les 5 derniers mois (dont certaines "en attente" et certaines marquées
"dépense mensuelle"), un virement interne, et un prêt immobilier d'exemple.

Ce profil utilise une base **H2 en mémoire** (`create-drop`) : elle est recréée vierge à chaque
démarrage puis repeuplée par `TestDataSeeder`. Rien de ce qui s'y passe n'affecte jamais ta base
PostgreSQL réelle.

### Lancer ce profil depuis IntelliJ

Une configuration de lancement `.run/HomeBank (profil test).run.xml` est fournie et devrait apparaître
automatiquement dans le sélecteur de configurations d'IntelliJ (menu déroulant en haut à droite).

**Si elle n'apparaît pas ou indique une erreur de module** (le nom du module Maven peut différer selon
la façon dont tu as importé le projet) :

1. `Run` → `Edit Configurations…`
2. `+` → `Spring Boot`
3. Renseigne :
   - **Main class** : `com.homebank.HomebankApplication`
   - **Active profiles** : `test`
   - **Use classpath of module** : le module du backend (celui qui contient `pom.xml`)
4. Lance cette configuration au lieu de la configuration par défaut.

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
| Commande | `java -jar target/homebank-1.0.0.jar` | Run config IntelliJ, ou `-Dspring-boot.run.profiles=test` |
| Base de données | PostgreSQL (tes vraies données) | H2 en mémoire (recréée à chaque démarrage) |
| Données | Les tiennes, persistantes | Jeu de démonstration généré automatiquement |
| Fichier de conf | `application.properties` | `application-test.properties` |
| Impact sur tes vraies données | — | Aucun, totalement isolé |
