package com.geneinvoice.notification;

import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    /** Snapshot of one invoice a strategy notification names to its recipient. */
    public record InvoiceRef(Long invoiceId, String invoiceNumber) {}

    private final NotificationRepository repository;
    private final UserRepository userRepository;

    @Transactional
    public Notification notify(Long userId, String type, String title, String message, String link) {
        return repository.save(Notification.builder()
                .userId(userId).type(type).title(title).message(message).link(link).build());
    }
    /**
     * Additive strategy-notification insertion: one durable per-recipient row carrying a
     * structured list of invoice snapshots. The five-argument {@link #notify} used by
     * dispute producers is unchanged.
     */
    @Transactional
    public Notification notifyStrategy(Long userId, String type, String title, String message,
                                       String link, List<InvoiceRef> invoiceItems) {
        Notification n = Notification.builder()
                .userId(userId).type(type).title(title).message(message).link(link).build();
        if (invoiceItems != null) {
            for (InvoiceRef it : invoiceItems) {
                n.getInvoiceItems().add(NotificationInvoiceItem.builder()
                        .notification(n)
                        .invoiceId(it.invoiceId())
                        .invoiceNumber(it.invoiceNumber())
                        .build());
            }
        }
        return repository.save(n);
    }

    @Transactional
    public void notifyAdmins(String type, String title, String message, String link) {
        List<User> admins = userRepository.findByRoleName("ADMIN");
        for (User a : admins) {
            notify(a.getId(), type, title, message, link);
        }
    }

    @Transactional(readOnly = true)
    public List<Notification> listFor(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(Long id, Long userId) {
        repository.findById(id).ifPresent(n -> {
            if (n.getUserId().equals(userId)) {
                n.setRead(true);
                repository.save(n);
            }
        });
    }

    @Transactional
    public int markAllRead(Long userId) {
        return repository.markAllRead(userId);
    }
}
