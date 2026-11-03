package org.dreeam.leaf.world.antifreecam;

import java.util.BitSet;
import org.jspecify.annotations.Nullable;

/**
 * Immutable; both may be null. hidden = full filler sections, partial = culled cells per section index (null entries
 * are sent real), never mutated. Disjoint. Section indices 0..sectionsCount-1.
 */
public record SendMasks(@Nullable BitSet hidden, long @Nullable [][] partial) {}
