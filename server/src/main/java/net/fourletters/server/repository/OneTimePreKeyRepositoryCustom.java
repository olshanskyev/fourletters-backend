package net.fourletters.server.repository;

import net.fourletters.server.model.OneTimePreKey;

import java.util.List;

public interface OneTimePreKeyRepositoryCustom {

    /** Insert all rows as new entities (no existence check / no per-row SELECT). */
    void insertAll(List<OneTimePreKey> preKeys);
}
