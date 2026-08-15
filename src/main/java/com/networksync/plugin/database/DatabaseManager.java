package com.networksync.plugin.database;

import com.networksync.plugin.NetworkSyncPlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Gère le pool de connexions MySQL/MariaDB (HikariCP) et l'initialisation du schéma.
 * Toutes les opérations SQL doivent être appelées depuis un thread async
 * (jamais depuis le thread principal du serveur).
 */
public class DatabaseManager {

    private final NetworkSyncPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(NetworkSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        FileConfiguration cfg = plugin.getConfig();

        String host = cfg.getString("mysql.host", "127.0.0.1");
        int port = cfg.getInt("mysql.port", 3306);
        String database = cfg.getString("mysql.database", "networksync");
        String username = cfg.getString("mysql.username", "networksync");
        String password = cfg.getString("mysql.password", "");
        boolean useSSL = cfg.getBoolean("mysql.useSSL", false);
        int poolSize = cfg.getInt("mysql.pool-size", 10);
        int timeout = cfg.getInt("mysql.connection-timeout-ms", 5000);

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + "&autoReconnect=true"
                + "&characterEncoding=UTF-8"
                + "&useUnicode=true";

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setConnectionTimeout(timeout);
        hikariConfig.setPoolName("NetworkSync-Pool");
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(hikariConfig);

        initSchema();
    }

    private void initSchema() {
        try (InputStream is = plugin.getResource("schema.sql")) {
            if (is == null) {
                plugin.getLogger().severe("schema.sql introuvable dans les ressources du plugin !");
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().startsWith("--") || line.isBlank()) continue;
                    sb.append(line).append("\n");
                }
            }

            String[] statements = sb.toString().split(";");
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                for (String raw : statements) {
                    String sql = raw.trim();
                    if (sql.isEmpty()) continue;
                    stmt.execute(sql);
                }
            }
            plugin.getLogger().info("Schéma MySQL initialisé/vérifié avec succès.");
        } catch (IOException | SQLException e) {
            plugin.getLogger().severe("Erreur lors de l'initialisation du schéma MySQL : " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }
}
