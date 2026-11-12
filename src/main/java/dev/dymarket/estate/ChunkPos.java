package dev.dymarket.estate;

import java.util.ArrayList;
import java.util.List;

/** A chunk coordinate inside one world. Stored as "x,z;x,z" in the estate tables. */
public record ChunkPos(int x, int z) {
    public static String encode(List<ChunkPos> chunks) {
        StringBuilder out = new StringBuilder();
        for (ChunkPos chunk : chunks) {
            if (!out.isEmpty()) out.append(';');
            out.append(chunk.x).append(',').append(chunk.z);
        }
        return out.toString();
    }

    /** Reads {@link #encode} output; broken parts are skipped. */
    public static List<ChunkPos> decode(String text) {
        List<ChunkPos> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;
        for (String part : text.split(";")) {
            int comma = part.indexOf(',');
            if (comma < 0) continue;
            try {
                chunks.add(new ChunkPos(Integer.parseInt(part.substring(0, comma).trim()), Integer.parseInt(part.substring(comma + 1).trim())));
            } catch (NumberFormatException broken) {
                // Skip a damaged entry rather than losing the whole listing.
            }
        }
        return chunks;
    }

    @Override
    public String toString() {
        return x + "," + z;
    }
}
