package com.geneinvoice.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

/**
 * One application-wide setting. The first and so far only one is the global admin email used as
 * the send-time From fallback, keyed {@link AppSettingKeys#ADMIN_EMAIL}.
 */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 80)
    private String key;

    @Column(name = "setting_value", length = 200)
    private String value;
}
