package com.app.nino.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "roles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;   // ROLE_USER, ROLE_ADMIN

    @Column(name = "description", length = 200)
    private String description;

    public enum RoleName {
        ROLE_USER, ROLE_ADMIN
    }
}
