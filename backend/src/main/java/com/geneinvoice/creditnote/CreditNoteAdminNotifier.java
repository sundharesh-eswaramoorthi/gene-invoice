package com.geneinvoice.creditnote;

import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Post-commit admin fan-out for credit-note issuance. Runs WITHOUT a transaction
 * of its own, so each {@link NotificationService#notify} call is its own
 * proxied transaction: one failed recipient attempts rolls back only itself and
 * never stops later recipients.
 *
 * Exactly one attempt is made per admin per committed issuance — no retry, no
 * queue — and the shared {@code notifyAdmins} behavior is left untouched for its
 * existing dispute callers. The notice links to '/invoices', an existing route.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteAdminNotifier {

    public static final String NOTIFICATION_TYPE = "CREDIT_NOTE_ISSUED";
    public static final String NOTIFICATION_TITLE = "Credit note issued";
    public static final String NOTIFICATION_LINK = "/invoices";

    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /**
     * Attempts one notification per admin for one committed issuance.
     *
     * @return true when admin enumeration or any single recipient attempt failed,
     *         false when every admin was notified.
     */
    public boolean notifyAdminsOfIssuance(CreditNoteDtos.CreditNoteResponse note) {
        List<User> admins;
        try {
            admins = userRepository.findByRoleName("ADMIN");
        } catch (RuntimeException e) {
            return true;
        }
        String message = "Credit note of " + note.amount().toPlainString()
                + " issued for invoice " + note.invoiceNumber()
                + " by " + note.issuedBy() + ".";
        boolean failed = false;
        for (User admin : admins) {
            try {
                notificationService.notify(admin.getId(), NOTIFICATION_TYPE,
                        NOTIFICATION_TITLE, message, NOTIFICATION_LINK);
            } catch (RuntimeException e) {
                failed = true;
            }
        }
        return failed;
    }
}
