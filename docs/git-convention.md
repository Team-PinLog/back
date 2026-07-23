# Git 커밋 컨벤션

이 문서는 [Conventional Commits 1.0.0](https://www.conventionalcommits.org/ko/v1.0.0/)을 기준으로
커밋 메시지 작성 형식을 정리한다.

## 형식

```text
<type>[optional scope][optional !]: <description>

[optional body]

[optional footer(s)]
```

커밋 메시지는 type, 선택적 scope, 선택적 `!`, description으로 시작한다.
필요하면 본문과 하나 이상의 꼬리말을 추가할 수 있다.

## Type

Type은 변경의 성격을 나타내는 필수 접두어다.

### `feat`

새로운 기능을 추가할 때 사용한다. 유의적 버전의 `MINOR`와 관련된다.

```text
feat: add user profile search
```

### `fix`

버그를 수정할 때 사용한다. 유의적 버전의 `PATCH`와 관련된다.

```text
fix: prevent duplicate profile creation
```

### 그 밖의 Type

명세는 `feat`와 `fix` 이외의 type도 허용한다.

```text
docs: correct api documentation
build: update build configuration
chore: remove unused files
ci: update continuous integration workflow
style: format source code
refactor: simplify validation logic
perf: reduce query execution time
test: add profile service tests
```

추가 type은 `BREAKING CHANGE`를 포함하지 않는 한 유의적 버전에 직접적인 영향을 주지 않는다.

## Scope

Scope는 변경이 적용되는 코드 영역을 보충해서 설명하는 선택 사항이다.
Type 바로 뒤에 괄호로 감싼 명사를 작성한다.

```text
feat(parser): support array values
fix(cache): prevent stale data lookup
```

Scope가 필요하지 않으면 생략한다.

## Description

Description은 변경 사항을 짧게 요약하는 필수 항목이다.
Type과 scope 다음에 콜론과 공백(`: `)을 넣고 작성한다.

```text
fix: handle empty configuration values
```

## Body

본문은 변경에 대한 추가 문맥을 설명하는 선택 사항이다.
Description 다음에 빈 줄을 두고 작성하며 여러 단락을 사용할 수 있다.

```text
fix: prevent outdated responses from replacing recent data

Assign an identifier to each request and ignore responses that do not match
the most recent request.
```

## Footer

꼬리말은 본문 다음의 빈 줄 뒤에 작성한다. 본문이 없다면 description 다음에 빈 줄을 둔다.
각 꼬리말은 토큰과 값으로 구성한다.

```text
Reviewed-by: username
Refs: #123
```

토큰에 여러 단어가 필요하면 공백 대신 하이픈을 사용한다.
`BREAKING CHANGE`는 예외적으로 공백을 사용한다.

## Breaking Change

호환되지 않는 변경은 Type 또는 scope 뒤에 `!`를 붙이거나 `BREAKING CHANGE` 꼬리말로 표시한다.
Type의 종류와 관계없이 사용할 수 있으며 유의적 버전의 `MAJOR`와 관련된다.

### `!` 사용

```text
feat!: change the default response format
```

Scope와 함께 사용할 수도 있다.

```text
feat(api)!: change the default response format
```

### 꼬리말 사용

```text
feat: support configuration inheritance

BREAKING CHANGE: the extends field now references another configuration file.
```

`BREAKING CHANGE`는 반드시 대문자로 작성한다. 꼬리말에서는 `BREAKING-CHANGE`도 같은 의미로 사용할 수 있다.

## Revert

Conventional Commits는 되돌리기의 구체적인 동작을 별도로 규정하지 않는다.
필요하면 `revert` type과 참조 꼬리말을 사용할 수 있다.

```text
revert: restore the previous response format

Refs: 676104e
```

## 예시

### Type과 description

```text
docs: correct spelling in api documentation
```

### Scope 포함

```text
feat(language): add Korean translations
```

### 본문과 여러 꼬리말 포함

```text
fix: prevent concurrent request conflicts

Track the latest request and discard responses from older requests.

Reviewed-by: username
Refs: #123
```

## 핵심 규칙

- 커밋은 type으로 시작한다.
- Scope는 선택 사항이며 괄호 안에 작성한다.
- Type 또는 scope와 description 사이에는 콜론과 공백을 사용한다.
- 본문은 description 다음의 빈 줄 뒤에 작성한다.
- 꼬리말은 본문 다음의 빈 줄 뒤에 작성한다.
- 호환되지 않는 변경은 `!` 또는 `BREAKING CHANGE`로 표시한다.
- `feat`는 새로운 기능, `fix`는 버그 수정을 의미한다.
