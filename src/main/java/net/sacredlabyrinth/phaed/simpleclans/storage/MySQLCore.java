package net.sacredlabyrinth.phaed.simpleclans.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.sacredlabyrinth.phaed.simpleclans.SimpleClans;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * @author cc_madelg
 */
public class MySQLCore implements DBCore {

    private final Logger log;
    private HikariDataSource dataSource;

    /**
     * @param host     The host
     * @param database The database
     * @param username The username
     * @param password The password
     */
    public MySQLCore(String host, String database, int port, String username, String password) {
        this.log = SimpleClans.getInstance().getLogger();
        try {
            HikariConfig config = new HikariConfig();
            config.setPoolName("SimpleClans-MySQL");
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=utf-8&useSSL=false");
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(10000);
            config.setMaxLifetime(1800000);
            dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            log.severe("Failed to initialize MySQL connection pool! " + e.getMessage());
        }
    }

    @Override
    public Connection getConnection() {
        if (dataSource == null) {
            return null;
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            log.severe("Failed to obtain a connection from the pool! " + e.getMessage());
            return null;
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

}
