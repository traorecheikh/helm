# Publier Helm

Ce dépôt n'a pas de remote et ne doit jamais être poussé tel quel : historique et fichiers non commités sensibles.

Publication : un export nettoyé, poussé sur `traorecheikh/helm` (public) en commit unique.

```sh
sh /Users/cheikh/Playground/stars/helm-publish.sh "message"
```

Le script exporte l'arbre de travail vers `/tmp/helm-export` (`helm-export.sh` exclut `design`, maquettes, `node_modules`, `dist`, `build` et expurge les faux secrets des tests), puis synchronise `/tmp/helm-push` et pousse.

Avant de pousser : synchroniser sans commiter et lire `git status --short` dans `/tmp/helm-push`. Ne pas passer `HELM_SKIP=".github"` : cela supprime `pages.yml` du dépôt public.

Release : `sh /Users/cheikh/Playground/stars/helm-release.sh <version>` (monter `versionName`/`versionCode` dans `android/app/build.gradle.kts` d'abord).

Interdit : `git remote add` ici, `git push` depuis ce dépôt, pousser sans avoir vérifié le diff de l'export.
