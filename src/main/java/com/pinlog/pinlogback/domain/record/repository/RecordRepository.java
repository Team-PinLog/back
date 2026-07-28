package com.pinlog.pinlogback.domain.record.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.record.entity.Record;

public interface RecordRepository extends JpaRepository<Record, Long> {
}
