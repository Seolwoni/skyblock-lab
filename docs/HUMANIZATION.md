# Humanization

규칙은 `AGENTS.md`의 `## Humanization` 섹션을 따른다.

## 범위
포함: 감지, 정지, 응답, 재개 지연
제외: 패킷 지터, sub-tick

## 현황

| 항목 | 상태 | 위치 |
|---|---|---|
| 감지: 파티 초대, `From <name>:`, 스태프 태그 네임태그, warp 없는 30블록 텔레포트 | 구현 | `utils/WatchSigns.kt`, `AutoFishing` Failsafe 섹션 |
| 정지: 감지 시 즉시 정지 + 알림 + journal 기록 | 구현 | `AutoFishing.failStop` |
| 응답 | 미구현 (Claude가 작성하지 않음) | - |
| 재개 지연 | 미구현 (Claude가 작성하지 않음) | - |

## 수치 (전부 설정값)

| 항목 | 기본값 | 설정 |
|---|---|---|
| 캐스트 실패 | 1.5% (2-4° 빗나감) | Auto Fishing › Mistakes › Cast Fail |
| 늦은 리일 | 2%, +7-14틱 | Late Reel / Late Reel By |
| 멍때림 | 캐스트당 3%, 3-8 s | Zone Out / Min / Max |
| 아이들 조준 | 랜덤워크, ±3° 경계 | Auto Fishing › Look › Movement Size / Drift Range |
| 클릭 면 내부 | ±0.12 | Human Behaviour › Aim Spread |
| 오버슈트 | 회전량(최대 10°)의 0.1-0.3, 20° 넘는 회전만 | Human Behaviour › Overshoot / Min / Max |

순수 수학: `utils/HumanMotion.kt`, `utils/WatchSigns.kt` ↔ `src/test/kotlin/human/HumanMotionTest.kt`, `WatchSignsTest.kt`.
