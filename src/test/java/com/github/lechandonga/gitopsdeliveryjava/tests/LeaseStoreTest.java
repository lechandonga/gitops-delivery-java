package com.github.lechandonga.gitopsdeliveryjava.tests;

import com.github.lechandonga.gitopsdeliveryjava.store.Lease;
import com.github.lechandonga.gitopsdeliveryjava.store.LeaseStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseStoreTest {

    @Test
    void sameTargetMutuallyExclusive(@TempDir Path dir) {
        LeaseStore leases = new LeaseStore(dir, 10_000);
        Lease a = leases.acquire("t", "owner-a");
        Lease b = leases.acquire("t", "owner-b");
        assertNotNull(a);
        assertNull(b);
        assertTrue(leases.isValid("t", "owner-a", a.fenceToken));
    }

    @Test
    void expiredLeaseCanBeTakenOverAndOldTokenDies(@TempDir Path dir) throws Exception {
        LeaseStore leases = new LeaseStore(dir, 60);
        Lease a = leases.acquire("t", "owner-a");
        Thread.sleep(90);
        Lease b = leases.takeover("t", "owner-b");
        assertNotNull(b);
        assertNotEquals(a.fenceToken, b.fenceToken);
        // 旧 owner 的 token 立刻失效，杜绝双写
        assertFalse(leases.isValid("t", "owner-a", a.fenceToken));
        assertTrue(leases.isValid("t", "owner-b", b.fenceToken));
        // 旧 owner 不能用旧 token 释放（不能抹掉新持有者的租约）
        leases.release("t", "owner-a", a.fenceToken);
        assertTrue(leases.isValid("t", "owner-b", b.fenceToken));
    }

    @Test
    void renewRequiresMatchingOwnerAndToken(@TempDir Path dir) {
        LeaseStore leases = new LeaseStore(dir, 10_000);
        Lease a = leases.acquire("t", "owner-a");
        assertFalse(leases.renew("t", "owner-b", a.fenceToken));
        assertTrue(leases.renew("t", "owner-a", a.fenceToken));
    }
}
