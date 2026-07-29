# BT-05. `.env`의 빈 값이 `application.yml` 기본값을 덮어 기동이 실패한다

- **상태**: 해결됨
- **발견**: 2026-07-29, Kakao·Naver 등록정보를 추가하고 테스트를 돌리다가 (S15P11A705-64)
- **관련**: [설정 규약](../../development/configuration.md) · [BI-24](../implements/BI-24-2026-07-29-kakao-naver-login.md)

## 증상

`application.yml`에 Kakao·Naver 등록정보를 추가한 뒤 테스트를 돌리자 **이미 통과하던 테스트 11개가 통째로 실패**했다. 실패 대상이 새로 쓴 것이 아니라 `SocialLoginRedirectTests`·`RefreshTokenStoreTest`처럼 무관한 클래스였다.

```
Caused by: java.lang.IllegalStateException:
    Client id of registration 'kakao' must not be empty.
```

컨텍스트 자체가 뜨지 못했다. 반면 스텁을 쓰는 콜백 테스트는 통과했다 — 그쪽은 `@DynamicPropertySource`가 값을 덮어쓰기 때문이다.

## 원인

**Spring Boot 4는 작업 디렉터리의 `.env`를 자동으로 읽는다.** 그리고 `.env.example`이 키를 **빈 값으로 정의**하고 있었다.

```
KAKAO_CLIENT_ID=
```

이 파일을 복사해 만든 `.env`가 있으면 `KAKAO_CLIENT_ID`는 **"없음"이 아니라 "빈 문자열"** 이 된다. `${KAKAO_CLIENT_ID:unset}`의 기본값은 프로퍼티를 **해석할 수 없을 때만** 적용되므로, 빈 문자열은 그대로 통과해 OAuth2 클라이언트 검증에서 걸린다.

Google이 멀쩡했던 것은 그 값만 실제로 채워져 있었기 때문이다. **즉 이 함정은 Kakao·Naver를 추가하면서 생긴 것이 아니라, 원래 있었는데 드러나지 않았을 뿐이다** — `.env.example`을 그대로 복사한 사람은 처음부터 앱을 띄울 수 없었다.

## 해결

`.env.example`의 자격증명 6줄을 **주석으로** 바꿨다. 키 이름은 그대로 보이므로 발견성은 유지되고, 복사해도 프로퍼티가 정의되지 않아 기본값이 적용된다.

```
#GOOGLE_CLIENT_ID=
#KAKAO_CLIENT_ID=
...
```

쓸 공급자만 주석을 풀고 값을 채우면 된다. 이유를 파일 안에 함께 적었다.

## 재발 방지

- **빈 값으로 두지 말고 줄 자체를 없앤다**가 이 저장소의 규칙이 됐다. `.env.example`이 그 이유를 담고 있다.
- **운영에도 그대로 적용된다.** Kubernetes Secret에 키를 만들되 값을 비워 주입하면 같은 이유로 파드가 기동하지 않는다. 아직 자격증명을 받지 못한 공급자는 **주입 자체를 하지 않아야** 한다 — 인프라에 전달할 항목이다.
- CI는 이 함정에 걸리지 않는다. `.env`가 `.gitignore` 대상이라 러너에 존재하지 않고, 기본값이 정상 적용된다. **로컬에서만 터지므로 CI가 잡아 주지 않는다**는 점이 이 문제의 성질이다.

## 곁가지 — 자격증명 백업 파일이 추적 대상이었다

원인을 찾는 동안 `.env`를 고치기 전에 `.env.bak-<sha>`로 백업했는데, `.gitignore`가 **정확히 `.env`만** 무시하고 있어 그 파일이 추적 대상이었다. 실제 Google 자격증명이 담긴 파일이다. 즉시 지우고 규칙을 `.env*`로 넓혔다(`!.env.example`은 유지). `.env.local` 같은 파생 이름도 이제 막힌다.
