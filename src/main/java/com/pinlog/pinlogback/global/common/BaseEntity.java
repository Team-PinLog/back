package com.pinlog.pinlogback.global.common;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

/**
 * 모든 core 테이블이 공유하는 created_at과, 대부분이 공유하는 deleted_at(soft delete)을 제공한다.
 *
 * <p>updated_at은 두지 않는다. 8개 core 테이블 중 place·record·collection 3개에만 있으므로,
 * 필요한 엔티티가 @LastModifiedDate 필드를 직접 선언한다.
 *
 * <p>soft delete의 실제 동작(@SQLDelete·@SQLRestriction)은 테이블명이 필요하므로 엔티티마다 명시한다.
 * deleted_at이 없는 테이블(place)은 이 클래스를 상속하지 않는다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	/**
	 * 조회에서 제외되도록 삭제 시각을 기록한다.
	 * 복원(restore)은 제공하지 않는다 — @SQLRestriction 때문에 삭제된 행을 다시 로드할 수 없어,
	 * 복원이 필요해지면 native 쿼리나 filter opt-out을 함께 도입해야 한다.
	 */
	public void softDelete() {
		this.deletedAt = Instant.now();
	}
}
