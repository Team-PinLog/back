# 인가(Authorization) 상세

이 문서는 PinLog 백엔드의 인가 정책을 정리한다. 각 기능이 요구하는 Access Token 여부와
소유권·공개 범위 검증 규칙, HTTP 응답 기준을 다룬다.

## 1. 권한 단계

| 단계 | 의미 |
| --- | --- |
| `PUBLIC` | 로그인하지 않은 사용자도 접근 가능 |
| `AUTHENTICATED` | 유효한 Access Token이 필요 |
| `OWNER` | Access Token의 사용자와 리소스 소유자가 같아야 함 |
| `RELATION_OWNER` | 팔로우 관계 등 관계 데이터를 생성한 사용자만 접근 가능 |
| `PUBLIC_RESOURCE` | 공개 상태인 컬렉션처럼 다른 로그인 사용자도 조회 가능한 리소스 |

## 2. 공통 인증 규칙

### Access Token

일반적인 사용자 API 호출에 사용합니다.

```
Authorization: Bearer {accessToken}
```

토큰에서 다음 정보를 식별합니다.

```
currentUserId
tokenId(jti)
roles
```

### Refresh Token

Refresh Token은 일반 API 인가에 사용하지 않습니다.

사용 범위:

- Access Token 재발급
- 로그아웃 시 세션 폐기
- Redis에서 로그인 세션 확인

### 일회성 인증 토큰

다음 기능은 Access Token 없이 접근하지만 별도의 일회성 토큰을 사용할 수 있습니다.

- 이메일 인증
- 비밀번호 재설정
- 계정 복구

```
Access Token 없음
→ 본인 인증 수행
→ 일회성 인증 토큰 발급
→ 비밀번호 변경
```

## 3. 계정 기능

| 기능 | Access Token | 추가 인가 규칙 |
| --- | --- | --- |
| 회원가입 | 불필요 | 이메일 인증 또는 소셜 로그인 인증 결과 필요 |
| 로그인 | 불필요 | 아이디·비밀번호 또는 소셜 인증 정보 검증 |
| 로그아웃 | 필요 | 현재 사용자의 Redis 토큰 정보만 삭제 |
| 아이디 찾기 | 불필요 | 이메일·전화번호 등 별도 본인 인증 필요 |
| 비밀번호 찾기 | 불필요 | 일회성 비밀번호 재설정 토큰 필요 |
| 회원 탈퇴 | 필요 | 토큰 사용자 본인만 가능, 재인증 권장 |
| 회원 정보 수정 | 필요 | 토큰 사용자 본인의 정보만 수정 가능 |
| Access Token 재발급 | 불필요 | 유효한 Refresh Token 필요 |

### 회원 정보 조회·수정

가능하면 다음과 같이 `userId`를 받지 않는 방식이 좋습니다.

```
GET /api/users/me
PATCH /api/users/me
DELETE /api/users/me
```

서버는 토큰에서 사용자 ID를 추출합니다.

```java
currentUserId = authentication.getPrincipal().getId();
```

따라서 클라이언트가 다른 사용자의 ID를 임의로 전달할 여지가 없습니다.

부득이하게 사용자 ID를 경로에 넣는다면 다음 검증이 필요합니다.

```
토큰의 currentUserId == 요청 경로의 userId
```

일치하지 않으면 접근을 거부합니다.

## 4. 지도 기능

| 기능 | Access Token | 인가 규칙 |
| --- | --- | --- |
| 장소 검색 | 불필요 | 클라이언트가 카카오맵 API를 직접 호출 |
| 지도에 내 기록 마커 표시 | 필요 | 현재 사용자의 활성 레코드만 조회 |
| 마커 클릭 후 기록 표시 | 필요 | 해당 레코드의 소유자만 원문 조회 |
| 외부 링크로 장소 추가 | 필요 | 생성되는 레코드의 소유자는 현재 사용자로 고정 |
| 장소를 컬렉션에 추가 | 필요 | 레코드와 컬렉션 모두 현재 사용자 소유여야 함 |
| 입력 폼으로 기록 추가 | 필요 | 생성 요청의 사용자 ID를 신뢰하지 않고 토큰 사용자로 설정 |
| 자연어로 내 기록 검색 | 필요 | 현재 사용자의 맥락만 검색 |
| 컬렉션별 마커 설정 | 필요 | 해당 컬렉션의 소유자만 설정 가능 |

### 장소 검색

장소 검색은 핀로그 서버 인증과 별개입니다.

```
Client
→ Kakao Map API
→ 장소 검색 결과
```

장소를 선택하여 기록을 생성할 때부터 핀로그 Access Token이 필요합니다.

```
Client
→ Spring Boot API
→ Place 저장 또는 조회
→ Record 생성
```

## 5. 기록 인가 규칙

| 기능 | Access Token | 인가 규칙 |
| --- | --- | --- |
| 기록 생성 | 필요 | 현재 사용자를 레코드 소유자로 설정 |
| 기록 상세 조회 | 필요 | 레코드 소유자만 가능 |
| 기록 수정 | 필요 | 레코드 소유자만 가능 |
| 기록 삭제 | 필요 | 레코드 소유자만 가능 |
| 피드 장소를 내 기록에 추가 | 필요 | 현재 사용자의 새 레코드 또는 기존 레코드에 맥락 추가 |
| 내 기록 목록 조회 | 필요 | 현재 사용자 소유 레코드만 반환 |

### 소유권 검증

```
record.userId == currentUserId
```

예시:

```java
Record record = recordRepository.findActiveById(recordId)
    .orElseThrow(RecordNotFoundException::new);

if (!record.isOwnedBy(currentUserId)) {
    throw new RecordNotFoundException();
}
```

다른 사용자의 레코드와 맥락은 비공개이므로 외부 사용자에게 직접 노출하지 않습니다.

발행 컬렉션에서 다른 사용자에게 제공하는 데이터는 별도 공개 DTO를 사용합니다.

```
공개 가능:
- 장소 정보
- 마스킹된 키워드
- 기록 생성 시각

공개 불가:
- 맥락 원문
- 개인 메모
- 임베딩
- 레코드 소유자 신원
```

## 6. 컬렉션 인가 규칙

| 기능 | Access Token | 인가 규칙 |
| --- | --- | --- |
| 내 컬렉션 조회 | 필요 | 현재 사용자 소유 컬렉션만 조회 |
| 컬렉션 생성 | 필요 | 생성자는 현재 사용자로 고정 |
| 내 컬렉션 상세 조회 | 필요 | 소유자는 전체 정보 조회 가능 |
| 공개 컬렉션 조회 | 필요 | 공개 상태이고 소유자가 탈퇴하지 않은 컬렉션 |
| 컬렉션 하위 기록 조회 | 필요 | 소유자는 원본 정보, 타인은 공개 정보만 조회 |
| 컬렉션 수정 | 필요 | 컬렉션 소유자만 가능 |
| 컬렉션 삭제 | 필요 | 컬렉션 소유자만 가능 |
| 공개·비공개 전환 | 필요 | 컬렉션 소유자만 가능 |
| 컬렉션 키워드 갱신 | 필요 | 소유자 또는 내부 시스템만 가능 |
| 컬렉션에 레코드 추가 | 필요 | 컬렉션과 레코드가 모두 현재 사용자 소유 |
| 컬렉션에서 레코드 제거 | 필요 | 컬렉션 소유자만 가능 |

### 컬렉션 소유권 검증

```
collection.ownerId == currentUserId
```

### 컬렉션에 기록 추가

두 리소스에 대한 소유권을 모두 검사해야 합니다.

```
collection.ownerId == currentUserId
AND
record.ownerId == currentUserId
```

다른 사용자의 개인 레코드를 자신의 컬렉션에 직접 연결할 수 없습니다.

타인의 공개 컬렉션에서 장소를 발견했다면 다음 흐름을 사용합니다.

```
공개 장소 선택
→ 내 레코드 생성 또는 기존 레코드 조회
→ 내 맥락 입력
→ 내 컬렉션에 연결
```

### 공개 컬렉션 조회

현재 정책의 `자동 공개`는 인터넷 전체 공개보다는 **로그인 사용자에게 공개**로 정의하는 것이 안전합니다.

```
Access Token 필요
AND
collection.isPublished == true
AND
collection.deleted == false
AND
collection.owner.withdrawn == false
```

컬렉션이 비공개라면 소유자만 조회할 수 있습니다.

```
collection.isPublished == false
→ collection.ownerId == currentUserId 필요
```

## 7. 피드 인가 규칙

| 기능 | Access Token | 인가 규칙 |
| --- | --- | --- |
| 컬렉션 추천 피드 | 필요 | 현재 사용자 기준으로 개인화 |
| 장소 추천 피드 | 필요 | 현재 사용자 기준으로 개인화 |
| 피드 컬렉션 상세 조회 | 필요 | 공개 컬렉션만 가능 |
| 피드 장소를 내 기록에 추가 | 필요 | 현재 사용자 소유 레코드로 생성 |
| 피드에서 선반 팔로우 | 필요 | 자기 선반이 아니어야 함 |

피드에는 다음 정보만 포함합니다.

```
- 컬렉션 제목
- 장소 정보
- 공개 키워드
- 컬렉션 생성일
- 익명 선반 식별자
```

다음 정보는 포함하지 않습니다.

```
- 사용자 ID
- 사용자 이름
- 소셜 계정
- 맥락 원문
- 팔로워 목록
```

## 8. 프로필·선반 인가 규칙

| 기능 | Access Token | 인가 규칙 |
| --- | --- | --- |
| 내 프로필 조회 | 필요 | 현재 사용자 본인만 조회 |
| 팔로워 수 조회 | 필요 | 현재 사용자 본인의 수치만 조회 |
| 팔로잉 수 조회 | 필요 | 현재 사용자 본인의 수치만 조회 |
| 내 기록 조회 | 필요 | 현재 사용자 소유 기록만 조회 |
| 내 선반 조회 | 필요 | 현재 사용자 본인만 전체 정보 조회 |
| 타인의 공개 선반 조회 | 필요 | 익명 공개 정보만 조회 |
| 팔로우 중인 선반 목록 | 필요 | 현재 사용자의 팔로우 관계만 조회 |
| 팔로우 선반 이름 수정 | 필요 | 해당 팔로우 관계를 만든 사용자만 가능 |

### 내 프로필

다음 API 형태를 권장합니다.

```
GET /api/me
GET /api/me/records
GET /api/me/collections
GET /api/me/shelf
GET /api/me/following
GET /api/me/follow-stats
```

사용자 ID를 경로로 전달하지 않으므로 다른 사용자의 정보에 접근하는 실수를 줄일 수 있습니다.

## 9. 팔로우 인가 규칙

| 기능 | Access Token | 인가 규칙 |
| --- | --- | --- |
| 선반 팔로우 | 필요 | 자신의 선반은 팔로우 불가 |
| 선반 언팔로우 | 필요 | 현재 사용자가 만든 팔로우 관계만 삭제 |
| 팔로우 선반 목록 조회 | 필요 | 본인의 팔로우 관계만 조회 |
| 팔로우 선반 이름 설정 | 필요 | 현재 사용자가 만든 팔로우 관계만 수정 |
| 팔로워 목록 조회 | 제공하지 않음 | 정책상 목록 비공개 |
| 팔로잉 목록 외부 공개 | 제공하지 않음 | 본인만 조회 가능 |

### 팔로우 생성

```
targetShelf.ownerId != currentUserId
AND
동일한 팔로우 관계가 존재하지 않음
```

DB 제약조건:

```sql
UNIQUE (follower_user_id, target_shelf_id)
```

### 팔로우 선반 이름 수정

선반 이름은 선반 자체의 속성이 아니라 팔로우 관계의 속성입니다.

```
follow.followerUserId == currentUserId
```

다른 사용자가 지정한 선반 별칭은 조회하거나 수정할 수 없습니다.

## 10. 개인 설정

| 기능 | Access Token | 인가 규칙 |
| --- | --- | --- |
| 개인 설정 조회 | 필요 | 현재 사용자 본인만 가능 |
| 개인 설정 수정 | 필요 | 현재 사용자 본인만 가능 |
| 다크모드 설정 | 필요 | 현재 사용자 설정만 수정 |

API 예시:

```
GET /api/me/settings
PATCH /api/me/settings
```

## 11. 권장 API 인가 패턴

### 사용자 본인 데이터

```
GET /api/me
PATCH /api/me
DELETE /api/me
```

`userId`를 클라이언트로부터 받지 않습니다.

### 소유 리소스

```
GET /api/records/{recordId}
PATCH /api/records/{recordId}
DELETE /api/records/{recordId}
```

조회된 리소스의 소유자를 검사합니다.

```
resource.ownerId == currentUserId
```

### 공개 리소스

```
GET /api/collections/{collectionId}
```

검증 순서:

```
1. 컬렉션 존재 여부
2. 삭제 여부
3. 공개 상태 확인
4. 비공개라면 소유자 확인
5. 공개 범위에 맞는 DTO 반환
```

## 12. HTTP 응답 기준

| 상황 | 상태 코드 |
| --- | --- |
| 토큰이 없음 | `401 Unauthorized` |
| 토큰이 만료되었거나 유효하지 않음 | `401 Unauthorized` |
| 로그인했지만 행위 권한이 없음 | `403 Forbidden` |
| 존재하지 않는 리소스 | `404 Not Found` |
| 타인의 비공개 리소스 접근 | `404 Not Found` 권장 |
| 동일 장소 중복 기록 | `409 Conflict` |
| 중복 팔로우 | `409 Conflict` |
| 마지막 맥락 삭제 등 정책 위반 | `409 Conflict` |
| 요청값 형식 오류 | `400 Bad Request` |
| 입력값 검증 실패 | `422 Unprocessable Entity` 또는 `400 Bad Request`로 통일 |

타인의 비공개 리소스에는 `403` 대신 `404`를 반환하는 것이 좋습니다.

```
403:
"리소스가 존재하지만 권한이 없다"는 정보를 노출

404:
리소스 존재 여부 자체를 숨김
```

## 13. 핵심 인가 조건 요약

```
내 정보 조회
→ Access Token 필요
→ currentUserId 기준으로 조회

기록 조회·수정·삭제
→ Access Token 필요
→ record.ownerId == currentUserId

컬렉션 수정·삭제
→ Access Token 필요
→ collection.ownerId == currentUserId

컬렉션에 기록 추가
→ Access Token 필요
→ collection.ownerId == currentUserId
→ record.ownerId == currentUserId

공개 컬렉션 조회
→ Access Token 필요
→ collection.isPublished == true

팔로우
→ Access Token 필요
→ targetShelf.ownerId != currentUserId

팔로우 선반 이름 수정
→ Access Token 필요
→ follow.followerUserId == currentUserId

자연어 검색
→ Access Token 필요
→ currentUserId의 맥락만 검색

로그인·회원가입·계정 복구
→ Access Token 불필요
→ 별도의 자격 증명 또는 일회성 토큰으로 검증
```

소셜 로그인 여부가 추후 변경되더라도 위 인가 규칙은 거의 달라지지 않습니다. 달라지는 부분은 로그인 시 사용자의 신원을 확인하는 **인증 방식**뿐입니다.
