package com.geneinvoice.mail.tracking;

final class Bounces {

    private Bounces() {}

    static String crlf(String... lines) {
        return String.join("\r\n", lines);
    }

    static String gmailFailure(String failedAddress, String originalMessageId) {
        return crlf(
                "Return-Path: <>",
                "From: Mail Delivery Subsystem <mailer-daemon@googlemail.com>",
                "To: jane@gmail.com",
                "Auto-Submitted: auto-replied",
                "Subject: Delivery Status Notification (Failure)",
                "References: " + originalMessageId,
                "In-Reply-To: " + originalMessageId,
                "X-Failed-Recipients: " + failedAddress,
                "Message-ID: <66ed1c2a.050a0220.1b7e3.2f1c.GMR@mx.google.com>",
                "Date: Sun, 20 Sep 2026 03:05:14 -0700 (PDT)",
                "MIME-Version: 1.0",
                "Content-Type: multipart/report; boundary=\"000000000000a1b2c3\"; report-type=delivery-status",
                "",
                "--000000000000a1b2c3",
                "Content-Type: multipart/related; boundary=\"000000000000a1b2c4\"",
                "",
                "--000000000000a1b2c4",
                "Content-Type: multipart/alternative; boundary=\"000000000000a1b2c5\"",
                "",
                "--000000000000a1b2c5",
                "Content-Type: text/plain; charset=\"UTF-8\"",
                "",
                "",
                "** Address not found **",
                "",
                "Your message wasn't delivered to " + failedAddress + " because the address couldn't be found, or is"
                        + " unable to receive mail.",
                "",
                "The response was:",
                "",
                "550 5.1.1 The email account that you tried to reach does not exist.",
                "",
                "--000000000000a1b2c5",
                "Content-Type: text/html; charset=\"UTF-8\"",
                "",
                "<html><body><p><b>Address not found</b></p></body></html>",
                "--000000000000a1b2c5--",
                "--000000000000a1b2c4",
                "Content-Type: image/png; name=\"icon.png\"",
                "Content-Disposition: attachment; filename=\"icon.png\"",
                "Content-Transfer-Encoding: base64",
                "Content-ID: <icon.png>",
                "",
                "iVBORw0KGgo=",
                "--000000000000a1b2c4--",
                "--000000000000a1b2c3",
                "Content-Type: message/delivery-status",
                "",
                "Reporting-MTA: dns; googlemail.com",
                "Received-From-MTA: dns; jane@gmail.com",
                "Arrival-Date: Sun, 20 Sep 2026 03:05:12 -0700 (PDT)",
                "X-Original-Message-ID: " + originalMessageId,
                "",
                "Final-Recipient: rfc822; " + failedAddress,
                "Action: failed",
                "Status: 5.1.1",
                "Remote-MTA: dns; mx.acme.com. (203.0.113.5, the server for the domain acme.com.)",
                "Diagnostic-Code: smtp; 550-5.1.1 The email account that you tried to reach does not exist. Please try",
                " 550-5.1.1 double-checking the recipient's email address for typos or",
                " 550 5.1.1 unnecessary spaces. https://support.google.com/mail/?p=NoSuchUser",
                "Last-Attempt-Date: Sun, 20 Sep 2026 03:05:14 -0700 (PDT)",
                "",
                "--000000000000a1b2c3",
                "Content-Type: message/rfc822",
                "",
                "From: Jane Doe <jane@gmail.com>",
                "To: Bob Smith <" + failedAddress + ">",
                "Subject: Invoice INV-0042",
                "Message-ID: " + originalMessageId,
                "Date: Sun, 20 Sep 2026 03:05:10 -0700",
                "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=UTF-8",
                "",
                "Please pay.",
                "--000000000000a1b2c3--",
                "");
    }

    static final String GMAIL_FAILURE_ERROR = "5.1.1 550-5.1.1 The email account that you tried to reach does not"
            + " exist. Please try 550-5.1.1 double-checking the recipient's email address for typos or 550 5.1.1"
            + " unnecessary spaces. https://support.google.com/mail/?p=NoSuchUser";

    static String gmailDelay(String address, String originalMessageId) {
        return crlf(
                "From: Mail Delivery Subsystem <mailer-daemon@googlemail.com>",
                "To: jane@gmail.com",
                "Subject: Delivery Status Notification (Delay)",
                "Message-ID: <delay-1@mx.google.com>",
                "MIME-Version: 1.0",
                "Content-Type: multipart/report; boundary=\"b1\"; report-type=\"delivery-status\"",
                "",
                "--b1",
                "Content-Type: text/plain; charset=UTF-8",
                "",
                "Delivery incomplete. Gmail will retry for 46 more hours.",
                "--b1",
                "Content-Type: message/delivery-status",
                "",
                "Reporting-MTA: dns; googlemail.com",
                "",
                "Final-Recipient: rfc822; " + address,
                "Action: delayed",
                "Status: 4.4.1",
                "Diagnostic-Code: smtp; The recipient server did not accept our requests to connect.",
                "Will-Retry-Until: Tue, 22 Sep 2026 03:05:14 -0700 (PDT)",
                "",
                "--b1",
                "Content-Type: text/rfc822-headers",
                "",
                "From: Jane Doe <jane@gmail.com>",
                "To: " + address,
                "Message-ID: " + originalMessageId,
                "",
                "--b1--",
                "");
    }

    static String eximFailure(String failedAddress) {
        return crlf(
                "From: Mail Delivery System <Mailer-Daemon@mx.acme.com>",
                "To: jane@gmail.com",
                "Subject: Mail delivery failed: returning message to sender",
                "X-Failed-Recipients: " + failedAddress,
                "Message-ID: <exim-1@mx.acme.com>",
                "Content-Type: text/plain; charset=us-ascii",
                "",
                "This message was created automatically by mail delivery software.",
                "",
                "A message that you sent could not be delivered to one or more of its recipients.",
                "",
                "  " + failedAddress,
                "    mailbox is full",
                "");
    }

    static String headersOnlyFailure(String address, String originalMessageId) {
        return crlf(
                "From: postmaster@mx.acme.com",
                "To: jane@gmail.com",
                "Subject: Undeliverable",
                "Content-Type: multipart/report; report-type=delivery-status; boundary=\"r\"",
                "",
                "--r",
                "Content-Type: text/plain",
                "",
                "Undeliverable.",
                "--r",
                "Content-Type: message/delivery-status",
                "",
                "Reporting-MTA: dns;mx.acme.com",
                "",
                "Original-Recipient: rfc822;<" + address + ">",
                "Action: failed",
                "Status: 5.2.2",
                "Diagnostic-Code: X-Postfix; mailbox full",
                "",
                "--r",
                "Content-Type: text/rfc822-headers",
                "",
                "Message-ID: " + originalMessageId,
                "Subject: Invoice",
                "",
                "--r--",
                "");
    }

    static String reply(String from, String inReplyTo) {
        return crlf(
                "From: Bob Smith <" + from + ">",
                "To: Jane Doe <jane@gmail.com>",
                "Cc: ravi@acme.com",
                "Subject: Re: Invoice INV-0042",
                "Date: Sun, 20 Sep 2026 10:05:00 +0000",
                "Message-ID: <reply-1@mail.acme.com>",
                "In-Reply-To: " + inReplyTo,
                "References: " + inReplyTo,
                "MIME-Version: 1.0",
                "Content-Type: text/plain; charset=UTF-8",
                "",
                "Thanks, we will pay on Friday.",
                "");
    }
}
