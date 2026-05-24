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

    private final NotificationRepository repository;
    private final UserRepository userRepository;

    @Transactional
    public Notification notify(Long userId, String type, String title, String message, String link) {
        return repository.save(Notification.builder()
                .userId(userId).type(type).title(title).message(message).link(link).build());
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
