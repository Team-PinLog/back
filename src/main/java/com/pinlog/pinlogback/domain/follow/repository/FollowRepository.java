package com.pinlog.pinlogback.domain.follow.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pinlog.pinlogback.domain.follow.entity.Follow;

public interface FollowRepository extends JpaRepository<Follow, Long> {
}
