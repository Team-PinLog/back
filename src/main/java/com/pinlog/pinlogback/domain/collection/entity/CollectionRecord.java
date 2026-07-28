package com.pinlog.pinlogback.domain.collection.entity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import com.pinlog.pinlogback.global.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Collection과 Record의 연결(데이터모델 2.7). Record는 Collection의 하위 데이터가 아니다 —
 * 어느 Collection에도 속하지 않은 Record가 있고, 한 Record가 여러 Collection에 담길 수 있다.
 *
 * <p>컬렉션 내부 정렬 기준은 이 테이블의 created_at DESC(담은 순서 최신순)다.
 */
@Entity
@Table(name = "collection_record", schema = "core")
@SQLDelete(sql = "UPDATE core.collection_record SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class CollectionRecord extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "collection_id", nullable = false, updatable = false)
	private Long collectionId;

	@Column(name = "record_id", nullable = false, updatable = false)
	private Long recordId;

	protected CollectionRecord() {
	}

	private CollectionRecord(Long collectionId, Long recordId) {
		this.collectionId = collectionId;
		this.recordId = recordId;
	}

	public static CollectionRecord create(Long collectionId, Long recordId) {
		return new CollectionRecord(collectionId, recordId);
	}

	public Long getId() {
		return id;
	}

	public Long getCollectionId() {
		return collectionId;
	}

	public Long getRecordId() {
		return recordId;
	}
}
