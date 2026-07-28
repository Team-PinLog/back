package com.pinlog.pinlogback.domain.collection.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.collection.entity.Collection;

public interface CollectionRepository extends JpaRepository<Collection, Long> {
}
