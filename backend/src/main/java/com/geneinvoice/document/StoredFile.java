package com.geneinvoice.document;

/** What {@link DocumentStorage#put} made of a stream: how much of it there was, and what it was. */
public record StoredFile(long sizeBytes, String checksum) {}
