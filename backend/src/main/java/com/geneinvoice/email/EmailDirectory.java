package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.region.RegionGrants;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class EmailDirectory {

    @PersistenceContext
    private EntityManager em;

    // Only searchPeople asks: the four address lookups below are reached by the mail webhook and
    // the dispatcher, which have no caller at all and must keep resolving every address (B1).
    private final CurrentUser currentUser;

    /**
     * Naming a colleague to put on an email. Who is nameable is the same question the staff list
     * answers and it gets the same answer: a person is visible where they work, and a person who
     * holds no grant at all is visible to everybody, so the administrator who has just created
     * somebody can address them. Until B1 any staff user name-searched the entire company
     * directory, which is the one read of people that the query funnel never covered (B1).
     *
     * <p>Hand-written JPQL rather than RegionPredicates.visiblePerson because this reader builds
     * no CriteriaQuery; the two shapes are deliberately the same clause in two grammars, and the
     * customer-login arm is left out only because the query already excludes customer logins (B1).
     */
    @Transactional(readOnly = true)
    public List<User> searchPeople(String text, int limit) {
        boolean all = text == null || text.isBlank();
        RegionGrants grants = currentUser.grants();
        // A wildcard holder sees the whole directory, exactly as everybody did before B1; a caller
        // with no grant at all (and a caller with no principal, whose grants are none()) is left
        // with the unstaffed arm alone, which denies rather than widens (B1).
        boolean everywhere = grants.allRegions(RegionRight.VIEW);
        Set<Long> visible = everywhere ? Set.of() : grants.with(RegionRight.VIEW);
        String region = everywhere ? "" : " and (exists (select 1 from UserRegionGrant g"
                + " where g.userId = u.id and (g.regionId is null"
                // No "in ()": an empty permitted set drops the disjunct rather than building one
                // (B1, the RegionGrants.with contract).
                + (visible.isEmpty() ? "" : " or g.regionId in :regionIds") + "))"
                + " or not exists (select 1 from UserRegionGrant h where h.userId = u.id))";
        TypedQuery<User> query = em.createQuery("select u from User u"
                + " where u.active = true and u.customerId is null"
                + region
                + (all ? "" : " and (lower(u.username) like :q escape '\\'"
                        + " or lower(coalesce(u.fullName, '')) like :q escape '\\'"
                        + " or lower(coalesce(u.email, '')) like :q escape '\\')")
                + " order by lower(coalesce(u.fullName, u.username)), u.username", User.class);
        if (!all) query.setParameter("q", "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%");
        if (!visible.isEmpty()) query.setParameter("regionIds", visible);
        return query.setMaxResults(limit).getResultList();
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional(readOnly = true)
    public List<User> activeLoginsOf(Long customerId) {
        return em.createQuery("select u from User u where u.customerId = :customerId and u.active = true"
                        + " order by u.id", User.class)
                .setParameter("customerId", customerId)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public Optional<User> userByAddress(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        return em.createQuery("select u from User u where lower(u.email) = :address"
                        + " order by case when u.active = true then 0 else 1 end, u.id", User.class)
                .setParameter("address", address.trim().toLowerCase(Locale.ROOT))
                .setMaxResults(1)
                .getResultList().stream().findFirst();
    }

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

    static String withoutTag(String address) {
        String lower = address.trim().toLowerCase(Locale.ROOT);
        int at = lower.lastIndexOf('@');
        if (at < 0) return lower;
        String local = lower.substring(0, at);
        int plus = local.indexOf('+');
        return (plus < 0 ? local : local.substring(0, plus)) + lower.substring(at);
    }

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
