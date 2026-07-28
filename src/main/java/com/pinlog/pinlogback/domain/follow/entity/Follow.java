package com.pinlog.pinlogback.domain.follow.entity;

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
 * Shelf 팔로우 관계(데이터모델 2.8). Shelf는 물리 테이블이 없으므로 member를 직접 참조하고,
 * 그 덕에 자기 팔로우 금지가 DB CHECK로 보장된다.
 *
 * <p>display_name(별칭)은 Shelf가 아니라 팔로우 관계에 속한다 — 같은 Shelf도 팔로워마다
 * 다른 이름을 가질 수 있고, 지정한 본인만 볼 수 있다.
 */
@Entity
@Table(name = "follow", schema = "core")
@SQLDelete(sql = "UPDATE core.follow SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Follow extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "followee_member_id", nullable = false, updatable = false)
	private Long followeeMemberId;

	@Column(name = "follower_member_id", nullable = false, updatable = false)
	private Long followerMemberId;

	@Column(name = "display_name", length = 20)
	private String displayName;

	protected Follow() {
	}

	private Follow(Long followeeMemberId, Long followerMemberId) {
		this.followeeMemberId = followeeMemberId;
		this.followerMemberId = followerMemberId;
	}

	public static Follow create(Long followeeMemberId, Long followerMemberId) {
		return new Follow(followeeMemberId, followerMemberId);
	}

	public Long getId() {
		return id;
	}

	public Long getFolloweeMemberId() {
		return followeeMemberId;
	}

	public Long getFollowerMemberId() {
		return followerMemberId;
	}

	public String getDisplayName() {
		return displayName;
	}

	public boolean isOwnedBy(Long candidateMemberId) {
		return followerMemberId.equals(candidateMemberId);
	}

	/**
	 * 별칭 수정·제거. 저장 전 정규화(공백 제거, 빈 값은 null)는 서비스 계층이 담당한다.
	 */
	public void changeDisplayName(String newDisplayName) {
		this.displayName = newDisplayName;
	}
}
