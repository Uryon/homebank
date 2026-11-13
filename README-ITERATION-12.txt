HomeBank — Itération 12 : protection contre la suppression accidentelle d'un compte utilisé

Constat
Le modèle Compte configure ses transactions avec cascade = ALL et orphanRemoval = true.
La suppression directe d'un compte pouvait donc supprimer également tout son historique.

Correction
- ServiceCompte.delete vérifie maintenant l'existence du compte.
- Si le compte contient une ou plusieurs transactions, la suppression est refusée.
- L'API renvoie HTTP 409 avec un message invitant à archiver le compte pour conserver l'historique.
- Un compte vide reste supprimable.
- Deux tests unitaires couvrent les deux scénarios.

Aucune donnée ni règle de calcul n'est modifiée. Aucun changement de schéma SQL.

Installation
Extraire le contenu de l'archive à la racine du projet HomeBank en remplaçant les fichiers existants.

Validation
Les tests Maven n'ont pas pu être exécutés dans l'environnement de préparation (commande mvn indisponible).
Depuis le dossier backend du projet : mvn test

À tester manuellement :
1. Supprimer un compte vide : la suppression doit fonctionner.
2. Tenter de supprimer un compte qui possède des transactions : l'API doit renvoyer 409 et l'historique doit rester intact.
3. Archiver ce compte : l'opération doit rester possible.
