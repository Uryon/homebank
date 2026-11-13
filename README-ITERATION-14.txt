HomeBank - Patch 14
==================

Objectif : garder les deux transactions d'un virement synchronisees lors du rapprochement.

Modification :
- ServiceTransaction.toggleValidated est maintenant transactionnel.
- Lorsqu'on valide ou devalide un cote d'un virement, le statut validated est applique aussi a la transaction jumelle.
- Le comportement d'une transaction normale reste inchange.
- Deux tests unitaires couvrent le virement (bascule simultanee des deux cotes) et la transaction normale.

Pourquoi : un virement interne est une operation unique representee par un debit et un credit. Les rapprocher independamment pouvait creer un etat incoherent et compliquer le nettoyage des transactions en attente.

Application : extraire le contenu du ZIP a la racine du projet HomeBank, en conservant les chemins backend/.

Validation : tests Maven non executes dans l'environnement de generation (Maven n'est pas disponible). Apres application, lancer depuis backend :
  mvn test
