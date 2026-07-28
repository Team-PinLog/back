package com.pinlog.pinlogback.domain.record.entity;

import java.time.Instant;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import com.pinlog.pinlogback.global.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 장소를 저장한 이유(데이터모델 2.5). 불변이라 수정은 UPDATE가 아니라 교체 생성이다(BD-07).
 *
 * <p>origin_created_at은 최초 작성 시각(BD-25)이다. 첫 생성은 자신의 created_at과 같고,
 * 교체 생성({@link #replacing})은 구 Context의 값을 승계한다. 목록 정렬·날짜 표시의 기준 컬럼이다.
 *
 * <p>member_id는 비정규화 컬럼이다. 자연어 검색이 항상 본인 맥락으로 한정되므로 Record 조인 없이
 * 후보를 좁히기 위해 둔다.
 */
@Entity
@Table(name = "context", schema = "core")
@SQLDelete(sql = "UPDATE core.context SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Context extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "record_id", nullable = false, updatable = false)
	private Long recordId;

	@Column(name = "member_id", nullable = false, updatable = false)
	private Long memberId;

	@Column(name = "body", nullable = false, updatable = false)
	private String body;

	@Column(name = "origin_created_at", nullable = false, updatable = false)
	private Instant originCreatedAt;

	protected Context() {
	}

	private Context(Long recordId, Long memberId, String body, Instant originCreatedAt) {
		this.recordId = recordId;
		this.memberId = memberId;
		this.body = body;
		this.originCreatedAt = originCreatedAt;
	}

	public static Context create(Long recordId, Long memberId, String body) {
		return new Context(recordId, memberId, body, null);
	}

	/**
	 * 교체 생성. 새 id·created_at을 갖되 최초 작성 시각은 구 Context에서 승계하므로
	 * 몇 번을 수정해도 목록 위치와 표시 시각이 바뀌지 않는다.
	 */
	public static Context replacing(Context old, String body) {
		return new Context(old.getRecordId(), old.getMemberId(), body, old.getOriginCreatedAt());
	}

	/**
	 * 첫 생성 시 origin_created_at을 자신의 created_at과 같은 값으로 채운다.
	 * BaseEntity의 AuditingEntityListener가 이 콜백보다 먼저 실행되어(JPA 리스너 우선 순서)
	 * created_at이 이미 채워져 있다.
	 */
	@PrePersist
	void fillOriginCreatedAtOnFirstCreation() {
		if (originCreatedAt == null) {
			originCreatedAt = getCreatedAt();
		}
	}

	public Long getId() {
		return id;
	}

	public Long getRecordId() {
		return recordId;
	}

	public Long getMemberId() {
		return memberId;
	}

	public String getBody() {
		return body;
	}

	public Instant getOriginCreatedAt() {
		return originCreatedAt;
	}
}
