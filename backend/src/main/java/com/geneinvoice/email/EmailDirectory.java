package com.geneinvoice.email;

import com.geneinvoice.customer.Customer;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Finds people and customers the way email needs them: by search text, by the customer they log in
 * for, and by address. Addresses are compared ignoring case, as mail servers do.
 */
@Component
public class EmailDirectory {

    @PersistenceContext
    private EntityManager em;

    /**
     * Active internal users whose username, full name or email contains the text, by name. The text is
     * matched literally: its {@code %} and {@code _} are escaped, as the table framework's "contains"
     * does, or a search for "%" would list everyone.
     */
    @Transactional(readOnly = true)
    public List<User> searchPeople(String text, int limit) {
        boolean all = text == null || text.isBlank();
        TypedQuery<User> query = em.createQuery("select u from User u"
                + " where u.active = true and u.customerId is null"
                + (all ? "" : " and (lower(u.username) like :q escape '\\'"
                        + " or lower(coalesce(u.fullName, '')) like :q escape '\\'"
                        + " or lower(coalesce(u.email, '')) like :q escape '\\')")
                + " order by lower(coalesce(u.fullName, u.username)), u.username", User.class);
        if (!all) query.setParameter("q", "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%");
        return query.setMaxResults(limit).getResultList();
    }

    /** The escape character itself first, then the wildcards, each preceded by it. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A customer may have more than one login; every active one counts as the customer's address. */
    @Transactional(readOnly = true)
    public List<User> activeLoginsOf(Long customerId) {
        return em.createQuery("select u from User u where u.customerId = :customerId and u.active = true"
                        + " order by u.id", User.class)
                .setParameter("customerId", customerId)
                .getResultList();
    }

    /** The account an address belongs to; an active one first, should two differ only in case. */
    @Transactional(readOnly = true)
    public Optional<User> userByAddress(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        return em.createQuery("select u from User u where lower(u.email) = :address"
                        + " order by case when u.active = true then 0 else 1 end, u.id", User.class)
                .setParameter("address", address.trim().toLowerCase(Locale.ROOT))
                .setMaxResults(1)
                .getResultList().stream().findFirst();
    }

    /**
     * The user who connected this Gmail address to send from (mail-service.md M2), which need not be
     * their email in Users. A {@code local+tag@} alias is the same mailbox. When more than one user
     * connected it, a working connection first, then the latest; users no longer on file are nobody.
     */
    @Transactional(readOnly = true)
    public Optional<User> userByGmail(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        return em.createQuery("select u from GmailConnection g, User u where u.id = g.userId"
                        + " and lower(g.gmailAddress) = :address"
                        + " order by case when g.status = com.geneinvoice.email.transport.ConnectionStatus.CONNECTED"
                        + " then 0 else 1 end, g.updatedAt desc, u.id", User.class)
                .setParameter("address", withoutTag(address))
                .setMaxResults(1)
                .getResultList().stream().findFirst();
    }

    /** Lower case, without the {@code +tag} of {@code local+tag@domain}, which delivers to the same mailbox. */
    static String withoutTag(String address) {
        String lower = address.trim().toLowerCase(Locale.ROOT);
        int at = lower.lastIndexOf('@');
        if (at < 0) return lower;
        String local = lower.substring(0, at);
        int plus = local.indexOf('+');
        return (plus < 0 ? local : local.substring(0, plus)) + lower.substring(at);
    }

    /**
     * The customer an address belongs to, as the customer's own email or one of its logins'. Empty
     * when it belongs to none, or to several — then nobody can say which one wrote.
     */
    @Transactional(readOnly = true)
    public Optional<Customer> customerByAddress(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        String wanted = address.trim().toLowerCase(Locale.ROOT);
        Set<Long> ids = new LinkedHashSet<>(em.createQuery(
                        "select c.id from Customer c where lower(c.email) = :address", Long.class)
                .setParameter("address", wanted).getResultList());
        ids.addAll(em.createQuery("select u.customerId from User u"
                        + " where u.customerId is not null and lower(u.email) = :address", Long.class)
                .setParameter("address", wanted).getResultList());
        if (ids.size() != 1) return Optional.empty();
        return Optional.ofNullable(em.find(Customer.class, ids.iterator().next()));
    }
}
