package io.velocitybridge.chat;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatRelay} の配信先除外ロジックの検証。
 *
 * <p>SignedVelocity 未導入環境では、送信者はクライアント側で自分の送信メッセージを表示するため、
 * リレーから送信者を除外しないと二重表示になる。</p>
 */
class ChatRelayTest {

    @Test
    void excludesSenderWhenSenderUuidProvided() {
        UUID senderId = UUID.randomUUID();
        List<String> senderMessages = new CopyOnWriteArrayList<>();
        List<String> otherMessages = new CopyOnWriteArrayList<>();

        Player sender = fakePlayer(senderId, senderMessages);
        Player other = fakePlayer(UUID.randomUUID(), otherMessages);
        ChatRelay relay = new ChatRelay(fakeProxy(List.of(sender, other)));

        relay.onRemoteChat("proxy-1", "Alice", "konnitiha", "こんにちは", senderId);

        assertTrue(senderMessages.isEmpty(),
                "sender must not receive the relayed message (client renders it locally)");
        assertEquals(1, otherMessages.size(), "other players should receive the message once");
    }

    @Test
    void deliversToEveryoneWhenNoSenderUuid() {
        UUID senderId = UUID.randomUUID();
        List<String> senderMessages = new CopyOnWriteArrayList<>();
        List<String> otherMessages = new CopyOnWriteArrayList<>();

        Player sender = fakePlayer(senderId, senderMessages);
        Player other = fakePlayer(UUID.randomUUID(), otherMessages);
        ChatRelay relay = new ChatRelay(fakeProxy(List.of(sender, other)));

        relay.onRemoteChat("proxy-1", "Alice", "konnitiha", "こんにちは", null);

        assertEquals(1, senderMessages.size());
        assertEquals(1, otherMessages.size());
    }

    private static Player fakePlayer(UUID id, List<String> received) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getUniqueId":
                    return id;
                case "sendMessage":
                    if (args != null && args.length > 0 && args[0] instanceof Component) {
                        received.add(((Component) args[0]).toString());
                    }
                    return null;
                case "toString":
                    return "Player(" + id + ")";
                case "hashCode":
                    return id.hashCode();
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException("Unexpected call: " + method);
            }
        };
        return (Player) Proxy.newProxyInstance(ChatRelayTest.class.getClassLoader(),
                new Class<?>[]{Player.class}, handler);
    }

    private static ProxyServer fakeProxy(List<Player> players) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getAllPlayers":
                    return players;
                case "toString":
                    return "ProxyServer";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException("Unexpected call: " + method);
            }
        };
        return (ProxyServer) Proxy.newProxyInstance(ChatRelayTest.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class}, handler);
    }
}
