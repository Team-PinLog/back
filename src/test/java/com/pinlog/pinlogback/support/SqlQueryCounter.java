package com.pinlog.pinlogback.support;

import java.io.Closeable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 실제로 나간 SQL을 센다. "N+1이 아니다"를 <b>측정</b>으로 말하기 위한 도구다 — 코드를 읽어
 * "일괄 조회로 짰다"고 주장하는 것과, 후보를 늘려도 쿼리 수가 그대로임을 보이는 것은 다르다.
 *
 * <p>{@code feed-tests.md} 9장(N4~N7)이 "쿼리 카운터로 검증한다"고 적어 두고도 카운터가 없어
 * 검증되지 않은 채로 있었다. S15P11A705-252에서 표시값 조회의 N+1 여부를 재기 위해 만들면서
 * 그 자리를 함께 채운다.
 *
 * <p><b>세는 대상은 {@link Connection#prepareStatement} 호출이다.</b> {@code JdbcTemplate}·Hibernate가
 * 모두 이 경로를 타므로 한 요청이 실제로 몇 번 DB에 왕복했는지가 그대로 드러난다. 커넥션 풀은
 * {@code prepareStatement}를 캐시하지 않으므로 호출 수가 왕복 수와 어긋나지 않는다.
 *
 * <p>쓰려면 테스트 클래스에 {@code @Import(SqlQueryCounter.Config.class)}를 붙인다. Spring 컨텍스트
 * 캐시 키가 달라져 <b>컨텍스트가 하나 더 뜨므로</b> 필요한 테스트에만 붙인다.
 */
public class SqlQueryCounter {

	private final List<String> statements = Collections.synchronizedList(new ArrayList<>());

	/** 측정 구간의 시작. 요청 직전에 부른다 — 컨텍스트 기동 중의 쿼리가 섞이지 않도록. */
	public void reset() {
		statements.clear();
	}

	/**
	 * {@code fragment}를 포함하는 SQL이 몇 번 나갔는지. 조각으로 거르는 이유는 한 요청이 여러
	 * 종류의 쿼리를 내보내기 때문이다 — 총량이 아니라 <b>이 쿼리</b>가 몇 번인지를 봐야 한다.
	 */
	public long count(String fragment) {
		return matching(fragment).size();
	}

	public List<String> matching(String fragment) {
		synchronized (statements) {
			return statements.stream().filter(sql -> sql.contains(fragment)).toList();
		}
	}

	private DataSource wrap(DataSource delegate) {
		// Closeable을 함께 구현시키는 이유: Spring이 destroy 메서드를 후처리 결과 객체의 타입에서
		// 추론하므로, DataSource만 구현하면 close()가 없어 컨텍스트 종료 시 풀이 닫히지 않는다.
		Class<?>[] interfaces = delegate instanceof Closeable
			? new Class<?>[] {DataSource.class, Closeable.class}
			: new Class<?>[] {DataSource.class};
		return (DataSource)Proxy.newProxyInstance(getClass().getClassLoader(), interfaces,
			(proxy, method, args) -> {
				Object result = invoke(delegate, method, args);
				return result instanceof Connection connection ? wrap(connection) : result;
			});
	}

	private Connection wrap(Connection delegate) {
		return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),
			new Class<?>[] {Connection.class}, (proxy, method, args) -> {
				if ("prepareStatement".equals(method.getName())
					&& args != null && args.length > 0 && args[0] instanceof String sql) {
					statements.add(sql);
				}
				return invoke(delegate, method, args);
			});
	}

	/** 대상이 던진 예외를 그대로 올려보낸다. 감싸면 {@code SQLException} 처리 경로가 달라진다. */
	private Object invoke(Object delegate, java.lang.reflect.Method method, Object[] args)
		throws Throwable {
		try {
			return method.invoke(delegate, args);
		} catch (InvocationTargetException e) {
			throw e.getTargetException();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		public SqlQueryCounter sqlQueryCounter() {
			return new SqlQueryCounter();
		}

		/**
		 * {@code static}이어야 한다. 인스턴스 메서드로 두면 이 설정 클래스가 다른 후처리기보다
		 * 먼저 생성되어 경고가 뜬다. {@link ObjectProvider}로 받는 것도 같은 이유다 — 후처리기
		 * 등록 시점에 카운터를 실체화하지 않는다.
		 */
		@Bean
		public static BeanPostProcessor sqlCountingDataSource(ObjectProvider<SqlQueryCounter> counter) {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String name) {
					return bean instanceof DataSource dataSource
						? counter.getObject().wrap(dataSource)
						: bean;
				}
			};
		}
	}
}
