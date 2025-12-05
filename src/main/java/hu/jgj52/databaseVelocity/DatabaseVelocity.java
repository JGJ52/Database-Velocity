package hu.jgj52.databaseVelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import org.slf4j.Logger;

import java.util.Map;

public class DatabaseVelocity {

    public static PostgreSQL postgres;
    public static Redis redis;

    private final Logger logger;

    @Inject
    public DatabaseVelocity(Logger logger) {
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        Config cfg = new Config("databasevelocity");
        Map<String, Object> config = cfg.getConfig();
        Map<String, Object> pgcfg = (Map<String, Object>) config.get("postgresql");
        Map<String, Object> rscfg = (Map<String, Object>) config.get("redis");
        try {
            postgres = new PostgreSQL(
                    pgcfg.get("host").toString(),
                    Integer.parseInt(pgcfg.get("port").toString()),
                    pgcfg.get("database").toString(),
                    pgcfg.get("username").toString(),
                    pgcfg.get("password").toString()
            );
            logger.info("PostgreSQL successfully connected!");
        } catch (Exception e) {
            logger.warn("PostgreSQL failed to connect!");
        }
        try {
            redis = new Redis(
                    rscfg.get("host").toString(),
                    Integer.parseInt(rscfg.get("port").toString()),
                    rscfg.get("password").toString()
            );
            logger.info("Redis successfully connected!");
        } catch (Exception e) {
            logger.warn("Redis failed to connect!");
        }
    }
}
