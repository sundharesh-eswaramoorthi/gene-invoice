package com.geneinvoice.mail.tracking;

import com.geneinvoice.mail.MailText;
import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.connection.ConnectionDto;
import com.geneinvoice.mail.connection.ConnectionService;
import com.geneinvoice.mail.connection.ConnectionStatus;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.connection.MailConnectionRepository;
import com.geneinvoice.mail.events.EventRecorder;
import com.geneinvoice.mail.gmail.GmailApiException;
import com.geneinvoice.mail.gmail.GmailClient;
import com.geneinvoice.mail.gmail.GmailMime;
import com.geneinvoice.mail.gmail.GoogleAuthException;
import com.geneinvoice.mail.gmail.IncomingMail;
import com.geneinvoice.mail.message.MailMessage;
import com.geneinvoice.mail.message.MailMessageRepository;
import com.geneinvoice.mail.message.MessageService;
import com.geneinvoice.mail.message.MessageStatus;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads one connected mailbox (§4.7). Gmail's history says what changed since the last run; of what
 * arrived, only messages in threads the service sent a copy from this mailbox are looked at (M10) —
 * a bounce turns its copy BOUNCED, anything else is a reply the backend hears about. A message read
 * in the mailbox of a copy's recipient turns the copy READ. No database transaction is open during
 * a Gmail call.
 */
@Component
@Slf4j
public class MailboxSync {

    static final String NOT_CONNECTED = "Gmail is not connected";
    static final String ALREADY_RUNNING = "A sync is already running";
    static final String TURNED_OFF = "Receiving email is turned off (MAIL_SYNC_ENABLED)";
    /** How far back a run looks when Gmail no longer keeps the history it needs. */
    static final Duration RECENT_WINDOW = Duration.ofDays(7);
    /** What stands in for history Gmail no longer keeps. */
    static final String RECENT = "newer_than:" + RECENT_WINDOW.toDays() + "d";
    static final String UNREAD = "UNREAD";
    /** Our own sent copies, unfinished drafts, and mail set aside as spam or deleted. */
    private static final Set<String> SKIPPED_LABELS = Set.of("SENT", "DRAFT", "SPAM", "TRASH");
    /** Labels whose removal brings mail back: "Not spam", or restored from Trash. */
    private static final Set<String> SET_ASIDE_LABELS = Set.of("SPAM", "TRASH");

    private final MailProperties properties;
    private final MailConnectionRepository connections;
    private final MailMessageRepository messages;
    private final MailInboundRepository inbound;
    private final ConnectionService connectionService;
    private final MessageService messageService;
    private final EventRecorder events;
    private final GmailClient gmail;
    private final TransactionTemplate transactions;
    private final Clock clock;
    /**
     * Per connection: the scheduler and someone asking to sync now would otherwise read the same
     * messages side by side. Whoever comes second is told a run is under way rather than kept waiting.
     */
    private final Map<Long, ReentrantLock> running = new ConcurrentHashMap<>();
    /**
     * Per connection: one more when a run starts and again when it ends (odd while one is under way),
     * so a send can tell whether a run read the history while its thread was not yet recorded.
     */
    private final Map<Long, AtomicLong> runs = new ConcurrentHashMap<>();

    public MailboxSync(MailProperties properties, MailConnectionRepository connections, MailMessageRepository messages,
                       MailInboundRepository inbound, ConnectionService connectionService, MessageService messageService,
                       EventRecorder events, GmailClient gmail, TransactionTemplate transactions, Clock clock) {
        this.properties = properties;
        this.connections = connections;
        this.messages = messages;
        this.inbound = inbound;
        this.connectionService = connectionService;
        this.messageService = messageService;
        this.events = events;
        this.gmail = gmail;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** What to look at, oldest first; messages read (UNREAD removed); and the history id to continue from. */
    private record Changes(List<GmailClient.MessageRef> added, List<String> read, String historyId) {}

    private static final class Counts {
        int fetched;
        int imported;
    }

    /** The owner's mailbox, now (the API). */
    public SyncResult syncNow(String ownerRef) {
        if (!properties.getSync().isEnabled()) return new SyncResult(false, 0, 0, TURNED_OFF);
        MailConnection connection = transactions.execute(status -> connections.findByOwnerRef(ownerRef).orElse(null));
        if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
            return new SyncResult(false, 0, 0, NOT_CONNECTED);
        }
        return sync(connection.getId());
    }

    /**
     * Reads what changed since the last run. A failed run keeps its place, so the next one looks at the
     * same messages again; the ones already dealt with are passed over by id without a download. While
     * another run on the mailbox is under way it returns at once with {@link #ALREADY_RUNNING}.
     */
    public SyncResult sync(long connectionId) {
        ReentrantLock lock = running.computeIfAbsent(connectionId, id -> new ReentrantLock());
        if (!lock.tryLock()) return new SyncResult(true, 0, 0, ALREADY_RUNNING);
        AtomicLong counter = runs(connectionId);
        counter.incrementAndGet();
        try {
            MailConnection connection = transactions.execute(status -> connections.findById(connectionId).orElse(null));
            if (connection == null || connection.getStatus() != ConnectionStatus.CONNECTED) {
                return new SyncResult(false, 0, 0, NOT_CONNECTED);
            }
            return run(connection);
        } finally {
            counter.incrementAndGet();
            lock.unlock();
        }
    }

    /**
     * Where reading the mailbox stands, taken just before a copy goes to Gmail from it and handed back
     * to {@link #threadRecorded} once the copy is recorded as sent.
     */
    public long readingMark(long connectionId) {
        return runs(connectionId).get();
    }

    /**
     * A copy sent from {@code mailbox} as {@code sentMessageId} has just been recorded with its thread.
     * Until then a run passes over whatever comes in that thread, and moves its place past it for good:
     * a bounce for an address that does not exist comes within seconds. So when a run was under way, or
     * has run, since {@code markBeforeSend} (null when it is not known when the copy went, as for one an
     * earlier attempt sent), the thread is looked at once and what came after the copy is handled
     * message by message as a run would; a message already dealt with is passed over. What came before
     * it (Gmail threads a copy into an earlier conversation with the same subject) cannot be about it.
     * Best effort: a failure is logged and the send stands.
     */
    public void threadRecorded(MailConnection mailbox, String threadId, String sentMessageId, Long markBeforeSend) {
        if (threadId == null || sentMessageId == null || !properties.getSync().isEnabled()) return;
        if (markBeforeSend != null && !readSince(mailbox.getId(), markBeforeSend)) return;
        try {
            GmailClient.ThreadMessages thread = gmail.thread(mailbox, threadId);
            Counts counts = new Counts();
            boolean afterTheCopy = false;
            // Gmail lists a thread's messages oldest first.
            for (GmailClient.MessageRef message : thread == null ? List.<GmailClient.MessageRef>of() : orEmpty(thread.messages())) {
                if (message.id() == null) continue;
                if (!afterTheCopy) {
                    afterTheCopy = message.id().equals(sentMessageId);
                    continue;
                }
                handle(mailbox, new GmailClient.MessageRef(message.id(), threadId, message.labelIds()), counts);
            }
            if (counts.imported > 0) {
                log.info("Found {} message(s) in thread {} of {} that came before the copy was recorded",
                        counts.imported, threadId, mailbox.getOwnerRef());
            }
        } catch (GoogleAuthException e) {
            connectionService.needsReconnect(mailbox, e);
        } catch (GmailApiException e) {
            if (e.status() != 404) {
                log.warn("Could not look at thread {} in the mailbox of {}: {}", threadId, mailbox.getOwnerRef(), e.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("Could not look at thread {} in the mailbox of {}", threadId, mailbox.getOwnerRef(), e);
        }
    }

    /** Whether a run was under way at {@code mark}, or has started or ended since. */
    private boolean readSince(long connectionId, long mark) {
        return (mark & 1) == 1 || runs(connectionId).get() != mark;
    }

    private AtomicLong runs(long connectionId) {
        return runs.computeIfAbsent(connectionId, id -> new AtomicLong());
    }

    private SyncResult run(MailConnection mailbox) {
        Counts counts = new Counts();
        try {
            if (mailbox.getHistoryId() == null) {
                // Only the starting point: what is already in the mailbox was not sent by the service.
                finish(mailbox, currentHistoryId(mailbox));
                return new SyncResult(true, 0, 0, null);
            }
            Changes changes = changesSince(mailbox, mailbox.getHistoryId());
            for (String messageId : changes.read()) markRead(mailbox, messageId);
            for (GmailClient.MessageRef message : changes.added()) handle(mailbox, message, counts);
            finish(mailbox, changes.historyId());
            return new SyncResult(true, counts.fetched, counts.imported, null);
        } catch (GoogleAuthException e) {
            // Marking the connection reports it, error and all.
            recordError(mailbox, e.getMessage(), false);
            connectionService.needsReconnect(mailbox, e);
            return new SyncResult(true, counts.fetched, counts.imported, e.getMessage());
        } catch (RuntimeException e) {
            String error;
            if (e instanceof GmailApiException) {
                error = e.getMessage();
            } else {
                log.warn("Gmail sync of {} failed", mailbox.getOwnerRef(), e);
                error = "Sync failed: " + e.getMessage();
            }
            recordError(mailbox, error, true);
            return new SyncResult(true, counts.fetched, counts.imported, error);
        }
    }

    /**
     * History since the last run, every page of it; past Gmail's retention, the last week instead.
     * Mail taken out of Spam or Trash counts as new: it was passed over when it arrived.
     */
    private Changes changesSince(MailConnection mailbox, String historyId) {
        Map<String, GmailClient.MessageRef> added = new LinkedHashMap<>();
        Set<String> read = new LinkedHashSet<>();
        String latest = historyId;
        String pageToken = null;
        try {
            do {
                GmailClient.HistoryPage page = gmail.history(mailbox, historyId, pageToken);
                if (page == null) break;
                Map<String, GmailClient.MessageRef> pageAdded = new LinkedHashMap<>();
                for (GmailClient.History record : orEmpty(page.history())) {
                    for (GmailClient.MessageAdded messageAdded : orEmpty(record.messagesAdded())) {
                        GmailClient.MessageRef message = messageAdded.message();
                        if (message != null && message.id() != null) pageAdded.putIfAbsent(message.id(), message);
                    }
                    for (GmailClient.LabelsRemoved removed : orEmpty(record.labelsRemoved())) {
                        GmailClient.MessageRef message = removed.message();
                        if (message == null || message.id() == null) continue;
                        if (orEmpty(removed.labelIds()).contains(UNREAD)) read.add(message.id());
                        if (setAside(removed.labelIds())) {
                            // Without labels, which an earlier record may list from before the rescue: the
                            // message's own decide. One that arrived in this run keeps its place.
                            pageAdded.put(message.id(), new GmailClient.MessageRef(message.id(), message.threadId(), null));
                        }
                    }
                }
                for (GmailClient.MessageRef message : inOwnThreads(mailbox, pageAdded.values())) {
                    if (message.labelIds() == null) added.put(message.id(), message);
                    else added.putIfAbsent(message.id(), message);
                }
                if (page.historyId() != null) latest = page.historyId();
                pageToken = page.nextPageToken();
            } while (pageToken != null);
            return new Changes(List.copyOf(added.values()), List.copyOf(read), latest);
        } catch (GmailApiException e) {
            if (e.status() != 404) throw e;
        }
        log.info("Gmail no longer has history of {} from {}; reading the last week instead", mailbox.getOwnerRef(), historyId);
        return recent(mailbox);
    }

    /**
     * The last week of the mailbox, oldest first so a bounce or reply is handled in the order it came,
     * and the copies of that week found here that have been read since. History starts again from
     * where the mailbox stood before looking, so nothing arriving or read meanwhile is missed.
     */
    private Changes recent(MailConnection mailbox) {
        String restartAt = currentHistoryId(mailbox);
        List<GmailClient.MessageRef> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String pageToken = null;
        do {
            GmailClient.MessagePage page = gmail.listMessages(mailbox, RECENT, pageToken, false);
            if (page == null) break;
            List<GmailClient.MessageRef> listed = orEmpty(page.messages()).stream()
                    .filter(m -> m.id() != null && seen.add(m.id())).toList();
            found.addAll(inOwnThreads(mailbox, listed));
            pageToken = page.nextPageToken();
        } while (pageToken != null);
        Collections.reverse(found);
        return new Changes(found, readWithoutHistory(mailbox), restartAt);
    }

    /**
     * Copies of the last week found in this mailbox (confirmed, not yet read) that it no longer shows
     * unread. History would have said so as UNREAD came off; without it each one is asked about, and
     * {@code DeliveryTracker} will not, as it only looks for copies not found yet. No labels at all
     * (read and archived) counts as read, as when a copy is first found.
     */
    private List<String> readWithoutHistory(MailConnection mailbox) {
        Instant sentSince = clock.instant().minus(RECENT_WINDOW);
        List<String> unread = transactions.execute(status ->
                messages.findUnreadInRecipientMailbox(mailbox.getId(), sentSince));
        List<String> read = new ArrayList<>();
        for (String messageId : new LinkedHashSet<>(unread == null ? List.<String>of() : unread)) {
            GmailClient.MessageInfo info;
            try {
                info = gmail.messageInfo(mailbox, messageId);
            } catch (GmailApiException e) {
                if (e.status() == 404) continue; // deleted: whether it was read first cannot be told
                if (!refusedForTheMessageAlone(mailbox, e)) throw e;
                log.warn("Skipping Gmail message {}, whose labels Gmail did not hand over: {}", messageId, e.getMessage());
                continue;
            }
            if (info != null && (info.labelIds() == null || !info.labelIds().contains(UNREAD))) read.add(messageId);
        }
        return read;
    }

    /** Only messages in a thread the service sent a copy from this mailbox: one query per page. */
    private List<GmailClient.MessageRef> inOwnThreads(MailConnection mailbox, Collection<GmailClient.MessageRef> refs) {
        Set<String> threads = new HashSet<>();
        refs.forEach(ref -> {
            if (ref.threadId() != null) threads.add(ref.threadId());
        });
        if (threads.isEmpty()) return List.of();
        Set<String> own = new HashSet<>(transactions.execute(status -> messages.findOwnThreads(mailbox.getId(), threads)));
        return refs.stream().filter(ref -> ref.threadId() != null && own.contains(ref.threadId())).toList();
    }

    /** A copy's recipient read it in their own mailbox. */
    private void markRead(MailConnection mailbox, String messageId) {
        transactions.executeWithoutResult(status -> {
            for (Long id : messages.findIdsInRecipientMailbox(mailbox.getId(), messageId)) {
                MailMessage copy = messageService.locked(id);
                if (copy == null || (copy.getStatus() != MessageStatus.SENT && copy.getStatus() != MessageStatus.DELIVERED)) {
                    continue;
                }
                Instant now = clock.instant();
                copy.setStatus(MessageStatus.READ);
                copy.setReadAt(now);
                if (copy.getDeliveredAt() == null) copy.setDeliveredAt(now);
                messageService.changed(copy);
            }
        });
    }

    /**
     * One message, looked at on its own. A failure that is the message's own does not improve on the
     * next run, and holding up the mailbox for it would stop all receiving, so the message is passed
     * over with a warning. Anything that would fail for every message stops the run instead, which
     * keeps its place.
     */
    private void handle(MailConnection mailbox, GmailClient.MessageRef message, Counts counts) {
        // Labels from the listing, when it has them, and the id checks spare any call.
        if (skipped(message.labelIds()) || known(mailbox, message.id())) return;
        GmailClient.MessageInfo info = null;
        GmailClient.RawMessage raw;
        try {
            // Its labels now, before a download that can run to tens of megabytes.
            info = gmail.messageInfo(mailbox, message.id());
            if (info == null || skipped(info.labelIds())) return;
            raw = gmail.rawMessage(mailbox, message.id());
        } catch (GmailApiException e) {
            if (e.status() == 404) return; // deleted since it arrived
            if (!refusedForTheMessageAlone(mailbox, e)) throw e;
            log.warn("Skipping Gmail message {}{}, which Gmail did not hand over: {}", message.id(),
                    info == null || info.sizeEstimate() == null ? "" : " of " + info.sizeEstimate() + " bytes",
                    e.getMessage());
            return;
        }
        if (raw == null || raw.raw() == null) return;
        counts.fetched++;

        IncomingMail mail;
        Optional<DsnParser.Report> report;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw.raw());
            mail = GmailMime.parse(message.id(), message.threadId() != null ? message.threadId() : raw.threadId(),
                    receivedAt(raw.internalDate()), bytes);
            report = DsnParser.parse(bytes);
        } catch (MessagingException | RuntimeException e) {
            // Nothing but the message's bytes goes into reading it.
            log.warn("Skipping Gmail message {}, which could not be read: {}", message.id(), e.getMessage());
            return;
        }
        Boolean imported;
        try {
            imported = transactions.execute(status -> record(mailbox, mail, report.orElse(null)));
        } catch (DataIntegrityViolationException e) {
            // Dealt with at the same moment by another instance of the service.
            log.info("Gmail message {} was already dealt with", message.id());
            return;
        } catch (RuntimeException e) {
            if (!failedForTheMessageAlone(e, mailbox)) throw e;
            log.warn("Skipping Gmail message {}, which could not be recorded", message.id(), e);
            return;
        }
        if (Boolean.TRUE.equals(imported)) counts.imported++;
    }

    /**
     * A bounce updates its copy; a delay changes nothing; anything else is a reply to the newest copy
     * in the thread (§4.7 step 5). Returns whether it was a bounce or a reply.
     */
    private boolean record(MailConnection mailbox, IncomingMail mail, DsnParser.Report report) {
        String threadId = mail.providerThreadId();
        List<MailMessage> thread = threadId == null ? List.of() : messages.findInThread(mailbox.getId(), threadId);
        if (report != null) {
            MailMessage copy = reportedCopy(mailbox, report, thread);
            if (report.failed()) {
                if (copy == null) {
                    log.warn("A delivery failure notice {} in the mailbox of {} names no copy the service sent",
                            mail.providerMessageId(), mailbox.getOwnerRef());
                    saveInbound(mailbox, mail, MailInbound.Kind.SKIPPED, null);
                    return false;
                }
                bounced(copy.getId(), report, mail.receivedAt());
                saveInbound(mailbox, mail, MailInbound.Kind.BOUNCE, copy.getId());
                return true;
            }
            if (!report.delayed()) {
                log.info("Gmail message {} is a delivery report that reports no failure; left alone", mail.providerMessageId());
            }
            saveInbound(mailbox, mail, report.delayed() ? MailInbound.Kind.DELAY : MailInbound.Kind.SKIPPED,
                    copy == null ? null : copy.getId());
            return false;
        }
        MailMessage repliedTo = thread.isEmpty() ? null : thread.get(0);
        if (repliedTo == null) {
            saveInbound(mailbox, mail, MailInbound.Kind.SKIPPED, null);
            return false;
        }
        events.record(EventRecorder.MESSAGE_RECEIVED, mailbox.getOwnerRef(), repliedTo.getExternalId(),
                ReceivedMail.of(mailbox, repliedTo, mail));
        saveInbound(mailbox, mail, MailInbound.Kind.REPLY, repliedTo.getId());
        return true;
    }

    /**
     * The copy a notice is about: the one whose Message-ID it quotes, else the thread's copy to an
     * address that failed, else the thread's only copy.
     */
    private MailMessage reportedCopy(MailConnection mailbox, DsnParser.Report report, List<MailMessage> thread) {
        if (report.originalMessageId() != null) {
            Optional<MailMessage> byId = messages.findFirstByConnectionIdAndRfcMessageIdOrderByIdDesc(
                    mailbox.getId(), report.originalMessageId());
            if (byId.isPresent()) return byId.get();
        }
        Set<String> failed = report.failedAddresses();
        for (MailMessage copy : thread) {
            if (failed.contains(copy.getToAddressKey())) return copy;
        }
        return thread.size() == 1 ? thread.get(0) : null;
    }

    /** SENT, or DELIVERED only by estimate, becomes BOUNCED; a copy seen in its recipient's mailbox stays. */
    private void bounced(Long id, DsnParser.Report report, Instant noticeAt) {
        MailMessage copy = messageService.locked(id);
        if (copy == null) return;
        boolean bounceable = copy.getStatus() == MessageStatus.SENT
                || (copy.getStatus() == MessageStatus.DELIVERED && !copy.isDeliveredConfirmed());
        if (!bounceable) return;
        copy.setStatus(MessageStatus.BOUNCED);
        copy.setBouncedAt(noticeAt);
        copy.setError(report.error(copy.getToAddressKey()));
        messageService.changed(copy);
    }

    private void saveInbound(MailConnection mailbox, IncomingMail mail, MailInbound.Kind kind, Long copyId) {
        inbound.saveAndFlush(MailInbound.builder()
                .connectionId(mailbox.getId())
                .providerMessageId(mail.providerMessageId())
                .providerThreadId(mail.providerThreadId())
                .kind(kind)
                .messageId(copyId)
                .createdAt(clock.instant())
                .build());
    }

    private boolean known(MailConnection mailbox, String providerMessageId) {
        return Boolean.TRUE.equals(transactions.execute(status ->
                inbound.existsByConnectionIdAndProviderMessageId(mailbox.getId(), providerMessageId)
                        || messages.existsByProviderMessageId(providerMessageId)));
    }

    /**
     * Whether Gmail's refusal concerns this message alone. Not when Gmail is busy or out of reach, when
     * it refuses the mailbox's access (401, 403), or when it no longer answers for the mailbox at all:
     * those hold for every message, and passing each over would lose them.
     */
    private boolean refusedForTheMessageAlone(MailConnection mailbox, GmailApiException e) {
        if (e.isTransientFailure() || e.status() == 401 || e.status() == 403) return false;
        try {
            gmail.profile(mailbox);
            return true;
        } catch (GmailApiException mailboxFailing) {
            return false;
        }
    }

    /** Whether recording failed for this message alone: the database still answers and did not ask to try again. */
    private boolean failedForTheMessageAlone(RuntimeException e, MailConnection mailbox) {
        if (e instanceof TransientDataAccessException) return false;
        try {
            known(mailbox, "");
            return true;
        } catch (RuntimeException storeFailing) {
            return false;
        }
    }

    private String currentHistoryId(MailConnection mailbox) {
        GmailClient.Profile profile = gmail.profile(mailbox);
        if (profile == null || profile.historyId() == null || profile.historyId().isBlank()) {
            throw new GmailApiException("Gmail did not say where the mailbox's history stands", 0, false, null);
        }
        return profile.historyId();
    }

    /** A run that finished; when the one before had failed, the backend hears the error is gone. */
    private void finish(MailConnection mailbox, String historyId) {
        transactions.executeWithoutResult(status -> {
            int updated = connections.recordSync(mailbox.getId(), mailbox.getVersion(), historyId, clock.instant());
            if (updated == 1 && mailbox.getLastSyncError() != null) announce(mailbox);
        });
    }

    /**
     * A run that stopped. The backend shows the owner the last error (the Inbox banner), so it hears
     * of one that appears or changes — not of every run that fails the same way.
     */
    private void recordError(MailConnection mailbox, String error, boolean announce) {
        String stored = MailText.fit(error, MailConnection.REASON_MAX);
        try {
            transactions.executeWithoutResult(status -> {
                int updated = connections.recordSyncError(mailbox.getId(), mailbox.getVersion(), stored);
                if (announce && updated == 1 && !Objects.equals(stored, mailbox.getLastSyncError())) announce(mailbox);
            });
        } catch (RuntimeException e) {
            log.warn("Could not record the Gmail sync error of {}: {}", mailbox.getOwnerRef(), e.getMessage());
        }
    }

    /** {@code connection.status} with the connection as it now stands; inside the transaction of the change. */
    private void announce(MailConnection mailbox) {
        connections.findById(mailbox.getId()).ifPresent(c ->
                events.record(EventRecorder.CONNECTION_STATUS, c.getOwnerRef(), null, ConnectionDto.of(c)));
    }

    private static boolean skipped(List<String> labelIds) {
        return labelIds != null && labelIds.stream().anyMatch(SKIPPED_LABELS::contains);
    }

    private static boolean setAside(List<String> labelIds) {
        return labelIds != null && labelIds.stream().anyMatch(SET_ASIDE_LABELS::contains);
    }

    private static Instant receivedAt(String internalDate) {
        if (internalDate == null || internalDate.isBlank()) return null;
        try {
            return Instant.ofEpochMilli(Long.parseLong(internalDate.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
