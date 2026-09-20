package com.geneinvoice.email;

import com.geneinvoice.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** A user's Inbox: the emails they are a To recipient of, whatever became of delivery (E11). */
@Service
@RequiredArgsConstructor
public class InboxService {

    private final EmailRecipientRepository repository;

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndFieldAndReadFalse(userId, RecipientField.TO);
    }

    /** Someone else's row, or a Cc row, is as absent as one that never existed. */
    @Transactional
    public void setRead(Long id, Long userId, boolean read) {
        Marked marked = mark(id, userId, read);
        if (marked == Marked.NOT_FOUND || marked == Marked.NOT_OURS) throw new NotFoundException("Inbox item not found");
    }

    @Transactional
    public int markAllRead(Long userId) {
        return repository.markAllRead(userId, Instant.now());
    }

    enum Marked { CHANGED, ALREADY, NOT_FOUND, NOT_OURS }

    /**
     * Marks one of the user's own Inbox rows read or unread. Keeps when it was first read; marking
     * unread forgets it. Only the read columns are written, never a row loaded earlier, since a report
     * from the mail service may be changing the same row's copy at the same moment.
     */
    Marked mark(Long id, Long userId, boolean read) {
        int changed = read ? repository.markRead(id, userId, Instant.now()) : repository.markUnread(id, userId);
        if (changed > 0) return Marked.CHANGED;
        return repository.findById(id)
                .map(r -> isInboxRowOf(r, userId) ? Marked.ALREADY : Marked.NOT_OURS)
                .orElse(Marked.NOT_FOUND);
    }

    static boolean isInboxRowOf(EmailRecipient row, Long userId) {
        return userId.equals(row.getUserId()) && row.getField() == RecipientField.TO;
    }
}
