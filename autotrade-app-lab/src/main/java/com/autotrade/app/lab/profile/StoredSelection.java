package com.autotrade.app.lab.profile;

import com.autotrade.selection.profile.SelectionProfile;
import java.time.Instant;
import java.util.Objects;

/**
 * 저장된 선정 프로필 한 버전과 이력 정보({@link StoredProfile}과 같은 모양).
 *
 * @param basedOn 이 버전을 만든 원본({@code id@version}). 처음 만든 프로필이면 빈 문자열
 * @param createdAt 저장 시각(주입된 Clock, UTC)
 * @param note 변경 메모. 해시에 들어가지 않는다
 */
public record StoredSelection(SelectionProfile profile, String basedOn, Instant createdAt, String note) {

    public StoredSelection {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(createdAt, "createdAt");
        basedOn = basedOn == null ? "" : basedOn;
        note = note == null ? "" : note;
    }
}
