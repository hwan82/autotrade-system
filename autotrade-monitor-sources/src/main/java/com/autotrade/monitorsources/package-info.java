/**
 * 감시 에이전트 표준 수집원(MON-05, D-090). {@link com.autotrade.monitorsources.SystemSource}(OSHI: CPU, 메모리, 디스크,
 * 네트워크), {@link com.autotrade.monitorsources.JvmSource}(힙, GC, 스레드, 프로세스 CPU)와 기본 임계
 * {@link com.autotrade.monitorsources.StandardSources#defaultThresholds()}.
 *
 * <p>코어({@code com.autotrade.monitor})와 패키지를 나눈 이유: 코어는 감시 프로세스 런타임(실전 경로)에 들어가 JDK 허용 목록
 * 안 타입만 쓰고, 이 패키지는 네이티브 접근(JNA)과 {@code java.lang.management}를 쓴다.
 */
package com.autotrade.monitorsources;
