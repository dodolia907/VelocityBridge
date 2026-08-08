package io.velocitybridge.probe;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MinecraftStatusPing} の VarInt エンコード / デコードのテスト。
 */
class VarIntTest {

    @Test
    void roundTripsCommonValues() throws IOException {
        int[] values = {0, 1, 127, 128, 255, 300, 16383, 16384, 2097151, 2097152,
                Integer.MAX_VALUE, -1, 0x7FFFFFFF};
        for (int value : values) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            MinecraftStatusPing.writeVarInt(new DataOutputStream(bos), value);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
            assertEquals(value, MinecraftStatusPing.readVarInt(in),
                    "round trip failed for " + value);
        }
    }

    @Test
    void rejectsVarIntLongerThanFiveBytes() {
        byte[] bad = new byte[]{
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00};
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bad));
        assertThrows(IOException.class, () -> MinecraftStatusPing.readVarInt(in));
    }
}
