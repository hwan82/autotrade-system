package com.autotrade.marketdata.daily;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** RFC 4180 CSV 최소 구현(큰따옴표 인용, 인용 안 쉼표·큰따옴표 두 개). 줄바꿈이 든 필드는 지원하지 않는다. */
final class Csv {

    private Csv() {}

    /** 첫 줄은 헤더. 헤더가 {@code expected}와 다르면 예외. */
    static List<List<String>> read(Path file, List<String> expected) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null) {
                throw new IllegalArgumentException(file.getFileName() + "이 비어 있습니다");
            }
            if (header.startsWith("﻿")) {
                header = header.substring(1);
            }
            if (!split(header).equals(expected)) {
                throw new IllegalArgumentException(file.getFileName() + " 헤더가 스키마와 다릅니다: " + header);
            }
            String line;
            int n = 1;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isEmpty()) {
                    continue;
                }
                List<String> f = split(line);
                if (f.size() != expected.size()) {
                    throw new IllegalArgumentException(file.getFileName() + " " + n + "번째 줄 열 수가 다릅니다");
                }
                rows.add(f);
            }
        }
        return rows;
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("닫히지 않은 큰따옴표");
        }
        out.add(cur.toString());
        return out;
    }
}
