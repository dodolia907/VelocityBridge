package io.velocitybridge.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuthHandler} のテスト。
 */
class AuthHandlerTest {

    private static final String SECRET = "super-secret-forwarding-key";

    @Test
    void verifiesCorrectMac() {
        AuthHandler handler = new AuthHandler(SECRET);
        String nonce = AuthHandler.generateNonce();
        String mac = handler.computeMac("proxy-2", nonce);
        assertTrue(handler.verify("proxy-2", nonce, mac));
    }

    @Test
    void rejectsWrongSecret() {
        AuthHandler server = new AuthHandler(SECRET);
        AuthHandler impostor = new AuthHandler("wrong-secret");
        String nonce = AuthHandler.generateNonce();
        String mac = impostor.computeMac("proxy-2", nonce);
        assertFalse(server.verify("proxy-2", nonce, mac));
    }

    @Test
    void rejectsWrongNodeId() {
        AuthHandler handler = new AuthHandler(SECRET);
        String nonce = AuthHandler.generateNonce();
        String mac = handler.computeMac("proxy-2", nonce);
        assertFalse(handler.verify("proxy-3", nonce, mac));
    }

    @Test
    void nonceIsUniquePerCall() {
        assertNotEquals(AuthHandler.generateNonce(), AuthHandler.generateNonce());
    }
}
