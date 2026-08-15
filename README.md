# NetworkSync

Plugin Paper de synchronisation multi-serveurs : inventaire (vanilla + items custom/Nexo/NBT),
armure, main secondaire, EnderChest, XP/niveau, vie/faim, effets, et économie (Vault/EssentialsX),
avec sessions anti-double-connexion, backups automatiques, snapshots, rollback et anti-duplication
par revision.

## Build

Prérequis : JDK 21, Maven 3.9+, connexion internet (dépôts PaperMC, JitPack, EssentialsX, Maven Central).

```bash
mvn clean package
```

Le jar final est généré dans `target/NetworkSync-1.0.0.jar`.

> Remarque : ce projet n'a pas pu être compilé dans cet environnement car l'accès réseau est
> restreint (les dépôts PaperMC/Maven Central ne sont pas joignables ici). Le code a été écrit
> et relu attentivement contre l'API Paper 1.21 / Vault, mais il est recommandé de lancer
> `mvn clean package` en local avant mise en production, et de corriger d'éventuels ajustements
> mineurs de version d'API si tu cibles une build Paper différente de 1.21.1.

## Installation

1. Place `NetworkSync-1.0.0.jar` dans le dossier `plugins/` de **chaque** serveur Paper du réseau.
2. Installe `Vault` et `EssentialsX` sur chaque serveur (softdepend, mais nécessaires pour la sync économie).
3. Démarre un serveur une première fois pour générer `plugins/NetworkSync/config.yml`.
4. Configure `mysql.*` (mêmes identifiants MySQL/MariaDB partagés par tous les serveurs) et
   `server-id` (unique par serveur, ex: `SMP-1`, `SMP-2`...).
5. Redémarre. Le schéma (`schema.sql`) est créé/vérifié automatiquement au démarrage.

## Configuration clé (config.yml)

- `sync.flush-delay-ms` : fenêtre de micro-batching pour les écritures temps réel (défaut 50ms).
- `backups.interval-seconds` : intervalle des snapshots automatiques (défaut 30s).
- `backups.only-if-changed` : ne sauvegarde que les joueurs "dirty" depuis le dernier passage.
- `backups.keep` : nombre de backups conservés par joueur (rotation automatique).
- `session.lock-timeout-seconds` : délai avant qu'un verrou de session mort (crash serveur) expire.

## Commandes

```
/sync backup <joueur>              - crée un backup manuel immédiat
/sync backups <joueur>             - liste les backups disponibles
/sync restore <joueur> <id>        - restaure un backup précis (crée automatiquement un backup "avant restauration")
/sync rollback <joueur>            - revient au backup précédent le plus récent
/sync economy-history <joueur>     - affiche l'historique des transactions
/sync status [joueur]              - statut général ou d'un joueur (verrou, cache, revision)
```

Permissions : `sync.admin`, `sync.backup`, `sync.restore`, `sync.view` (tous en `op` par défaut).

## Architecture

- **Sync temps réel** : chaque action (clic inventaire, drop, craft, XP, etc.) marque le joueur
  "dirty" et incrémente sa `revision`. Un flush asynchrone regroupe les écritures MySQL toutes les
  `flush-delay-ms` (micro-batching), pour éviter une requête par clic.
- **Changement de serveur / déconnexion** : toujours un flush immédiat (bypass du micro-batching),
  jamais d'attente des 30 secondes.
- **Backups périodiques** : toutes les `interval-seconds`, uniquement pour les joueurs modifiés
  depuis le dernier passage (`SyncManager#consumeDirtySinceBackup`).
- **Anti-duplication** : chaque écriture porte une `revision` ; la DB refuse toute écriture dont la
  revision n'est pas strictement supérieure à celle déjà connue (protection en transaction SQL avec
  `SELECT ... FOR UPDATE`).
- **Anti double-connexion** : table `player_lock` avec `session_id` + `last_heartbeat`. Un
  `AsyncPlayerPreLoginEvent` refuse la connexion si un verrou actif existe ailleurs ; le heartbeat
  périodique permet de détecter un crash et de libérer automatiquement le verrou après timeout.
- **Économie** : jamais d'accès direct à la DB EssentialsX. Toujours via l'API Vault
  (`Economy#getBalance/depositPlayer/withdrawPlayer`), avec journalisation indépendante dans la
  table `transactions`.
- **Items custom (Nexo, etc.)** : sérialisation NBT complète via `ItemStack#serializeAsBytes()`
  (Paper), pas de réduction à `Material + quantité`. Compression GZIP avant stockage en BLOB.

## Structure du projet

```
NetworkSync/
├── pom.xml
├── schema.sql (aussi copié dans les resources du jar)
└── src/main/
    ├── java/com/networksync/plugin/
    │   ├── NetworkSyncPlugin.java        (classe principale)
    │   ├── database/
    │   │   ├── DatabaseManager.java      (pool HikariCP + init schéma)
    │   │   └── PlayerDataDAO.java        (toutes les requêtes SQL)
    │   ├── model/
    │   │   ├── PlayerData.java
    │   │   └── BackupEntry.java
    │   ├── sync/SyncManager.java         (micro-batching temps réel)
    │   ├── backup/BackupManager.java     (snapshots 30s + rotation)
    │   ├── session/SessionManager.java   (verrou anti double-connexion)
    │   ├── economy/EconomyManager.java   (intégration Vault/EssentialsX)
    │   ├── listener/
    │   │   ├── PlayerConnectionListener.java
    │   │   ├── InventoryListener.java
    │   │   └── ServerSwitchListener.java
    │   ├── command/SyncCommand.java
    │   └── util/ItemSerializer.java      (NBT complet + compression)
    └── resources/
        ├── plugin.yml
        ├── config.yml
        └── schema.sql
```
