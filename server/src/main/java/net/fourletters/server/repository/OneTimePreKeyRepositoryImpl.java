package net.fourletters.server.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import net.fourletters.server.model.OneTimePreKey;

import java.util.List;

public class OneTimePreKeyRepositoryImpl implements OneTimePreKeyRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void insertAll(List<OneTimePreKey> preKeys) {
        for (OneTimePreKey preKey : preKeys) {
            entityManager.persist(preKey);
        }
    }
}
