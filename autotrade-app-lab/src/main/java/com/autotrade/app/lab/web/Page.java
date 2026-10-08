package com.autotrade.app.lab.web;

import java.util.List;

/**
 * 페이징 목록 응답(UI-01: 목록은 페이징). {@code page}는 0부터.
 */
public record Page<T>(List<T> items, int page, int size, long total) {

    public static final int MAX_SIZE = 200;

    public Page {
        items = List.copyOf(items);
    }

    /** 이미 정렬된 목록에서 한 쪽을 잘라 낸다. 범위를 넘으면 빈 목록. */
    public static <T> Page<T> of(List<T> all, int page, int size) {
        if (page < 0) {
            throw ApiException.badRequest("page는 0 이상이어야 합니다");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw ApiException.badRequest("size는 1~" + MAX_SIZE + "여야 합니다");
        }
        long from = (long) page * size;
        if (from >= all.size()) {
            return new Page<>(List.of(), page, size, all.size());
        }
        int to = (int) Math.min(all.size(), from + size);
        return new Page<>(all.subList((int) from, to), page, size, all.size());
    }
}
