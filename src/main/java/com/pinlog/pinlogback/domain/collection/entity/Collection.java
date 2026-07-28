package com.pinlog.pinlogback.domain.collection.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Record를 묶어 공개하는 단위(데이터모델 2.6). 생성 즉시 자동 발행된다(BD-23).
 *
 * <p>record_count는 활성 연결 수의 비정규화 값이다. 연결 추가·제거와 동일 트랜잭션에서 갱신한다.
 * 소유자는 member_id 하나이며 shelf_id를 두지 않는다(BD-15).
 */
@Entity
@Table(name = "collection", schema = "core")
@SQLDelete(sql = "UPDATE core.collection SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Collection extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false, updatable = false)
	private Long memberId;

	@Column(name = "title", nullable = false, length = 20)
	private String title;

	@Column(name = "is_published", nullable = false)
	private boolean isPublished;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Column(name = "record_count", nullable = false)
	private int recordCount;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Collection() {
	}

	private Collection(Long memberId, String title) {
		this.memberId = memberId;
		this.title = title;
		this.isPublished = true;
	}

	public static Collection create(Long memberId, String title) {
		return new Collection(memberId, title);
	}

	/**
	 * 생성 즉시 자동 발행이므로(BD-23) 발행 시각을 생성 시각과 같은 값으로 채운다.
	 * BaseEntity의 AuditingEntityListener가 이 콜백보다 먼저 실행되어 created_at이 이미 채워져 있다.
	 */
	@PrePersist
	void publishOnCreation() {
		if (publishedAt == null) {
			publishedAt = getCreatedAt();
		}
	}

	public Long getId() {
		return id;
	}

	public Long getMemberId() {
		return memberId;
	}

	public String getTitle() {
		return title;
	}

	public boolean isPublished() {
		return isPublished;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public int getRecordCount() {
		return recordCount;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public boolean isOwnedBy(Long candidateMemberId) {
		return memberId.equals(candidateMemberId);
	}

	public void rename(String newTitle) {
		this.title = newTitle;
	}

	public void increaseRecordCount(int delta) {
		this.recordCount += delta;
	}

	public void decreaseRecordCount(int delta) {
		this.recordCount -= delta;
	}
}
