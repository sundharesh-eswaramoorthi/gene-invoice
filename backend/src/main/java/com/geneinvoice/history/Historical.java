package com.geneinvoice.history;

/**
 * A record as it was at a point, rebuilt from its versions: an unmapped subclass of the live entity, so every
 * reader that takes the entity takes it, and no save can ever write it (Hibernate knows no persister for it).
 */
public interface Historical {
}
