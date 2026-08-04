package dev.erinlkolp.glassnotify.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Encodes and decodes the HELLO frame body. */
public final class HelloCodec {

    private HelloCodec() {
    }

    public static byte[] encode(Hello hello) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(hello.deviceName);
        out.writeUTF(hello.deviceAddress);
        out.flush();
        return bytes.toByteArray();
    }

    public static Hello decode(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        String name = in.readUTF();
        String address = in.readUTF();
        return new Hello(name, address);
    }
}
