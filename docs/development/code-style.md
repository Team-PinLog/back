# 코드 스타일 규약

시작 절차와 규칙은 [CONTRIBUTING.md](../../CONTRIBUTING.md)를 따릅니다. 이 문서는 Java 코드 스타일 기준과 그 강제 방식입니다.

## 컨벤션

**[Naver 캠퍼스 핵데이 Java 코딩 컨벤션](http://naver.github.io/hackday-conventions-java/)** 을 따릅니다. 전문은 공식 문서를 참조하고, 이 저장소에 복사하지 않습니다.

## 강제 방식

CI가 스타일을 **강제**합니다. 규칙이 문서상 권고에 그치지 않습니다.

- **Checkstyle** 이 CI 게이트입니다. `backend-ci / check`가 실행하는 `./gradlew clean check`에 포함되며, `maxWarnings = 0`이라 위반이 하나라도 있으면 실패합니다. 룰셋은 `config/checkstyle/naver-checkstyle-rules.xml`(Naver 공식)입니다.
- **`.editorconfig`** 는 에디터/IDE가 저장 시 자동 포맷하도록 합니다(강제 아님, 편의). IntelliJ·VS Code는 기본 지원합니다.
- **`.gitattributes`** 가 `*.java` 등 텍스트 파일을 `eol=lf`로 고정해, OS와 무관하게 개행이 일관됩니다.

Checkstyle이 검사하는 주요 항목: 개행 LF, **탭 들여쓰기**, 줄 끝 공백 금지, **줄 길이 120자**, import 순서(static 최상단), 파일 끝 개행 등.

## 핵심 규칙 요약

| 항목 | 값 |
| --- | --- |
| 들여쓰기 | **탭** (탭 폭 4) |
| 줄 길이 | 최대 **120자** |
| 개행 | **LF** (`\n`), 파일 끝 개행 필수 |
| 줄 끝 공백 | 금지 |
| import | static import를 **최상단**에 두고 그룹별 정렬 |
| 인코딩 | UTF-8 |

세부 값은 `.editorconfig`와 Naver 룰셋에 있습니다.

## 로컬 검증

완료 전 공통 검증(`./gradlew clean check --no-daemon`)에 스타일 검사가 포함됩니다. 스타일만 따로 확인하려면:

```bash
./gradlew checkstyleMain checkstyleTest --no-daemon
```

위반 리포트는 `build/reports/checkstyle/main.html`·`test.html`에서 확인합니다.

## 위반 수정

- 대부분의 포맷(탭·개행·줄 끝 공백·파일 끝 개행)은 **IDE가 `.editorconfig`로 자동 정리**합니다. 저장 시 포맷을 켜두세요.
- import 순서·줄 길이 등은 리포트를 보고 직접 고칩니다.
- 이미 배포된 룰셋 파일(`config/checkstyle/`)은 팀 합의 없이 바꾸지 않습니다.

## 체크리스트

- [ ] 탭 들여쓰기(폭 4), 줄 길이 120자 이내
- [ ] LF 개행, 파일 끝 개행, 줄 끝 공백 없음
- [ ] static import 최상단
- [ ] `./gradlew checkstyleMain checkstyleTest` 통과
