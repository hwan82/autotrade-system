/**
 * 선정 프로필과 조합(ADR-007). 순수 Java 모듈이다(Spring·DB·증권사 SDK 금지).
 *
 * <ul>
 *   <li>{@code selection.profile}: 선정 프로필 형식·해시, 조합과 {@code combination_hash}</li>
 *   <li>{@code selection.result}: 선정 결과 모델과 내용 해시, 판정 사유. 결과 파일 코덱과 유효성 판정은 후속 PR</li>
 *   <li>{@code selection.calc}: 날짜 T의 선정 계산과 선정 조건 카탈로그(STR-15·16). 실험실과 생산 일괄 명령만 쓰고 실전
 *       엔진은 쓰지 않는다(ADR-007 2.7)</li>
 * </ul>
 */
package com.autotrade.selection;
