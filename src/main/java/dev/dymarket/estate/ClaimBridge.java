package dev.dymarket.estate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the estate module needs from DyClaim, so the rules can be tested with a fake. Reads may run on any
 * thread; {@link #transfer}, {@link #setTrust} and {@link #removeTrust} must run on the thread that owns the
 * claim's chunk (the region thread on Folia), which {@link EstateService} arranges through its platform.
 */
public interface ClaimBridge {
    /** A snapshot of one claim (one chunk). */
    record ClaimInfo(UUID id, String world, int x, int z, UUID owner, String ownerName) {
        public ChunkPos chunk() {
            return new ChunkPos(x, z);
        }
    }

    /** The claim covering the chunk, or null. */
    ClaimInfo claimAt(String world, int x, int z);

    /** Every claim the player owns, in all worlds. */
    List<ClaimInfo> claimsOf(UUID owner);

    boolean transfer(UUID claimId, UUID newOwner, String newOwnerName);

    /** Grants or replaces timed trust; {@code expiresAt} epoch millis, 0 = permanent. */
    boolean setTrust(UUID claimId, UUID player, Set<String> rights, long expiresAt);

    boolean removeTrust(UUID claimId, UUID player);
}
