package com.geneinvoice.mail.connection;

import com.geneinvoice.mail.tracking.MailboxSync;
import com.geneinvoice.mail.tracking.SyncResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {

    private final ConnectionService connections;
    private final MailboxSync sync;

    public ConnectionController(ConnectionService connections, MailboxSync sync) {
        this.connections = connections;
        this.sync = sync;
    }

    @PutMapping("/{ownerRef}")
    public ConnectionDto connect(@PathVariable String ownerRef, @RequestBody ConnectRequest request) {
        return connections.connect(ownerRef, request);
    }

    @GetMapping("/{ownerRef}")
    public ConnectionDto get(@PathVariable String ownerRef) {
        return connections.get(ownerRef);
    }

    @GetMapping
    public List<ConnectionDto> list() {
        return connections.list();
    }

    @DeleteMapping("/{ownerRef}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String ownerRef) {
        connections.disconnect(ownerRef);
    }

    @PostMapping("/{ownerRef}/sync")
    public SyncResult sync(@PathVariable String ownerRef) {
        return sync.syncNow(ownerRef);
    }
}
