package com.geneinvoice.mail.message;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

    private final MessageService messages;

    public MessageController(MessageService messages) {
        this.messages = messages;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmitRequest.Response submit(@RequestBody SubmitRequest request) {
        return new SubmitRequest.Response(messages.submit(request));
    }

    @GetMapping("/{externalId}")
    public CopyState get(@PathVariable String externalId) {
        return messages.get(externalId);
    }
}
