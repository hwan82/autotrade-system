/**
 * 선정 프로필과 조합(ADR-007). 순수 Java 모듈이다(Spring·DB·증권사 SDK 금지).
 *
 * <ul>
 *   <li>{@code selection.profile}: 선정 프로필 형식·해시, 조합과 {@code combination_hash}</li>
 *   <li>{@code selection.result}: 선정 결과의 판정 사유. 결과 파일 코덱과 유효성 판정은 후속 PR</li>
 * </ul>
 */
package com.autotrade.selection;
