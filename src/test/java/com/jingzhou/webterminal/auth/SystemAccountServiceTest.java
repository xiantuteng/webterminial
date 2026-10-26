package com.jingzhou.webterminal.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemAccountServiceTest {

    private final SystemAccountService service = new SystemAccountService();

    @Test
    void rejectsUnsafeNamesWithoutTouchingTheSystem() {
        assertNull(service.lookup("alice;rm"));
        assertNull(service.lookup("-rf"));
        assertNull(service.lookup("../root"));
        assertNull(service.lookup(""));
        assertNull(service.lookup("a b"));
    }

    @Test
    void parsesPasswdLine() {
        Account account = SystemAccountService.parsePasswdLine("alice", "alice:x:1000:1000:Alice:/home/alice:/bin/bash");
        assertNotNull(account);
        assertEquals("/home/alice", account.getHome());
        assertEquals("/bin/bash", account.getShell());
        assertNull(SystemAccountService.parsePasswdLine("alice", "bob:x:1000:1000::/home/bob:/bin/sh"));
        assertNull(SystemAccountService.parsePasswdLine("alice", "alice:x:1:1::relative:/bin/sh"));
    }

    @Test
    void currentAccountResolves() {
        String user = System.getProperty("user.name");
        Account account = service.lookup(user);
        assertNotNull(account);
        assertEquals(user, account.getUsername());
        assertTrue(account.getHome().startsWith("/"));
        assertTrue(account.getShell().startsWith("/"));
    }

    @Test
    void wrongPasswordIsRejected() {
        Account account = service.lookup(System.getProperty("user.name"));
        assertNotNull(account);
        assertFalse(service.authenticate(account, "definitely-not-the-real-password"));
    }
}
