package com.cadence.catalog.domain;

import com.cadence.events.UuidV7;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "genres")
public class Genre {

    @Id
    private UUID id;
    private String name;

    protected Genre() {
    }

    public Genre(String name) {
        this.id = UuidV7.generate();
        this.name = name.strip();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
