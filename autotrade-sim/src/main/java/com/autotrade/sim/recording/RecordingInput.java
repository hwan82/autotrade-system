package com.autotrade.sim.recording;

import java.util.List;

/** 녹화에서 만든 실험 입력의 기록(보고서와 입력 확인용). 목록({@link RecordedMarketDataSet})과 날마다 읽는 것({@link StreamedRecordingDataSet})이 같다. */
public interface RecordingInput {

    /** 데이터셋 버전. 같은 녹화 파일이면 두 구현이 같은 값을 낸다. */
    String version();

    /** 읽은 녹화일, 날짜 오름차순. */
    List<RecordedMarketDataSet.DayInfo> days();

    /** 이벤트 수(끼워 넣은 공백 포함). */
    long eventCount();
}
