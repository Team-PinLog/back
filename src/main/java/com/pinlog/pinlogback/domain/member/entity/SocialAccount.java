package com.pinlog.pinlogback.domain.member.entity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

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
 * <p>탈퇴는 {@link #withdraw()}로 한다. <b>{@code repository.delete()}를 쓰면 안 된다</b> —
 * Hibernate가 엔티티를 removed 상태로 보아 마스킹 변경이 flush되지 않고, {@code @SQLRestriction}
 * 때문에 다시 조회해 고칠 수도 없다.
 */
@Entity
@Table(name = "social_account", schema = "core")
@SQLDelete(sql = "UPDATE core.social_account SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class SocialAccount extends BaseEntity {

	/** 마스킹 치환값 접두사. 원본을 되돌릴 수 없어야 하고 NULL이면 안 된다(06 §2.2). */
	private static final String MASK_PREFIX = "withdrawn:";

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

	@Column(name = "email", nullable = false, length = 255)
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
	 * @param email 필수다. 값 없는 계정을 두지 않으므로 정규화 계층이 먼저 끊는다(06 §2.2).
	 */
	public static SocialAccount create(
		Member member, SocialProvider provider, String providerUserId, String email) {
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

	/**
	 * 탈퇴 시 개인정보를 파기하고 소프트 삭제한다(06 §6.9). 두 변경이 한 UPDATE에 담겨야 하므로
	 * 도메인 메서드로 둔다 — {@code repository.delete()}를 쓰면 Hibernate가 엔티티를 removed로
	 * 보아 <b>마스킹이 flush되지 않고</b>, {@code @SQLRestriction} 때문에 다시 조회해 고칠 수도 없다.
	 *
	 * <p>마스킹은 <b>치환이며 {@code NULL}이 아니다</b> — 두 컬럼 모두 {@code NOT NULL}이다(06 §2.2).
	 * 치환값이 회원끼리 겹쳐도 무해하다. 유니크는 활성행만 대상이고({@code WHERE deleted_at IS NULL})
	 * 이 시점엔 이미 인덱스 밖이므로, id를 붙이는 것은 제약 회피가 아니라 운영 추적 편의다.
	 *
	 * <p>이메일 자리에 {@code .invalid}를 쓴다 — 예약 TLD(RFC 2606)라 어떤 경로로도 발송되지 않는다.
	 */
	public void withdraw() {
		this.providerUserId = MASK_PREFIX + id;
		this.email = MASK_PREFIX + id + "@deleted.invalid";
		softDelete();
	}
}
