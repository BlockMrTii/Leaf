package org.dreeam.leaf.world.antifreecam;

import java.util.BitSet;
import org.jspecify.annotations.Nullable;

/** Immutable; both may be null. hidden = full filler, partial = per-cell culling. Disjoint. Section indices 0..sectionsCount-1. */
public record SendMasks(@Nullable BitSet hidden, @Nullable BitSet partial) {}
