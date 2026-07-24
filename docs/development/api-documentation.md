# API 문서화 (OpenAPI/Swagger)

시작 절차와 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를, API 계약은 [API 규약](api-conventions.md)을 따릅니다. 이 문서는 HTTP API를 OpenAPI로 문서화하고 Swagger UI로 노출하는 기준입니다.

## 도구

springdoc-openapi로 런타임에 OpenAPI 문서와 Swagger UI를 생성합니다. 코드와 문서가 어긋나지 않도록 별도 수기 문서를 만들지 않습니다.

```groovy
implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:<Spring Boot 4 호환 버전>'
```

> 이 프로젝트는 Spring Boot 4.1을 사용합니다. springdoc 버전은 **Boot 4와 호환되는 버전으로 고정**하고, 올릴 때 기동·문서 생성이 깨지지 않는지 확인합니다.

## 경로 — context path 아래

서비스 context path는 `/api/core`입니다([설정·프로파일 규약](configuration.md)). Swagger 관련 경로도 그 아래로 내려갑니다.

| 용도 | 경로 |
| --- | --- |
| Swagger UI | `/api/core/swagger-ui.html` |
| OpenAPI 문서(JSON) | `/api/core/v3/api-docs` |

context path를 떼거나 컨트롤러에 prefix를 중복하면 Swagger "Try it out" 요청 주소가 깨집니다([infra/backend-conventions](https://github.com/Team-PinLog/infra/blob/main/docs/backend-conventions.md)).

## 노출 범위

- 문서 엔드포인트를 **어느 환경에 노출할지**는 프로파일로 제어합니다. 로컬·개발에서는 열고, 운영 노출 여부는 팀 정책에 따릅니다(기본은 비노출을 권장하되 팀 합의로 결정).
- 인증을 도입하면 Swagger 경로의 접근 정책(공개/보호)을 보안 설정에 명시하고 테스트합니다([인증 PR 계약](authentication.md)).

## 작성 기준

- 컨트롤러와 DTO에 OpenAPI 애노테이션으로 요약·설명·예시를 답니다. Entity를 문서 모델로 직접 노출하지 않습니다([API 규약](api-conventions.md)).
- 공통 **오류 계약**(`code`/`message`/`traceId`)과 대표 상태 코드(400 등)를 응답 예시에 포함합니다([에러 처리 규약](error-handling.md)).
- 목록 API는 `page`/`size`/`sort` pagination 파라미터를 문서화합니다.
- API 계약을 바꾸면 애노테이션과 예시를 **같은 PR에서** 갱신합니다.

## 검증

- 애플리케이션 기동 시 OpenAPI 문서가 정상 생성되는지 확인합니다(문서 생성 실패는 설정 오류 신호).
- API 계약 변경 PR은 정상 요청·validation 400 테스트와 함께 문서 갱신을 포함합니다([테스트 규약](testing-conventions.md), [API 규약](api-conventions.md)).

## 체크리스트

- [ ] springdoc 의존성을 Boot 4 호환 버전으로 고정했다
- [ ] Swagger UI/문서 경로가 `/api/core` 아래에서 열린다
- [ ] 환경별 노출을 프로파일로 제어한다
- [ ] Entity를 문서 모델로 직접 노출하지 않는다
- [ ] 오류 계약·pagination을 문서에 반영했다
- [ ] API 계약 변경과 문서 갱신을 같은 PR에서 처리했다
