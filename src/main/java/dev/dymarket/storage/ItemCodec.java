package dev.dymarket.storage;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

/**
 * Turns items into bytes for the database and back. Paper's {@code serializeAsBytes} is used when the server has
 * it (it survives Minecraft updates); other servers use Bukkit object streams. The first byte names the format,
 * so a database moved between server types still reads.
 */
public final class ItemCodec {
    public static final byte PAPER = 1;
    public static final byte BUKKIT = 2;

    private static final MethodHandle SERIALIZE;
    private static final MethodHandle DESERIALIZE;

    static {
        MethodHandle serialize = null, deserialize = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            serialize = lookup.findVirtual(ItemStack.class, "serializeAsBytes", MethodType.methodType(byte[].class));
            deserialize = lookup.findStatic(ItemStack.class, "deserializeBytes", MethodType.methodType(ItemStack.class, byte[].class));
        } catch (ReflectiveOperationException | LinkageError missing) {
            // Spigot: Bukkit object streams only.
        }
        SERIALIZE = serialize;
        DESERIALIZE = deserialize;
    }

    private ItemCodec() {
    }

    public static boolean paperFormat() {
        return SERIALIZE != null && DESERIALIZE != null;
    }

    public static byte[] encode(ItemStack item) throws IOException {
        byte[] body;
        byte format;
        if (paperFormat()) {
            try {
                body = (byte[]) SERIALIZE.invoke(item);
                format = PAPER;
            } catch (Throwable failure) {
                throw new IOException("Could not serialize " + item.getType(), failure);
            }
        } else {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
                output.writeObject(item);
            }
            body = bytes.toByteArray();
            format = BUKKIT;
        }
        byte[] out = new byte[body.length + 1];
        out[0] = format;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    public static ItemStack decode(byte[] data) throws IOException {
        if (data == null || data.length < 2) throw new IOException("Empty item data");
        byte[] body = Arrays.copyOfRange(data, 1, data.length);
        if (data[0] == PAPER) {
            if (DESERIALIZE == null) throw new IOException("This item was saved on Paper and needs a Paper-based server to load");
            try {
                return (ItemStack) DESERIALIZE.invoke(body);
            } catch (Throwable failure) {
                throw new IOException("Could not read a stored item", failure);
            }
        }
        if (data[0] != BUKKIT) throw new IOException("Unknown item format " + data[0]);
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(body))) {
            return (ItemStack) input.readObject();
        } catch (ClassNotFoundException failure) {
            throw new IOException("Could not read a stored item", failure);
        }
    }
}
