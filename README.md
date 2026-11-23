# Toss CardReader Service

토스프론트의 IC·마그네틱 리더를 혼자 맡아 열고, 다른 앱이 AIDL로 리더를 쓰게 해 주는 화면 없는 드라이버 앱입니다.

**결제 외 용도**로 사용합니다. 결제 승인, PIN 입력, 결제 암호문 생성, 결제 키 관리 기능은 없습니다.

| 항목 | 내용 |
| --- | --- |
| 서비스 버전 | 1.0.2 (versionCode 3) |
| 패키지 | `com.toss.cardreader.service` |
| 대상 환경 | 토스프론트 PLF02WH / LineageOS 13 |
| 배포 파일 | `toss-cardreader-service-1.0.2.apk` (GitHub Releases) |
| 첫 클라이언트 | 카드 리더 앱 1.3.0 이상 ([AkaKSR/Toss-CardReader](https://github.com/AkaKSR/Toss-CardReader)) |

## 왜 별도 서비스인가

`/dev/ttyS3`는 한 프로세스만 열 수 있습니다. 서비스 하나가 리더를 맡고, 카드 리더 앱과 다른 앱은 서비스에 연결해서 씁니다.

- 연결: 인텐트 액션 `com.toss.cardreader.action.BIND`, 패키지 `com.toss.cardreader.service`.
- 호출 권한: 호출할 때마다 호출 앱의 서명 인증서 SHA-256을 허용 목록(`CallerCheck.ALLOWED_SIGNERS`)과 비교합니다. 허용 목록에는 서비스 릴리스 키와 카드 리더 앱 키(1.0.0부터 사용)가 있습니다. 다른 앱은 연결은 되지만 모든 호출이 거부됩니다.
- 리더 선택: 순정 Sunmi 결제 서비스가 있으면 그 서비스를, 없으면 `/dev/ttyS3` 직접 연결을 씁니다.
- 안전 범위: IC 상태 확인, IC 전원 켜기·끄기, APDU 교환, 마그네틱 읽기 5개 명령만 보냅니다. 펌웨어·키·인증서·PIN·설정 명령은 코드에서 만들 수 없고, 단위 테스트가 허용 목록을 검사합니다.
- 리더 수명: 첫 호출에서 포트를 열고, 마지막 클라이언트가 나갈 때마다 닫습니다. 카드 대기 중일 때만 리더에 질의합니다.
- 충돌 방지: 포트를 쓰기 전에 300ms 동안 회선을 듣고, 다른 프로그램이 쓰고 있으면 손대지 않고 `BUSY`로 답합니다.
- 로그: 명령 번호, 결과 코드, 시간만 남기고 카드 데이터는 기록하지 않습니다.

상세 인터페이스는 [docs/reader_service_api.md](docs/reader_service_api.md), 연결 라이브러리 사용법은 [client/README.md](client/README.md), 리더 통신 규격은 [docs/sp_protocol.md](docs/sp_protocol.md), 실기기 시험 절차는 [docs/reader_service_device_test.md](docs/reader_service_device_test.md)에 있습니다. 문서에 나오는 `:app` 모듈(카드 리더 앱)은 [AkaKSR/Toss-CardReader](https://github.com/AkaKSR/Toss-CardReader) 저장소에 있습니다.

## 버전 기록

| 버전 | 날짜 | 변경 요약 |
| --- | --- | --- |
| 1.0.2 (versionCode 3) | 2026-11-23 | 첫 클라이언트가 나갈 때만이 아니라 마지막 클라이언트가 나갈 때마다 리더를 닫습니다(`onUnbind`가 `true`를 돌려주고 `onRebind` 추가). 다시 연결한 뒤 두 번째로 닫는 과정을 실기기에서 확인했습니다. |
| 1.0.1 (versionCode 2) | 2026-11-23 | 리더 링크를 다시 열면 프레임 번호를 00부터 다시 셉니다. |
| 1.0.0 (versionCode 1) | 2026-11-23 | 첫 버전: AIDL 서비스, 서명 인증서 허용 목록, 연결 라이브러리, 시험 앱 |

부팅 직후 자동 시작(`BOOT_COMPLETED`)은 아직 실기기 재부팅으로 확인하지 않았습니다.

## 설치

```sh
adb install -r toss-cardreader-service-1.0.2.apk
adb shell am start-foreground-service -n com.toss.cardreader.service/.ReaderService
```

서비스는 부팅과 업데이트 때 스스로 시작합니다. 실행 중에는 최소 우선순위 알림이 표시됩니다. 같은 서명 키로 만든 1.0.0·1.0.1 위에 덮어 설치할 수 있습니다.

## 모듈과 빌드

| 모듈 | 내용 |
| --- | --- |
| `service` | 서비스 앱 |
| `reader` | 공용 드라이버 (Sunmi 서비스 또는 직접 연결, EMV 읽기, 세션 규칙) |
| `client` | AIDL, 데이터 클래스, `CardReaderClient` (AAR) |
| `testclient` | 시험 앱: `trusted`·`second`(릴리스 키), `foreign`(다른 키, 거부 확인용) |

JDK 21과 Android SDK 34 플랫폼·빌드 도구를 준비하고 SDK 위치를 설정합니다.

```sh
./gradlew test assembleRelease
```

릴리스 서명 설정(`keystore.properties`)과 키스토어는 Git에서 제외합니다. 서명 설정이 없으면 디버그 키로 빌드되며, 그 APK는 배포 APK 위에 덮어 설치할 수 없습니다.

## 제거

```sh
adb uninstall com.toss.cardreader.service
```

서비스를 제거하면 카드 리더 앱 1.3.0은 Sunmi 서비스 또는 자체 직접 연결로 돌아갑니다.
