package com.geneinvoice.email.connection;

import com.geneinvoice.common.ApiError;
import com.geneinvoice.email.connection.GmailConnectionDtos.ConnectGmailRequest;
import com.geneinvoice.email.connection.GmailConnectionDtos.GmailConnectionDto;
import com.geneinvoice.email.connection.GmailConnectionDtos.UserGmailDto;
import com.geneinvoice.email.transport.MailConnectException;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class GmailConnectionController {

    private final GmailConnectionService service;

    @GetMapping("/api/me/gmail")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public GmailConnectionDto mine() {
        return service.mine();
    }

    @PutMapping("/api/me/gmail")
    @PreAuthorize("hasAuthority('" + Privileges.EMAIL_SEND + "')")
    public GmailConnectionDto connect(@RequestBody ConnectGmailRequest req) {
        return service.connect(req);
    }

    @DeleteMapping("/api/me/gmail")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> disconnect() {
        service.disconnect();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/users/{id}/gmail")
    @PreAuthorize("hasAuthority('" + Privileges.USER_VIEW + "')")
    public UserGmailDto ofUser(@PathVariable Long id) {
        return service.ofUser(id);
    }

    @DeleteMapping("/api/users/{id}/gmail")
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public ResponseEntity<Void> disconnectUser(@PathVariable Long id) {
        service.disconnectUser(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MailConnectException.class)
    public ResponseEntity<ApiError> connectFailed(MailConnectException e, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(e.getHttpStatus());
        if (status == null) status = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(
                ApiError.of(status.value(), status.getReasonPhrase(), e.getMessage(), req.getRequestURI()));
    }
}
