package dev.dymarket.economy;

import java.util.UUID;

/** A currency kept by another plugin ({@code provider: vault}); DyMarket only logs what passes through. */
public interface ExternalEconomy {
    /** True when the other plugin is present and answering. */
    boolean available();

    long balance(UUID player);

    boolean withdraw(UUID player, long minor);

    boolean deposit(UUID player, long minor);
}
