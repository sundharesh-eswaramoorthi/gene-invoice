package com.geneinvoice.email;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class RecipientSet {

    record Entry(RecipientField field, Long userId, Long customerId, String name, String address,
                 boolean internal, List<String> sources) {}

    private static final class Draft {
        RecipientField field;
        Long userId;
        Long customerId;
        String name;
        String address;
        boolean internal;
        final Set<String> sources = new LinkedHashSet<>();

        boolean samePerson(Draft other) {
            return (userId != null && userId.equals(other.userId))
                    || (address != null && other.address != null && address.equalsIgnoreCase(other.address));
        }

        void absorb(Draft other) {
            if (userId == null && other.userId != null) {
                userId = other.userId;
                customerId = other.customerId;
                name = other.name;
                internal = other.internal;
            }
            if (customerId == null) customerId = other.customerId;
            if (address == null) address = other.address;
            if (other.field == RecipientField.TO) field = RecipientField.TO;
            sources.addAll(other.sources);
        }
    }

    private final List<Draft> drafts = new ArrayList<>();

    void add(RecipientField field, EmailTargets.Person person, String source) {
        add(field, person.userId(), person.customerId(), person.name(), person.address(), person.internal(), source);
    }

    void add(RecipientField field, Long userId, Long customerId, String name, String address,
             boolean internal, String source) {
        Draft incoming = new Draft();
        incoming.field = field;
        incoming.userId = userId;
        incoming.customerId = customerId;
        incoming.name = name;
        incoming.address = address;
        incoming.internal = internal;
        incoming.sources.add(source);

        List<Draft> same = drafts.stream().filter(d -> d.samePerson(incoming)).toList();
        if (same.isEmpty()) {
            drafts.add(incoming);
            return;
        }
        Draft kept = same.get(0);
        for (Draft other : same.subList(1, same.size())) {
            kept.absorb(other);
            drafts.remove(other);
        }
        kept.absorb(incoming);
    }

    boolean isEmpty() {
        return drafts.isEmpty();
    }

    List<Entry> entries() {
        return drafts.stream().map(d -> new Entry(d.field, d.userId, d.customerId, d.name, d.address,
                d.internal, List.copyOf(d.sources))).toList();
    }
}
