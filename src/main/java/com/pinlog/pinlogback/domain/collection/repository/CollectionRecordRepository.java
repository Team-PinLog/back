package com.pinlog.pinlogback.domain.collection.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.collection.entity.CollectionRecord;

public interface CollectionRecordRepository extends JpaRepository<CollectionRecord, Long> {
}
