package com.geneinvoice.mail.message;

import com.geneinvoice.mail.gmail.Attachment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Where an email's files are kept between the submit and the send: {@link MailAttachmentSet} and
 * its {@link MailAttachment} rows, looked up by what they hold rather than by who asked for them.
 *
 * <p>The copies of one email share a set, and so does a retry of that email, because the set is
 * found by a digest of the files themselves. That is not an optimisation for its own sake: without
 * it, one email to twenty people would store the same attachment twenty times, and every retry
 * would store it again and leave the old rows behind with nothing pointing at them.
 */
@Component
@Slf4j
public class AttachmentSets {

    private final MailAttachmentSetRepository sets;
    private final MailAttachmentRepository attachments;
    private final Clock clock;

    public AttachmentSets(MailAttachmentSetRepository sets, MailAttachmentRepository attachments, Clock clock) {
        this.sets = sets;
        this.attachments = attachments;
        this.clock = clock;
    }

    /**
     * The id of the set holding these files, storing it if it is not stored yet; null when there
     * are no files, which is what almost every email submits. Runs in the caller's transaction, so
     * the set is committed together with the copies that point at it — a copy can never be queued
     * with a set id that is not there.
     *
     * <p>Two submissions of the same files at the same moment race on the digest's unique index.
     * The loser's flush fails, its whole transaction rolls back, and {@code MessageService} runs
     * the save again; the second pass finds the winner's set. That is the same path an
     * {@code externalId} collision already takes.
     */
    public Long store(List<Attachment> files) {
        if (files == null || files.isEmpty()) return null;
        String digest = digest(files);
        Optional<MailAttachmentSet> stored = sets.findByDigest(digest);
        if (stored.isPresent()) return stored.get().getId();
        long total = files.stream().mapToLong(Attachment::sizeBytes).sum();
        // Flushed here so the unique digest is enforced now, while there is still a save to redo.
        MailAttachmentSet set = sets.saveAndFlush(MailAttachmentSet.builder()
                .digest(digest)
                .sizeBytes(total)
                .fileCount(files.size())
                .createdAt(clock.instant())
                .build());
        List<MailAttachment> rows = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            Attachment file = files.get(i);
            rows.add(MailAttachment.builder()
                    .setId(set.getId())
                    .ordinal(i)
                    .filename(file.filename())
                    .contentType(file.contentType())
                    .sizeBytes(file.sizeBytes())
                    .content(file.content())
                    .build());
        }
        attachments.saveAll(rows);
        log.debug("Stored attachment set {} ({} file(s), {} bytes)", set.getId(), files.size(), total);
        return set.getId();
    }

    /**
     * The files of a set, in order, ready to go into a message. Empty when the set id is null, and
     * also when the set holds nothing — which cannot happen for a set this class wrote, and which
     * the send worker treats as a failure rather than sending an email that quietly lost its files.
     */
    public List<Attachment> load(Long setId) {
        if (setId == null) return List.of();
        return attachments.findBySetIdOrderByOrdinalAsc(setId).stream()
                .map(a -> new Attachment(a.getFilename(), a.getContentType(), a.getContent()))
                .toList();
    }

    /**
     * SHA-256 over every file's name, type and bytes, in order, with the lengths mixed in so that
     * no two different sets can be run together into the same digest.
     */
    static String digest(List<Attachment> files) {
        MessageDigest sha = sha256();
        for (Attachment file : files) {
            field(sha, file.filename());
            field(sha, file.contentType());
            sha.update(length(file.content().length));
            sha.update(file.content());
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void field(MessageDigest sha, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        sha.update(length(bytes.length));
        sha.update(bytes);
    }

    private static byte[] length(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
