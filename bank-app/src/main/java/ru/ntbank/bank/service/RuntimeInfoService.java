package ru.ntbank.bank.service;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * Живые настройки пула соединений к БД и пула потоков Tomcat.
 * Через эти методы режим «Дефекты» меняет их на лету.
 */
@Service
public class RuntimeInfoService {

    private final DataSource dataSource;
    private final ServletWebServerApplicationContext webServerContext;

    /** Штатные значения «нижних» границ пулов — чтобы вернуть их при восстановлении. */
    private volatile int defaultMinIdle = -1;
    private volatile int defaultMinSpareThreads = -1;

    public RuntimeInfoService(DataSource dataSource,
                              ServletWebServerApplicationContext webServerContext) {
        this.dataSource = dataSource;
        this.webServerContext = webServerContext;
    }

    // ------------------------------------------------------------------ HikariCP

    public int hikariMaxPoolSize() {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getMaximumPoolSize();
        }
        return -1;
    }

    /**
     * Меняет максимум пула соединений.
     * <p>
     * Одного setMaximumPoolSize мало: HikariCP не закрывает уже открытые соединения,
     * и при уменьшении максимума с 30 до 2 все 30 продолжали бы выдаваться ещё до 10 минут
     * (idle-timeout). Поэтому после изменения старые соединения вытесняются: свободные
     * закрываются сразу, занятые — как только их вернут в пул.
     */
    public synchronized void setHikariMaxPoolSize(int size) {
        if (!(dataSource instanceof HikariDataSource hikari) || size <= 0) {
            return;
        }
        if (defaultMinIdle < 0) {
            defaultMinIdle = hikari.getMinimumIdle();
        }
        int minIdle = Math.min(defaultMinIdle, size);
        if (size < hikari.getMaximumPoolSize()) {
            hikari.setMinimumIdle(minIdle);
            hikari.setMaximumPoolSize(size);
        } else {
            hikari.setMaximumPoolSize(size);
            hikari.setMinimumIdle(minIdle);
        }
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool != null) {
            pool.softEvictConnections();
        }
    }

    // ------------------------------------------------------------------ Tomcat

    public int tomcatMaxThreads() {
        AbstractProtocol<?> protocol = tomcatProtocol();
        return protocol == null ? -1 : protocol.getMaxThreads();
    }

    /**
     * Меняет максимум потоков Tomcat через настройку коннектора, а не напрямую через исполнитель:
     * так меняется и само значение maxThreads, которое показывает метрика
     * tomcat_threads_config_max_threads, и пул потоков. minSpareThreads не может быть больше
     * максимума, поэтому порядок изменения зависит от того, уменьшаем мы пул или увеличиваем.
     */
    public synchronized void setTomcatMaxThreads(int threads) {
        AbstractProtocol<?> protocol = tomcatProtocol();
        if (protocol == null || threads <= 0) {
            return;
        }
        if (defaultMinSpareThreads < 0) {
            defaultMinSpareThreads = protocol.getMinSpareThreads();
        }
        int minSpare = Math.min(defaultMinSpareThreads, threads);
        if (threads < protocol.getMaxThreads()) {
            protocol.setMinSpareThreads(minSpare);
            protocol.setMaxThreads(threads);
        } else {
            protocol.setMaxThreads(threads);
            protocol.setMinSpareThreads(minSpare);
        }
    }

    private AbstractProtocol<?> tomcatProtocol() {
        if (!(webServerContext.getWebServer() instanceof TomcatWebServer tomcatWebServer)) {
            return null;
        }
        // Именно findConnectors(), а не getConnector(): последний создаёт новый коннектор
        // на порту 8080, если ни одного не привязано — а во время старта Spring Boot
        // временно снимает свои. Это приводило к «Port 8080 was already in use».
        Connector[] connectors = tomcatWebServer.getTomcat().getService().findConnectors();
        if (connectors == null || connectors.length == 0) {
            return null;
        }
        ProtocolHandler handler = connectors[0].getProtocolHandler();
        return handler instanceof AbstractProtocol<?> protocol ? protocol : null;
    }
}
