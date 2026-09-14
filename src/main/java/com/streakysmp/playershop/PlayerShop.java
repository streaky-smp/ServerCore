package com.streakysmp.playershop;

import org.bukkit.Material;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A player-owned stall.
 *
 * <p>Anchored to a single block -- the sign or chest customers interact with --
 * rather than an area. A shop is a point of sale, and tying it to one block keeps
 * "which shop did I just click" unambiguous.
 *
 * <p>{@code claimId} and {@code plotId} record where the shop sits. Exactly one is
 * normally set: a shop inside the owner's own claim, or one on a rented spawn
 * plot. Both being null means the shop is on unclaimed land, which the service
 * refuses to create precisely because nothing would protect it.
 *
 * @param revenue lifetime takings, in minor units, for the owner's own reporting
 */
public record PlayerShop(
        String id,
        UUID owner,
        String name,
        String description,
        String worldName,
        int x,
        int y,
        int z,
        String claimId,
        String plotId,
        PlayerShopStatus status,
        long createdAt,
        long revenue,
        List<ShopOffer> offers) {

    public PlayerShop {
        offers = List.copyOf(offers);
    }

    public boolean isOwner(UUID player) {
        return owner.equals(player);
    }

    /** Whether customers may trade here. */
    public boolean trading() {
        return status.trading();
    }

    public Optional<ShopOffer> offer(Material material) {
        for (ShopOffer offer : offers) {
            if (offer.material() == material) {
                return Optional.of(offer);
            }
        }
        return Optional.empty();
    }

    /** Offers a customer can currently buy from, i.e. priced and in stock. */
    public List<ShopOffer> buyableOffers() {
        return offers.stream().filter(ShopOffer::customerCanBuy).toList();
    }

    /** Offers the shop is buying from customers. */
    public List<ShopOffer> sellableOffers() {
        return offers.stream().filter(ShopOffer::customerCanSell).toList();
    }

    /** Whether this shop is on a rented spawn plot rather than the owner's own land. */
    public boolean onSpawnPlot() {
        return plotId != null;
    }

    /** Total items held across every offer, for the directory listing. */
    public int totalStock() {
        int total = 0;
        for (ShopOffer offer : offers) {
            total += offer.stock();
        }
        return total;
    }

    public PlayerShop withStatus(PlayerShopStatus newStatus) {
        return new PlayerShop(id, owner, name, description, worldName, x, y, z,
                claimId, plotId, newStatus, createdAt, revenue, offers);
    }

    public PlayerShop withOffers(List<ShopOffer> newOffers) {
        return new PlayerShop(id, owner, name, description, worldName, x, y, z,
                claimId, plotId, status, createdAt, revenue, newOffers);
    }

    public PlayerShop withDetails(String newName, String newDescription) {
        return new PlayerShop(id, owner, newName, newDescription, worldName, x, y, z,
                claimId, plotId, status, createdAt, revenue, offers);
    }

    public PlayerShop withRevenue(long newRevenue) {
        return new PlayerShop(id, owner, name, description, worldName, x, y, z,
                claimId, plotId, status, createdAt, newRevenue, offers);
    }

    public PlayerShop withOwner(UUID newOwner) {
        return new PlayerShop(id, newOwner, name, description, worldName, x, y, z,
                claimId, plotId, status, createdAt, revenue, offers);
    }

    /** Packed block key, used to index shops by the block customers click. */
    public String locationKey() {
        return locationKey(worldName, x, y, z);
    }

    public static String locationKey(String worldName, int x, int y, int z) {
        return worldName + ":" + x + ":" + y + ":" + z;
    }
}
