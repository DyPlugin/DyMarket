package dev.dymarket.importer;

import java.math.BigDecimal;
import java.util.UUID;

/** One balance read from another plugin. */
public record ImportEntry(UUID id, String name, BigDecimal amount) { }
