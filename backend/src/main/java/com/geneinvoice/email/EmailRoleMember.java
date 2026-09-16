package com.geneinvoice.email;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Someone who was in a role when an email went to that role. */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EmailRoleMember {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 120, nullable = false)
    private String name;

    @Column(length = 120)
    private String address;
}
