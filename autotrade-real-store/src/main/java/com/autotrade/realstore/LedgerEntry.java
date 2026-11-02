package com.autotrade.realstore;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 원장에서 읽은 기록 하나(ADR-015 2.4). 형식 버전 {@code v}는 {@link LedgerCodec}가 확인한 뒤라 담지 않는다.
 *
 * @param rseq 원장 기록 일련번호(원장마다 1부터 끊김 없이, 날짜 파일이 바뀌어도 이어짐). 멱등 키 번호 {@code keySeq}와 다르다
 * @param boot 이 줄을 쓴 엔진의 부팅 번호
 */
public record LedgerEntry(String stream, long rseq, long boot, Instant at, String type, JsonNode body, String crc) {
}
