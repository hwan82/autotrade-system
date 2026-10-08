import org.gradle.api.provider.ListProperty

/** 프로세스 진입점 모듈의 런타임 클래스패스에 들어오면 안 되는 모듈과 라이브러리 그룹. */
abstract class ProcessIsolationExtension {
    /** 예: ":autotrade-sim" */
    abstract val forbiddenProjects: ListProperty<String>

    /** 예: "org.springframework" (하위 그룹 포함) */
    abstract val forbiddenGroups: ListProperty<String>
}
