package com.pinlog.pinlogback.domain.record.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.record.entity.Context;

public interface ContextRepository extends JpaRepository<Context, Long> {
}
