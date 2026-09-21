package com.github.lechandonga.gitopsdeliveryjava.config;

import com.github.lechandonga.gitopsdeliveryjava.engine.ReconciliationEngine;
import com.github.lechandonga.gitopsdeliveryjava.engine.RecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** 进程启动：先恢复（重放事件 + 接管过期租约），再按配置自动收敛一次。 */
@Component
public class DeliveryStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeliveryStartupRunner.class);

    private final RecoveryService recovery;
    private final ReconciliationEngine engine;
    private final DeliveryProperties props;

    public DeliveryStartupRunner(RecoveryService recovery, ReconciliationEngine engine,
                                 DeliveryProperties props) {
        this.recovery = recovery;
        this.engine = engine;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> takenOver = recovery.recoverOnStartup();
        if (!takenOver.isEmpty()) {
            log.warn("recovery took over expired leases: {}", takenOver);
        }
        if (props.isAutoReconcileOnStartup()) {
            try {
                var report = engine.converge("recovery");
                log.info("startup auto-converge: converged={} drift={} outcomes={}",
                        report.converged(), report.driftDetected(),
                        report.outcomes().size());
            } catch (RuntimeException e) {
                // 无清单等情形不算启动失败
                log.info("startup auto-converge skipped: {}", e.getMessage());
            }
        }
    }
}
