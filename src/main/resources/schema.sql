-- =========================================
--  NetworkSync - Schéma MySQL / MariaDB
-- =========================================

CREATE TABLE IF NOT EXISTS players (
    uuid            CHAR(36)     NOT NULL PRIMARY KEY,
    username        VARCHAR(32)  NOT NULL,
    last_server     VARCHAR(64)  NOT NULL DEFAULT '',
    last_seen       BIGINT       NOT NULL DEFAULT 0,
    revision        BIGINT       NOT NULL DEFAULT 0,
    version         INT          NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventories (
    uuid              CHAR(36)     NOT NULL PRIMARY KEY,
    inventory_data    LONGBLOB,
    armor_data        LONGBLOB,
    offhand_data      LONGBLOB,
    enderchest_data   LONGBLOB,
    xp                INT          NOT NULL DEFAULT 0,
    level             INT          NOT NULL DEFAULT 0,
    health            DOUBLE       NOT NULL DEFAULT 20,
    food              INT          NOT NULL DEFAULT 20,
    saturation        FLOAT        NOT NULL DEFAULT 5,
    potion_effects    BLOB,
    revision          BIGINT       NOT NULL DEFAULT 0,
    server            VARCHAR(64)  NOT NULL DEFAULT '',
    updated_at        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_inventories_player FOREIGN KEY (uuid) REFERENCES players(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS economy (
    uuid        CHAR(36)    NOT NULL PRIMARY KEY,
    balance     DECIMAL(20,2) NOT NULL DEFAULT 0,
    revision    BIGINT      NOT NULL DEFAULT 0,
    updated_at  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_economy_player FOREIGN KEY (uuid) REFERENCES players(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory_backups (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    uuid              CHAR(36)     NOT NULL,
    server            VARCHAR(64)  NOT NULL,
    inventory_data    LONGBLOB,
    armor_data        LONGBLOB,
    offhand_data      LONGBLOB,
    enderchest_data   LONGBLOB,
    xp                INT          NOT NULL DEFAULT 0,
    level             INT          NOT NULL DEFAULT 0,
    balance           DECIMAL(20,2) NOT NULL DEFAULT 0,
    reason            VARCHAR(64)  NOT NULL DEFAULT 'PERIODIC',
    created_at        BIGINT       NOT NULL,
    INDEX idx_backups_uuid_created (uuid, created_at),
    CONSTRAINT fk_backups_player FOREIGN KEY (uuid) REFERENCES players(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS transactions (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    uuid        CHAR(36)     NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    amount      DECIMAL(20,2) NOT NULL,
    balance_before DECIMAL(20,2) NOT NULL,
    balance_after  DECIMAL(20,2) NOT NULL,
    server      VARCHAR(64)  NOT NULL,
    reason      VARCHAR(128) NOT NULL DEFAULT '',
    timestamp   BIGINT       NOT NULL,
    INDEX idx_tx_uuid_time (uuid, timestamp),
    CONSTRAINT fk_tx_player FOREIGN KEY (uuid) REFERENCES players(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_lock (
    uuid            CHAR(36)     NOT NULL PRIMARY KEY,
    server_id       VARCHAR(64)  NOT NULL,
    session_id      VARCHAR(64)  NOT NULL,
    last_heartbeat  BIGINT       NOT NULL,
    locked_at       BIGINT       NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
