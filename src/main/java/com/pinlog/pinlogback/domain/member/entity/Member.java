package com.pinlog.pinlogback.domain.member.entity;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import com.pinlog.pinlogback.global.common.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 회원. 익명 서비스이므로 개인정보 컬럼이 없다(docs/static/06_데이터모델_및_무결성.md 2.1).
 * 인증 수단은 social_account가 소유하며 인증 PR에서 추가한다.
 */
@Entity
@Table(name = "member", schema = "core")
@SQLDelete(sql = "UPDATE core.member SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
public class Member extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	protected Member() {
	}

	public static Member create() {
		return new Member();
	}

	public Long getId() {
		return id;
	}
}
