package io.velocitybridge.hub;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MessageCodec} のテスト。
 */
class MessageCodecTest {

    @Test
    void roundTripEncodeDecode() {
        JsonObject payload = new JsonObject();
        payload.addProperty("uuid", "00000000-0000-0000-0000-000000000001");
        payload.addProperty("username", "TestPlayer");
        Message original = Message.of(MessageType.PLAYER_JOIN, "proxy-2", payload);

        byte[] encoded = MessageCodec.encode(original);
        Message decoded = MessageCodec.decode(encoded);

        assertEquals(original.type(), decoded.type());
        assertEquals(original.sender(), decoded.sender());
        assertNotNull(decoded.payload());
        assertEquals("00000000-0000-0000-0000-000000000001", decoded.payload().get("uuid").getAsString());
        assertEquals("TestPlayer", decoded.payload().get("username").getAsString());
    }

    @Test
    void streamRoundTrip() throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("message", "こんにちは");
        Message original = Message.of(MessageType.CHAT_MESSAGE, "proxy-1", payload);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MessageCodec.write(bos, original);

        Message decoded = MessageCodec.read(new ByteArrayInputStream(bos.toByteArray()));
        assertEquals(original.type(), decoded.type());
        assertEquals("こんにちは", decoded.payload().get("message").getAsString());
    }

    @Test
    void encryptedStreamRoundTrip() throws IOException {
        MessageCipher cipher = new MessageCipher("secret-key-1234");
        JsonObject payload = new JsonObject();
        payload.addProperty("message", "暗号化テスト");
        Message original = Message.of(MessageType.CHAT_MESSAGE, "proxy-1", payload);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MessageCodec.write(bos, original, cipher);

        Message decoded = MessageCodec.read(new ByteArrayInputStream(bos.toByteArray()), cipher);
        assertEquals(original.type(), decoded.type());
        assertEquals("暗号化テスト", decoded.payload().get("message").getAsString());
    }

    @Test
    void encryptedStreamFailsWithWrongSecret() throws IOException {
        MessageCipher cipherSender = new MessageCipher("secret-key-1234");
        MessageCipher cipherReceiver = new MessageCipher("wrong-key-5678");

        JsonObject payload = new JsonObject();
        payload.addProperty("message", "機密データ");
        Message original = Message.of(MessageType.CHAT_MESSAGE, "proxy-1", payload);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MessageCodec.write(bos, original, cipherSender);

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
                MessageCodec.read(new ByteArrayInputStream(bos.toByteArray()), cipherReceiver));
    }

    @Test
    void rejectsCorruptFrame() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> MessageCodec.decode(new byte[]{0, 0, 1, 0}));
    }
}
