package com.pinlog.pinlogback.domain.member.entity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.jspecify.annotations.Nullable;

import com.pinlog.pinlogback.global.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 소셜 인증 수단. 신원(member)과 분리해 보관한다(docs/static/06_데이터모델_및_무결성.md 2.2).
 *
 * <p>식별은 (provider, provider_user_id) 조합이다. 공급자 간 값이 충돌할 수 있어 복합으로 두며,
 * 유니크는 활성행에만 걸린다 — 전체 유니크면 탈퇴 후 같은 계정으로 재가입할 수 없다.
 *
 * <p>탈퇴 시 provider_user_id와 email을 마스킹해야 한다. 그때
 * {@code repository.delete()}를 쓰면 안 된다 — Hibernate가 엔티티를 removed 상태로 보아
 * 마스킹 변경이 flush되지 않고, {@code @SQLRestriction} 때문에 다시 조회할 수도 없다.
 * 마스킹과 deleted_at을 한 UPDATE에 담는 도메인 메서드를 쓴다(탈퇴 티켓에서 추가).
 */
@Entity
@Table(name = "social_account", schema = "core")
@SQLDelete(sql = "UPDATE core.social_account SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class SocialAccount extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false, updatable = false)
	private Member member;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, length = 20, updatable = false)
	private SocialProvider provider;

	@Column(name = "provider_user_id", nullable = false, length = 255)
	private String providerUserId;

	@Column(name = "email", length = 255)
	private String email;

	protected SocialAccount() {
	}

	private SocialAccount(Member member, SocialProvider provider, String providerUserId, String email) {
		this.member = member;
		this.provider = provider;
		this.providerUserId = providerUserId;
		this.email = email;
	}

	/**
	 * @param email 공급자가 제공하지 않거나 사용자가 동의하지 않으면 null이다.
	 */
	public static SocialAccount create(
		Member member, SocialProvider provider, String providerUserId, @Nullable String email) {
		return new SocialAccount(member, provider, providerUserId, email);
	}

	public Long getId() {
		return id;
	}

	public Member getMember() {
		return member;
	}

	public SocialProvider getProvider() {
		return provider;
	}

	public String getProviderUserId() {
		return providerUserId;
	}

	public String getEmail() {
		return email;
	}
}
