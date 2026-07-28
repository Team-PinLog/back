package com.pinlog.pinlogback.domain.record.entity;

import java.time.Instant;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.annotation.LastModifiedDate;

import com.pinlog.pinlogback.global.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 한 User와 한 Place의 연결 단위(데이터모델 2.4). 활성 Context를 최소 1개 가져야 한다(애플리케이션 보장).
 *
 * <p>연관관계 대신 Long FK 컬럼으로 매핑한다. 서비스가 사용자 식별자를 memberId 파라미터로 받는
 * 구조(BD-14)와 정합하고, 잠금·집계 쿼리를 단순하게 유지한다.
 */
@Entity
@Table(name = "record", schema = "core")
@SQLDelete(sql = "UPDATE core.record SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Record extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false, updatable = false)
	private Long memberId;

	@Column(name = "place_id", nullable = false, updatable = false)
	private Long placeId;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Record() {
	}

	private Record(Long memberId, Long placeId) {
		this.memberId = memberId;
		this.placeId = placeId;
	}

	public static Record create(Long memberId, Long placeId) {
		return new Record(memberId, placeId);
	}

	public Long getId() {
		return id;
	}

	public Long getMemberId() {
		return memberId;
	}

	public Long getPlaceId() {
		return placeId;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public boolean isOwnedBy(Long candidateMemberId) {
		return memberId.equals(candidateMemberId);
	}

	/**
	 * Context 추가·수정 시 최신 활동 시각을 기록한다(API 명세 14장 15번).
	 * {@code @LastModifiedDate}는 엔티티가 dirty일 때만 갱신되므로 명시적으로 찍는다.
	 */
	public void touch() {
		this.updatedAt = Instant.now();
	}
}
