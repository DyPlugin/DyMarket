package dev.dymarket.estate;

import dev.dyclaim.api.Claim;
import dev.dyclaim.api.DyClaimAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link ClaimBridge} over DyClaim 1.3.1's public API. Only created after {@link EstateModule#dyClaimSupported()}
 * confirmed that API exists, so older servers never link these classes.
 */
final class DyClaimBridge implements ClaimBridge {
    static ClaimInfo info(Claim claim) {
        return claim == null ? null : new ClaimInfo(claim.getId(), claim.getWorld(), claim.getChunkX(), claim.getChunkZ(),
                claim.getOwner(), claim.getOwnerName());
    }

    @Override
    public ClaimInfo claimAt(String world, int x, int z) {
        return DyClaimAPI.getClaimAt(world, x, z).map(DyClaimBridge::info).orElse(null);
    }

    @Override
    public List<ClaimInfo> claimsOf(UUID owner) {
        List<ClaimInfo> list = new ArrayList<>();
        for (Claim claim : DyClaimAPI.getClaims(owner)) list.add(info(claim));
        return list;
    }

    @Override
    public boolean transfer(UUID claimId, UUID newOwner, String newOwnerName) {
        return DyClaimAPI.transferClaim(claimId, newOwner, newOwnerName);
    }

    @Override
    public boolean setTrust(UUID claimId, UUID player, Set<String> rights, long expiresAt) {
        return DyClaimAPI.setTrust(claimId, player, rights, expiresAt);
    }

    @Override
    public boolean removeTrust(UUID claimId, UUID player) {
        return DyClaimAPI.removeTrust(claimId, player);
    }
}
