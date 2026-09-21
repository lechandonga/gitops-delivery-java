package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.service.DeliveryService;
import com.github.lechandonga.gitopsdeliveryjava.service.RecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证全部 Bean（含 @Lazy 解决的 StateStore/LeaseManager 循环）可被 Spring 装配。
 */
@SpringBootTest(properties = {
        "delivery.data-dir=${java.io.tmpdir}/delivery-spring-test",
        "delivery.scheduler-enabled=false"
})
class ApplicationContextTest {

    @Autowired
    DeliveryService deliveryService;

    @Autowired
    RecoveryService recoveryService;

    @Test
    void contextWires() {
        assertNotNull(deliveryService);
        assertNotNull(recoveryService);
    }
}
